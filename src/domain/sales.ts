import { get, nowIso, run, transaction, type Db } from '../db/db.ts';
import { appendAudit } from './audit.ts';
import { getSale, listSaleItems, type SaleItemRow, type SaleRow } from './checkout.ts';
import { newUuid } from './ids.ts';
import { sum, type Minor } from './money.ts';
import { enqueue } from './outbox.ts';
import { can, PermissionError, type Role } from './permissions.ts';
import { nextReceiptNumber } from './receipt-number.ts';
import { recordMovement } from './stock.ts';
import { requireUser } from './users.ts';

/**
 * Sales are immutable. A void or a refund never edits or deletes the original:
 * it writes a NEW sale row of kind 'void'/'refund' that references the original
 * and reverses stock with its own movements.
 */

export class SaleError extends Error {
  override name = 'SaleError';
}

export function voidSale(
  db: Db,
  input: { deviceId: string; saleId: string; userId: string; reason: string; at?: string },
): SaleRow {
  if (!input.reason.trim()) throw new SaleError('a void requires a reason');
  return transaction(db, () => {
    const user = requireUser(db, input.userId);
    const role: Role = user.role;
    if (!can(role, 'sale.void')) throw new PermissionError(`role '${role}' cannot void sales`);

    const original = getSale(db, input.saleId);
    if (!original) throw new SaleError(`sale ${input.saleId} not found`);
    if (original.status !== 'completed') throw new SaleError(`sale ${original.receipt_number} is ${original.status}`);
    if (original.kind !== 'sale') throw new SaleError('only a completed sale can be voided');

    const at = input.at ?? nowIso();
    const voidId = newUuid();
    const receiptNumber = nextReceiptNumber(db, input.deviceId, at);

    run(
      db,
      `INSERT INTO sales
         (sale_id, device_id, receipt_number, shift_id, cashier_id, customer_ref, currency,
          subtotal, discount_total, tax_total, total, kind, original_sale_id, status,
          voided_by, voided_reason, server_total, price_discrepancy, sync_state, created_at)
       VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, 'void', ?, 'completed', NULL, NULL, NULL, 0, 'pending', ?)`,
      voidId,
      input.deviceId,
      receiptNumber,
      original.shift_id,
      input.userId,
      original.currency,
      original.subtotal,
      original.discount_total,
      original.tax_total,
      original.total,
      original.sale_id,
      at,
    );

    for (const item of listSaleItems(db, original.sale_id)) {
      copyItem(db, voidId, item);
      recordMovement(db, {
        deviceId: input.deviceId,
        productId: item.product_id,
        quantityDelta: item.quantity,
        reasonCode: 'void',
        refType: 'sale',
        refId: voidId,
        userId: input.userId,
        occurredAt: at,
      });
    }

    // Controlled status transition on the original; its financial fields are untouched.
    run(
      db,
      `UPDATE sales SET status = 'voided', voided_by = ?, voided_reason = ? WHERE sale_id = ?`,
      input.userId,
      input.reason,
      original.sale_id,
    );

    appendAudit(db, {
      deviceId: input.deviceId,
      actorId: input.userId,
      action: 'sale.voided',
      entityType: 'sale',
      entityId: voidId,
      occurredAt: at,
      details: { originalSaleId: original.sale_id, originalReceipt: original.receipt_number, reason: input.reason, total: original.total },
    });

    enqueue(db, {
      deviceId: input.deviceId,
      eventType: 'sale.voided',
      aggregateType: 'sale',
      aggregateId: voidId,
      createdAt: at,
      payload: {
        voidSaleId: voidId,
        originalSaleId: original.sale_id,
        receiptNumber,
        userId: input.userId,
        reason: input.reason,
        createdAt: at,
        total: original.total,
      },
    });

    return getSale(db, voidId)!;
  });
}

export interface RefundLine {
  itemId: string;
  quantity: number;
}

export interface RefundResult {
  refund: SaleRow;
  refundedTotal: Minor;
}

/**
 * Refund some or all of a sale. Amounts are pro-rated from the original item so
 * the refund can never exceed what was actually charged.
 */
