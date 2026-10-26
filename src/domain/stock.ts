import { all, get, nowIso, run, transaction, type Db } from '../db/db.ts';
import { newUuid } from './ids.ts';

/**
 * Stock accounting.
 *
 * `stock_movements` is the append-only source of truth; `stock_on_hand` is a
 * cache maintained in the *same transaction* as each movement so reads are O(1)
 * and can always be rebuilt with `rebuildStockOnHand`. A completed sale is never
 * rejected for insufficient stock — stock may go negative and be flagged.
 */

export type ReasonCode =
  | 'sale'
  | 'goods_received'
  | 'adjustment'
  | 'disbursement'
  | 'refund'
  | 'void'
  | 'transfer_out'
  | 'transfer_in'
  | 'stocktake';

/** Reason codes that a human may cite when adjusting stock outside a sale. */
export const MANUAL_REASON_CODES: readonly ReasonCode[] = [
  'goods_received',
  'adjustment',
  'disbursement',
  'transfer_out',
  'transfer_in',
  'stocktake',
];

export interface MovementInput {
  deviceId: string;
  productId: string;
  quantityDelta: number;
  reasonCode: ReasonCode;
  userId: string;
  refType?: string | null;
  refId?: string | null;
  approvalId?: string | null;
  occurredAt?: string;
  /** When true, a movement that drives on-hand below zero is flagged for review. */
  reviewIfNegative?: boolean;
}

export interface MovementRow {
  movement_id: string;
  device_id: string;
  product_id: string;
  quantity_delta: number;
  reason_code: ReasonCode;
  ref_type: string | null;
  ref_id: string | null;
  user_id: string;
  approval_id: string | null;
  requires_review: number;
  occurred_at: string;
  created_at: string;
}

export class StockError extends Error {
  override name = 'StockError';
}

/**
 * Append a movement and update the on-hand cache atomically. The cache update is
 * an upsert (`+ delta`) so it is correct whether or not a row exists yet, and it
 * never reads-then-writes the value, avoiding a lost update under concurrency.
 */
export function recordMovement(db: Db, input: MovementInput): MovementRow {
  if (!Number.isInteger(input.quantityDelta) || input.quantityDelta === 0) {
    throw new StockError(`quantityDelta must be a non-zero integer, got ${input.quantityDelta}`);
  }
  return transaction(db, () => {
    const occurredAt = input.occurredAt ?? nowIso();
    const current = onHand(db, input.productId);
    const resulting = current + input.quantityDelta;
    const requiresReview = input.reviewIfNegative && resulting < 0 ? 1 : 0;

    const row: MovementRow = {
      movement_id: newUuid(),
      device_id: input.deviceId,
      product_id: input.productId,
      quantity_delta: input.quantityDelta,
      reason_code: input.reasonCode,
      ref_type: input.refType ?? null,
      ref_id: input.refId ?? null,
      user_id: input.userId,
      approval_id: input.approvalId ?? null,
      requires_review: requiresReview,
      occurred_at: occurredAt,
      created_at: nowIso(),
    };

    run(
      db,
      `INSERT INTO stock_movements
         (movement_id, device_id, product_id, quantity_delta, reason_code, ref_type, ref_id,
          user_id, approval_id, requires_review, occurred_at, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      row.movement_id,
      row.device_id,
      row.product_id,
      row.quantity_delta,
      row.reason_code,
      row.ref_type,
      row.ref_id,
      row.user_id,
      row.approval_id,
      row.requires_review,
      row.occurred_at,
      row.created_at,
    );

    run(
      db,
      `INSERT INTO stock_on_hand (product_id, quantity, updated_at)
       VALUES (?, ?, ?)
       ON CONFLICT (product_id) DO UPDATE SET
         quantity = stock_on_hand.quantity + excluded.quantity,
         updated_at = excluded.updated_at`,
      input.productId,
      input.quantityDelta,
      occurredAt,
    );

    return row;
  });
}

export function onHand(db: Db, productId: string): number {
  const row = get<{ quantity: number }>(db, 'SELECT quantity FROM stock_on_hand WHERE product_id = ?', productId);
  return row?.quantity ?? 0;
}

export function movementsFor(db: Db, productId: string): MovementRow[] {
  return all<MovementRow>(db, 'SELECT * FROM stock_movements WHERE product_id = ? ORDER BY occurred_at ASC', productId);
}

/** Recompute the cache from the ledger; used for audits and after a restore. */
export function rebuildStockOnHand(db: Db): void {
  transaction(db, () => {
    run(db, 'DELETE FROM stock_on_hand');
    run(
      db,
      `INSERT INTO stock_on_hand (product_id, quantity, updated_at)
       SELECT product_id, SUM(quantity_delta), MAX(created_at)
       FROM stock_movements
       GROUP BY product_id`,
    );
  });
}

/** True when the ledger and the cache agree for every product. */
export function verifyStockCache(db: Db): { ok: boolean; mismatches: Array<{ productId: string; cache: number; ledger: number }> } {
  const ledger = all<{ product_id: string; quantity: number }>(
    db,
    'SELECT product_id, SUM(quantity_delta) AS quantity FROM stock_movements GROUP BY product_id',
  );
  const mismatches: Array<{ productId: string; cache: number; ledger: number }> = [];
  for (const row of ledger) {
    const cache = onHand(db, row.product_id);
    if (cache !== row.quantity) mismatches.push({ productId: row.product_id, cache, ledger: row.quantity });
  }
  return { ok: mismatches.length === 0, mismatches };
}

export function productsNeedingReview(db: Db): MovementRow[] {
  return all<MovementRow>(db, 'SELECT * FROM stock_movements WHERE requires_review = 1 ORDER BY occurred_at DESC');
}
