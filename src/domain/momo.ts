import { appendAudit } from './audit.ts';
import { get, nowIso, run, type Db } from '../db/db.ts';
import { confirmPayment, failPayment, type PaymentRow } from './payments.ts';

/**
 * Till-side mobile-money: the ASYNC side of the payment lifecycle.
 *
 * A MoMo sale is recorded `pending` by checkout; only the provider decides
 * `confirmed`/`failed`. The till never trusts a cashier's word for it —
 * `initiateMobileMoneyPayment` hands the attempt to the configured provider
 * seam and polls `verifyMobileMoneyPayment`, which may transition the exact
 * pending row once, atomically, and records the audit trail the sync tests
 * already rely on.
 *
 * There is no default provider: without one configured (offline store, late
 * business decision) initiation is refused and pending attempts stay pending
 * until either a provider is attached or the cashier records cash instead.
 * Credentials belong to the provider implementation, never to this file.
 */

export class MomoError extends Error {
  override name = 'MomoError';
}

export interface MomoInitiationRequest {
  paymentId: string;
  amount: number;
  currency: string;
  /** The payment's idempotency key — a provider may dedup retries on it. */
  reference: string;
}

export interface MomoInitiationResult {
  provider: string;
  providerRef: string;
  /** Human-facing hint (STK-push message, dial code, …) shown to the cashier. */
  instructions?: string;
}

export interface MomoVerification {
  status: 'confirmed' | 'failed' | 'pending';
  providerRef?: string | null;
  failureReason?: string | null;
}

/**
 * The pluggable vendor seam. A real provider adapter (MTN MoMo, Paystack, …)
 * implements this against its SDK/API; tests and the unconfigured till use a
 * fake or nothing at all.
 */
export interface MomoProvider {
  readonly name: string;
  initiate(request: MomoInitiationRequest): Promise<MomoInitiationResult>;
  verify(request: { paymentId: string; providerRef: string; amount: number }): Promise<MomoVerification>;
}

export function findPaymentRow(db: Db, paymentId: string): PaymentRow | undefined {
  return get<PaymentRow>(db, 'SELECT * FROM payments WHERE payment_id = ?', paymentId);
}

export interface MomoInitiationOutcome {
  payment: PaymentRow;
  initiated: boolean;
  /** Present only on the call that actually reached the provider. */
  instructions?: string;
}

/** Initiate a pending MoMo attempt. Idempotent: an already-initiated row is returned, not re-sent. */
export async function initiateMobileMoneyPayment(
  db: Db,
  paymentId: string,
  provider: MomoProvider,
): Promise<MomoInitiationOutcome> {
  const payment = findPaymentRow(db, paymentId);
  if (!payment) throw new MomoError(`payment ${paymentId} not found`);
  if (payment.method !== 'mobile_money') {
    throw new MomoError(`payment ${paymentId} is a ${payment.method} attempt; only mobile_money can be initiated`);
  }
  if (payment.status !== 'pending') {
    throw new MomoError(`payment ${paymentId} is already ${payment.status}`);
  }

  if (payment.provider && payment.provider_ref) {
    return { payment, initiated: false };
  }


  const initiation = await provider.initiate({
    paymentId,
    amount: payment.amount,
    currency: 'GHS',
    reference: payment.idempotency_key,
  });
  if (!initiation.providerRef || !initiation.providerRef.trim()) {
    throw new MomoError('provider did not return a usable reference');
  }
  const at = nowIso();
  run(
    db,
    `UPDATE payments SET provider = ?, provider_ref = ?, updated_at = ? WHERE payment_id = ?`,
    initiation.provider,
    initiation.providerRef.trim(),
    at,
    paymentId,
  );
  const updated = findPaymentRow(db, paymentId)!;
  const deviceId = get<{ device_id: string }>(db, 'SELECT device_id FROM sales WHERE sale_id = ?', updated.sale_id)?.device_id ?? '';
  appendAudit(db, {
    deviceId,
    actorId: null,
    action: 'payment.momo_initiated',
    entityType: 'payment',
    entityId: paymentId,
    details: {
      saleId: updated.sale_id,
      amount: updated.amount,
      provider: initiation.provider,
      providerRef: initiation.providerRef.trim(),
    },
  });
  return { payment: updated, initiated: true, instructions: initiation.instructions };
}

/** Ask the provider about a pending attempt; transition the row only on its verdict. */
export async function verifyMobileMoneyPayment(
  db: Db,
  paymentId: string,
  provider: MomoProvider,
): Promise<PaymentRow> {
  const payment = findPaymentRow(db, paymentId);
  if (!payment) throw new MomoError(`payment ${paymentId} not found`);
  if (payment.method !== 'mobile_money') throw new MomoError(`payment ${paymentId} is not a mobile-money attempt`);
  if (payment.status !== 'pending') return payment; // nothing pending to verify

  const verdict = await provider.verify({
    paymentId,
    providerRef: payment.provider_ref ?? '',
    amount: payment.amount,
  });
  if (verdict.status === 'confirmed') return confirmPayment(db, paymentId, verdict.providerRef || payment.provider_ref || undefined);
  if (verdict.status === 'failed') return failPayment(db, paymentId, verdict.failureReason ?? 'provider reported failure');
  return payment;
}

export interface PaymentStatusView {
  paymentId: string;
  saleId: string;
  method: string;
  amount: number;
  status: string;
  provider: string | null;
  providerRef: string | null;
  failureReason: string | null;
  reconciledAt: string | null;
}

/** The fields the cashier-facing status poll may show. Amounts stay visible; no FKs leak. */
export function paymentStatusView(db: Db, payment: PaymentRow): PaymentStatusView {
  return {
    paymentId: payment.payment_id,
    saleId: payment.sale_id,
    method: payment.method,
    amount: payment.amount,
    status: payment.status,
    provider: payment.provider,
    providerRef: payment.provider_ref,
    failureReason: payment.failure_reason,
    reconciledAt: payment.reconciled_at,
  };
}