export function refundSale(
  db: Db,
  input: { deviceId: string; saleId: string; userId: string; reason: string; lines: RefundLine[]; at?: string },
): RefundResult {
  if (!input.reason.trim()) throw new SaleError('a refund requires a reason');
  if (input.lines.length === 0) throw new SaleError('a refund must reference at least one item');

  return transaction(db, () => {
    const user = requireUser(db, input.userId);
    const role: Role = user.role;
    if (!can(role, 'sale.refund')) throw new PermissionError(`role '${role}' cannot issue refunds`);

    const original = getSale(db, input.saleId);
    if (!original) throw new SaleError(`sale ${input.saleId} not found`);
    if (original.kind !== 'sale') throw new SaleError('only a normal sale can be refunded');

    const originalItems = listSaleItems(db, original.sale_id);
    const byId = new Map(originalItems.map((i) => [i.item_id, i]));
    const at = input.at ?? nowIso();
    const refundId = newUuid();
    const receiptNumber = nextReceiptNumber(db, input.deviceId, at);

    const planned = input.lines.map((line) => {
      const item = byId.get(line.itemId);
      if (!item) throw new SaleError(`item ${line.itemId} is not part of sale ${original.receipt_number}`);
      if (!Number.isInteger(line.quantity) || line.quantity <= 0) {
        throw new SaleError(`refund quantity for ${item.sku} must be a positive integer`);
      }
      if (line.quantity > item.quantity) {
        throw new SaleError(`cannot refund ${line.quantity} of ${item.sku}; only ${item.quantity} were sold`);
      }
      const ratio = line.quantity / item.quantity;
      const gross = item.unit_price * line.quantity;
      const discount = Math.round(item.discount * ratio);
      const tax = Math.round(item.tax * ratio);
      return { item, quantity: line.quantity, discount, tax, lineTotal: gross - discount + tax };
    });

    const refundedTotal = sum(planned.map((p) => p.lineTotal));
    const subtotal = sum(planned.map((p) => p.item.unit_price * p.quantity - p.discount));
    const discountTotal = sum(planned.map((p) => p.discount));
    const taxTotal = sum(planned.map((p) => p.tax));

    run(
      db,
      `INSERT INTO sales
         (sale_id, device_id, receipt_number, shift_id, cashier_id, customer_ref, currency,
          subtotal, discount_total, tax_total, total, kind, original_sale_id, status,
          voided_by, voided_reason, server_total, price_discrepancy, sync_state, created_at)
       VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, 'refund', ?, 'completed', NULL, NULL, NULL, 0, 'pending', ?)`,
      refundId,
      input.deviceId,
      receiptNumber,
      original.shift_id,
      input.userId,
      original.currency,
      subtotal,
      discountTotal,
      taxTotal,
      refundedTotal,
      original.sale_id,
      at,
    );

    for (const p of planned) {
      run(
        db,
        `INSERT INTO sale_items
           (item_id, sale_id, product_id, sku, name, quantity, unit_price, discount,
            tax_rate_bp, tax, line_total, price_source, override_by)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)`,
        newUuid(),
        refundId,
        p.item.product_id,
        p.item.sku,
        p.item.name,
        p.quantity,
        p.item.unit_price,
        p.discount,
        p.item.tax_rate_bp,
        p.tax,
        p.lineTotal,
        p.item.price_source,
      );
      recordMovement(db, {
        deviceId: input.deviceId,
        productId: p.item.product_id,
        quantityDelta: p.quantity,
        reasonCode: 'refund',
        refType: 'sale',
        refId: refundId,
        userId: input.userId,
        occurredAt: at,
      });
    }

    run(db, `UPDATE sales SET status = 'refunded' WHERE sale_id = ?`, original.sale_id);

    appendAudit(db, {
      deviceId: input.deviceId,
      actorId: input.userId,
      action: 'sale.refunded',
      entityType: 'sale',
      entityId: refundId,
      occurredAt: at,
      details: {
        originalSaleId: original.sale_id,
        originalReceipt: original.receipt_number,
        reason: input.reason,
        refundedTotal,
        lines: planned.length,
      },
    });

    enqueue(db, {
      deviceId: input.deviceId,
      eventType: 'sale.refunded',
      aggregateType: 'sale',
      aggregateId: refundId,
      createdAt: at,
      payload: {
        refundSaleId: refundId,
        originalSaleId: original.sale_id,
        receiptNumber,
        userId: input.userId,
        reason: input.reason,
        createdAt: at,
        refundedTotal,
        lines: planned.map((p) => ({ productId: p.item.product_id, quantity: p.quantity, lineTotal: p.lineTotal })),
      },
    });

    const refund = getSale(db, refundId)!;
    return { refund, refundedTotal };
  });
}

function copyItem(db: Db, saleId: string, item: SaleItemRow): void {
  run(
    db,
    `INSERT INTO sale_items
       (item_id, sale_id, product_id, sku, name, quantity, unit_price, discount,
        tax_rate_bp, tax, line_total, price_source, override_by)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    newUuid(),
    saleId,
    item.product_id,
    item.sku,
    item.name,
    item.quantity,
    item.unit_price,
    item.discount,
    item.tax_rate_bp,
    item.tax,
    item.line_total,
    item.price_source,
    item.override_by,
  );
}

export { getSale, listSaleItems };
