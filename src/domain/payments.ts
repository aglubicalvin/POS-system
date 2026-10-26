import { all, get, nowIso, run, transaction, type Db } from '../db/db.ts';
import { appendAudit } from './audit.ts';
import { newUuid } from './ids.ts';
import { sum } from './money.ts';
import { enqueue } from './outbox.ts';

/**
 * Payment is a separate lifecycle from the sale: `pending -> confirmed|failed`.
 *
 * Mobile-money and card confirmations arrive asynchronously (and sometimes
 * twice), so `createPayment` is keyed by an idempotency key and every transition
 * is itself idempotent — re-confirming a confirmed payment is a no-op that
 * returns the current row instead of double-counting revenue.
 */

export type PaymentMethod = 'cash' | 'mobile_money' | 'card' | 'credit';
export type PaymentStatus = 'pending' | 'confirmed' | 'failed';

export interface PaymentRow {
  payment_id: string;
  sale_id: string;
  method: PaymentMethod;
  amount: number;
  status: PaymentStatus;
  idempotency_key: string;
  provider: string | null;
  provider_ref: string | null;
  failure_reason: string | null;
  reconciled_at: string | null;
  created_at: string;
  updated_at: string;
}

export class PaymentError extends Error {
  override name = 'PaymentError';
}

const ALLOWED: Record<PaymentStatus, readonly PaymentStatus[]> = {
  pending: ['confirmed', 'failed'],
  confirmed: [],
  failed: [],
};

export interface CreatePaymentInput {
  saleId: string;
  method: PaymentMethod;
  amount: number;
  /** Provider/terminal idempotency key. Derived from sale+method when omitted. */
  idempotencyKey?: string;
  provider?: string;
}

export function createPayment(db: Db, input: CreatePaymentInput): PaymentRow {
  if (!Number.isInteger(input.amount) || input.amount <= 0) {
    throw new PaymentError('payment amount must be a positive integer in minor units');
  }
  const key = input.idempotencyKey ?? `${input.saleId}:${input.method}:${input.amount}`;

  return transaction(db, () => {
    const existing = findPaymentByKey(db, key);
    if (existing) return existing;

    const at = nowIso();
    const row: PaymentRow = {
      payment_id: newUuid(),
      sale_id: input.saleId,
      method: input.method,
      amount: input.amount,
      status: 'pending',
      idempotency_key: key,
      provider: input.provider ?? null,
      provider_ref: null,
      failure_reason: null,
      reconciled_at: null,
      created_at: at,
      updated_at: at,
    };
    run(
      db,
      `INSERT INTO payments
         (payment_id, sale_id, method, amount, status, idempotency_key, provider,
          provider_ref, failure_reason, reconciled_at, created_at, updated_at)
       VALUES (?, ?, ?, ?, 'pending', ?, ?, NULL, NULL, NULL, ?, ?)`,
      row.payment_id,
      row.sale_id,
      row.method,
      row.amount,
      row.idempotency_key,
      row.provider,
      at,
      at,
    );
    return row;
  });
}

export function findPaymentByKey(db: Db, key: string): PaymentRow | undefined {
  return get<PaymentRow>(db, 'SELECT * FROM payments WHERE idempotency_key = ?', key);
}

export function findPayment(db: Db, paymentId: string): PaymentRow | undefined {
  return get<PaymentRow>(db, 'SELECT * FROM payments WHERE payment_id = ?', paymentId);
}

export function listPayments(db: Db, saleId: string): PaymentRow[] {
  return all<PaymentRow>(db, 'SELECT * FROM payments WHERE sale_id = ? ORDER BY created_at', saleId);
}

function transition(db: Db, paymentId: string, to: PaymentStatus, patch: Partial<PaymentRow> = {}): PaymentRow {
  return transaction(db, () => {
    const payment = findPayment(db, paymentId);
    if (!payment) throw new PaymentError(`payment ${paymentId} not found`);
    if (payment.status === to) return payment; // idempotent repeat
    if (!ALLOWED[payment.status].includes(to)) {
      throw new PaymentError(`illegal payment transition ${payment.status} -> ${to}`);
    }
    run(
      db,
      `UPDATE payments SET status = ?, provider_ref = COALESCE(?, provider_ref),
         failure_reason = COALESCE(?, failure_reason), updated_at = ? WHERE payment_id = ?`,
      to,
      patch.provider_ref ?? null,
      patch.failure_reason ?? null,
      nowIso(),
      paymentId,
    );
    const updated = findPayment(db, paymentId)!;
    // Attribute the audit + outbox rows to the device that owns the sale so the
    // per-device audit chain stays contiguous and the event routes home.
    const deviceId =
      get<{ device_id: string }>(db, 'SELECT device_id FROM sales WHERE sale_id = ?', updated.sale_id)?.device_id ?? '';
    appendAudit(db, {
      deviceId,
      actorId: null,
      action: `payment.${to}`,
      entityType: 'payment',
      entityId: paymentId,
      details: {
        saleId: updated.sale_id,
        method: updated.method,
        amount: updated.amount,
        providerRef: updated.provider_ref,
      },
    });
    enqueue(db, {
      deviceId,
      eventType: 'payment.updated',
      aggregateType: 'payment',
      aggregateId: paymentId,
      payload: { paymentId, saleId: updated.sale_id, status: to, providerRef: updated.provider_ref },
    });
    return updated;
  });
}

/** Cashier/terminal confirms funds received. Idempotent. */
export function confirmPayment(db: Db, paymentId: string, providerRef?: string): PaymentRow {
  return transition(db, paymentId, 'confirmed', { provider_ref: providerRef ?? null });
}

/** Provider or cashier marks the attempt failed. Idempotent. */
export function failPayment(db: Db, paymentId: string, reason: string): PaymentRow {
  return transition(db, paymentId, 'failed', { failure_reason: reason });
}

export interface ReconciliationResult {
  payment: PaymentRow;
  matched: boolean;
  reason?: 'amount-mismatch' | 'reference-mismatch' | 'not-confirmed';
}

/**
 * Reconcile a payment against the provider's statement. Never mutates the amount
 * or status — a mismatch is reported so a human can investigate, and the
 * reconciliation timestamp records that it was checked.
 */
export function reconcilePayment(
  db: Db,
  paymentId: string,
  statement: { amount: number; providerRef: string },
): ReconciliationResult {
  return transaction(db, () => {
    const payment = findPayment(db, paymentId);
    if (!payment) throw new PaymentError(`payment ${paymentId} not found`);
    if (payment.status !== 'confirmed') {
      return { payment, matched: false, reason: 'not-confirmed' };
    }
    if (payment.amount !== statement.amount) {
      return { payment, matched: false, reason: 'amount-mismatch' };
    }
    if (payment.provider_ref && payment.provider_ref !== statement.providerRef) {
      return { payment, matched: false, reason: 'reference-mismatch' };
    }
    run(db, 'UPDATE payments SET reconciled_at = ?, updated_at = ? WHERE payment_id = ?', nowIso(), nowIso(), paymentId);
    return { payment: findPayment(db, paymentId)!, matched: true };
  });
}

export function confirmedTotal(db: Db, saleId: string): number {
  const rows = all<{ amount: number }>(
    db,
    `SELECT amount FROM payments WHERE sale_id = ? AND status = 'confirmed'`,
    saleId,
  );
  return sum(rows.map((r) => r.amount));
}

export function saleIsPaid(db: Db, saleId: string, saleTotal: number): boolean {
  return confirmedTotal(db, saleId) >= saleTotal;
}
