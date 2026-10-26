import { all, get, type Db } from '../db/db.ts';

/**
 * Reporting reads straight from the ledger. Sales totals and stock levels are
 * derived, never stored as mutable counters, so a report cannot drift from the
 * transactions that produced it.
 */

export interface DailySalesRow {
  day: string;
  salesCount: number;
  grossTotal: number;
  discountTotal: number;
  taxTotal: number;
  netTotal: number;
}

/** Sales per day. Refunds and voids reduce the net; they never delete a sale. */
export function dailySales(db: Db, fromDay: string, toDay: string, deviceId?: string): DailySalesRow[] {
  const deviceClause = deviceId ? 'AND device_id = ?' : '';
  const params: string[] = [fromDay, toDay];
  if (deviceId) params.push(deviceId);

  const rows = all<{ day: string; kind: string; n: number; total: number; discount: number; tax: number }>(
    db,
    `SELECT substr(created_at, 1, 10) AS day, kind, COUNT(*) AS n,
            SUM(total) AS total, SUM(discount_total) AS discount, SUM(tax_total) AS tax
       FROM sales
      WHERE substr(created_at, 1, 10) BETWEEN ? AND ? ${deviceClause}
      GROUP BY day, kind
      ORDER BY day`,
    ...params,
  );

  const byDay = new Map<string, DailySalesRow>();
  for (const row of rows) {
    const entry =
      byDay.get(row.day) ?? { day: row.day, salesCount: 0, grossTotal: 0, discountTotal: 0, taxTotal: 0, netTotal: 0 };
    const sign = row.kind === 'sale' ? 1 : -1;
    if (row.kind === 'sale') entry.salesCount += row.n;
    entry.grossTotal += sign * row.total;
    entry.discountTotal += sign * row.discount;
    entry.taxTotal += sign * row.tax;
    byDay.set(row.day, entry);
  }
  for (const entry of byDay.values()) entry.netTotal = entry.grossTotal;
  return [...byDay.values()];
}

export interface StockLevelRow {
  productId: string;
  sku: string;
  name: string;
  onHand: number;
  threshold: number;
  low: boolean;
}

export function stockLevels(db: Db): StockLevelRow[] {
  const rows = all<{ product_id: string; sku: string; name: string; quantity: number; low_stock_threshold: number }>(
    db,
    `SELECT p.product_id, p.sku, p.name,
            COALESCE(s.quantity, 0) AS quantity,
            p.low_stock_threshold
       FROM products p
       LEFT JOIN stock_on_hand s ON s.product_id = p.product_id
      WHERE p.is_active = 1
      ORDER BY p.name`,
  );
  return rows.map((row) => ({
    productId: row.product_id,
    sku: row.sku,
    name: row.name,
    onHand: row.quantity,
    threshold: row.low_stock_threshold,
    low: row.quantity <= row.low_stock_threshold,
  }));
}

export function lowStockAlerts(db: Db): StockLevelRow[] {
  return stockLevels(db).filter((row) => row.low);
}

export interface ReceivedVsSoldRow {
  productId: string;
  sku: string;
  name: string;
  received: number;
  sold: number;
  adjusted: number;
  onHand: number;
  /** sold + adjusted + onHand should equal received for a healthy product. */
  balances: boolean;
}

/**
 * Reconcile what came in against what went out, using stock_movements as the
 * source of truth. `balances` is the shrink/theft check: for every product the
 * inflows must equal the outflows plus what is left on the shelf.
 */
export function receivedVsSold(db: Db, fromDay?: string, toDay?: string): ReceivedVsSoldRow[] {
  const clauses: string[] = [];
  const params: string[] = [];
  if (fromDay) {
    clauses.push('substr(occurred_at, 1, 10) >= ?');
    params.push(fromDay);
  }
  if (toDay) {
    clauses.push('substr(occurred_at, 1, 10) <= ?');
    params.push(toDay);
  }
  const where = clauses.length ? `WHERE ${clauses.join(' AND ')}` : '';

  const rows = all<{
    product_id: string;
    sku: string;
    name: string;
    received: number;
    sold: number;
    adjusted: number;
  }>(
    db,
    `SELECT p.product_id, p.sku, p.name,
            COALESCE(SUM(CASE WHEN m.reason_code = 'goods_received' THEN m.quantity_delta ELSE 0 END), 0) AS received,
            COALESCE(SUM(CASE WHEN m.reason_code = 'sale' THEN -m.quantity_delta ELSE 0 END), 0) AS sold,
            COALESCE(SUM(CASE WHEN m.reason_code IN ('adjustment', 'disbursement', 'stocktake', 'transfer_out', 'transfer_in')
                              THEN m.quantity_delta ELSE 0 END), 0) AS adjusted
       FROM products p
       LEFT JOIN stock_movements m ON m.product_id = p.product_id ${where ? `AND ${where.replace(/\boccurred_at\b/g, 'm.occurred_at')}` : ''}
      GROUP BY p.product_id, p.sku, p.name
      ORDER BY p.name`,
    ...params,
  );

  return rows.map((row) => {
    const onHand = get<{ quantity: number }>(db, 'SELECT quantity FROM stock_on_hand WHERE product_id = ?', row.product_id)
      ?.quantity ?? 0;
    return {
      productId: row.product_id,
      sku: row.sku,
      name: row.name,
      received: row.received,
      sold: row.sold,
      adjusted: row.adjusted,
      onHand,
      balances: row.received - row.sold + row.adjusted === onHand,
    };
  });
}

export interface DiscrepancyRow {
  saleId: string;
  receiptNumber: string;
  deviceId: string;
  clientTotal: number;
  serverTotal: number | null;
}

/** Sales the server priced differently from the device, for finance review. */
export function priceDiscrepancies(db: Db): DiscrepancyRow[] {
  return all<{ sale_id: string; receipt_number: string; device_id: string; total: number; server_total: number | null }>(
    db,
    `SELECT sale_id, receipt_number, device_id, total, server_total
       FROM sales WHERE price_discrepancy = 1 OR sync_state = 'disputed'
      ORDER BY created_at DESC`,
  ).map((row) => ({
    saleId: row.sale_id,
    receiptNumber: row.receipt_number,
    deviceId: row.device_id,
    clientTotal: row.total,
    serverTotal: row.server_total,
  }));
}

/** Movements that pushed stock negative and need a human to confirm. */
export function stockReviewQueue(db: Db): Array<{ movementId: string; productId: string; delta: number; reason: string; occurredAt: string }> {
  return all<{ movement_id: string; product_id: string; quantity_delta: number; reason_code: string; occurred_at: string }>(
    db,
    `SELECT movement_id, product_id, quantity_delta, reason_code, occurred_at
       FROM stock_movements WHERE requires_review = 1 ORDER BY occurred_at DESC`,
  ).map((row) => ({
    movementId: row.movement_id,
    productId: row.product_id,
    delta: row.quantity_delta,
    reason: row.reason_code,
    occurredAt: row.occurred_at,
  }));
}
