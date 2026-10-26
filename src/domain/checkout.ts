import { get, nowIso, run, transaction, type Db } from '../db/db.ts';
import { appendAudit } from './audit.ts';
import { newUuid } from './ids.ts';
import { assertApproval } from './approvals.ts';
import { applyRate, priceLine, sum, type Minor } from './money.ts';
import { enqueue } from './outbox.ts';
import { can, checkDiscount, PermissionError, type Role } from './permissions.ts';
import { requireActiveProduct } from './products.ts';
import { nextReceiptNumber, type ReceiptNumber } from './receipt-number.ts';
import { recordMovement } from './stock.ts';
import { requireUser } from './users.ts';

/**
 * Checkout: the sale, its items, the stock movements, the receipt number, the
 * audit entry, and the outbox event are written in ONE transaction. Either the
 * whole sale exists and will eventually sync, or nothing happened.
 */

export interface CheckoutLineInput {
  productId: string;
  quantity: number;
  /** Explicit unit price in minor units. Requires price.override or an approval. */
  overrideUnitPrice?: number;
  /** Absolute discount in minor units, applied to the line gross. */
  discount?: number;
  /** Approval id when the discount or override needs a manager. */
  approvalId?: string;
}

export interface CheckoutInput {
  deviceId: string;
  cashierId: string;
  shiftId?: string | null;
  customerRef?: string | null;
  currency?: string;
  lines: CheckoutLineInput[];
  at?: string;
}

export interface SaleRow {
  sale_id: string;
  device_id: string;
  receipt_number: string;
  shift_id: string | null;
  cashier_id: string;
  customer_ref: string | null;
  currency: string;
  subtotal: number;
  discount_total: number;
  tax_total: number;
  total: number;
  kind: 'sale' | 'void' | 'refund';
  original_sale_id: string | null;
  status: 'completed' | 'voided' | 'refunded';
  voided_by: string | null;
  voided_reason: string | null;
  server_total: number | null;
  price_discrepancy: number;
  sync_state: 'pending' | 'synced' | 'disputed';
  created_at: string;
}

export interface SaleItemRow {
  item_id: string;
  sale_id: string;
  product_id: string;
  sku: string;
  name: string;
  quantity: number;
  unit_price: number;
  discount: number;
  tax_rate_bp: number;
  tax: number;
  line_total: number;
  price_source: 'catalog' | 'override';
  override_by: string | null;
}

export interface CheckoutResult {
  sale: SaleRow;
  items: SaleItemRow[];
  receiptNumber: ReceiptNumber;
}

export class CheckoutError extends Error {
  override name = 'CheckoutError';
}

export function checkout(db: Db, input: CheckoutInput): CheckoutResult {
  if (input.lines.length === 0) throw new CheckoutError('a sale must contain at least one line');

  return transaction(db, () => {
    const cashier = requireUser(db, input.cashierId);
    const role: Role = cashier.role;
    if (!can(role, 'sale.create')) throw new PermissionError(`role '${role}' cannot create sales`);

    if (input.shiftId) {
      const shift = get<{ status: string; device_id: string }>(
        db,
        'SELECT status, device_id FROM shifts WHERE shift_id = ?',
        input.shiftId,
      );
      if (!shift) throw new CheckoutError(`shift ${input.shiftId} not found`);
      if (shift.status !== 'open') throw new CheckoutError('cannot sell against a closed shift');
      if (shift.device_id !== input.deviceId) throw new CheckoutError('shift belongs to another device');
    }

    const at = input.at ?? nowIso();
    const saleId = newUuid();

    // Price and validate every line first so a rejected line aborts the sale
    // before any row is written.
    const priced = input.lines.map((line) => {
      const product = requireActiveProduct(db, line.productId);
      if (!Number.isInteger(line.quantity) || line.quantity <= 0) {
        throw new CheckoutError(`line for ${product.sku} has invalid quantity ${line.quantity}`);
      }

      let isOverride = false;
      let unitPrice = product.unit_price;
      const override = line.overrideUnitPrice;
      if (override !== undefined && override !== product.unit_price) {
        if (!Number.isInteger(override) || override < 0) {
          throw new CheckoutError(`override price for ${product.sku} must be a non-negative integer`);
        }
        if (!can(role, 'price.override')) {
          assertApproval(db, line.approvalId, 'price.override', input.deviceId);
        }
        isOverride = true;
        unitPrice = override;
      }

      const gross = unitPrice * line.quantity;
      const discount = line.discount ?? 0;
      const discountBp = gross === 0 ? 0 : Math.round((discount * 10000) / gross);
      const decision = checkDiscount(role, discountBp);
      if (!decision.allowed) throw new PermissionError(decision.reason ?? 'discount not allowed');
      if (decision.needsApproval) {
        assertApproval(db, line.approvalId, 'sale.discount.approval', input.deviceId);
      }

      const price = priceLine({ unitPrice, quantity: line.quantity, discount, taxRateBp: product.tax_rate_bp });
      return { product, unitPrice, discount, isOverride, price, quantity: line.quantity };
    });

    const subtotal = sum(priced.map((p) => p.price.net));
    const discountTotal = sum(priced.map((p) => p.price.discount));
    const taxTotal = sum(priced.map((p) => p.price.tax));
    const total = sum(priced.map((p) => p.price.total));

    const receiptNumber = nextReceiptNumber(db, input.deviceId, at);
    const currency = input.currency ?? 'GHS';

    run(
      db,
      `INSERT INTO sales
         (sale_id, device_id, receipt_number, shift_id, cashier_id, customer_ref, currency,
          subtotal, discount_total, tax_total, total, kind, original_sale_id, status,
          voided_by, voided_reason, server_total, price_discrepancy, sync_state, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'sale', NULL, 'completed',
               NULL, NULL, NULL, 0, 'pending', ?)`,
      saleId,
      input.deviceId,
      receiptNumber,
      input.shiftId ?? null,
      input.cashierId,
      input.customerRef ?? null,
      currency,
      subtotal,
      discountTotal,
      taxTotal,
      total,
      at,
    );

    const items: SaleItemRow[] = [];
    for (const line of priced) {
      const item: SaleItemRow = {
        item_id: newUuid(),
        sale_id: saleId,
        product_id: line.product.product_id,
        sku: line.product.sku,
        name: line.product.name,
        quantity: line.quantity,
        unit_price: line.unitPrice,
        discount: line.discount,
        tax_rate_bp: line.product.tax_rate_bp,
        tax: line.price.tax,
        line_total: line.price.total,
        price_source: line.isOverride ? 'override' : 'catalog',
        override_by: line.isOverride ? input.cashierId : null,
      };
      run(
        db,
        `INSERT INTO sale_items
           (item_id, sale_id, product_id, sku, name, quantity, unit_price, discount,
            tax_rate_bp, tax, line_total, price_source, override_by)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        item.item_id,
        item.sale_id,
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
      items.push(item);
    }

    // Stock out. A device-side sale never rejects for low stock; the flag is set
    // after sync when the server sees the reconciled picture.
    for (const line of priced) {
      recordMovement(db, {
        deviceId: input.deviceId,
        productId: line.product.product_id,
        quantityDelta: -line.quantity,
        reasonCode: 'sale',
        refType: 'sale',
        refId: saleId,
        userId: input.cashierId,
        occurredAt: at,
      });
    }

    appendAudit(db, {
      deviceId: input.deviceId,
      actorId: input.cashierId,
      action: 'sale.created',
      entityType: 'sale',
      entityId: saleId,
      occurredAt: at,
      details: { receiptNumber, total, currency, lines: items.length },
    });

    enqueue(db, {
      deviceId: input.deviceId,
      eventType: 'sale.created',
      aggregateType: 'sale',
      aggregateId: saleId,
      createdAt: at,
      payload: {
        saleId,
        receiptNumber,
        shiftId: input.shiftId ?? null,
        cashierId: input.cashierId,
        customerRef: input.customerRef ?? null,
        currency,
        createdAt: at,
        clientTotals: { subtotal, discountTotal, taxTotal, total },
        items: items.map((item) => ({
          itemId: item.item_id,
          productId: item.product_id,
          sku: item.sku,
          name: item.name,
          quantity: item.quantity,
          unitPrice: item.unit_price,
          discount: item.discount,
          taxRateBp: item.tax_rate_bp,
          tax: item.tax,
          lineTotal: item.line_total,
          priceSource: item.price_source,
        })),
      },
    });

    const sale = get<SaleRow>(db, 'SELECT * FROM sales WHERE sale_id = ?', saleId)!;
    return { sale, items, receiptNumber };
  });
}

export function getSale(db: Db, saleId: string): SaleRow | undefined {
  return get<SaleRow>(db, 'SELECT * FROM sales WHERE sale_id = ?', saleId);
}

export function getSaleByReceipt(db: Db, deviceId: string, receiptNumber: string): SaleRow | undefined {
  return get<SaleRow>(
    db,
    'SELECT * FROM sales WHERE device_id = ? AND receipt_number = ?',
    deviceId,
    receiptNumber,
  );
}

export function listSaleItems(db: Db, saleId: string): SaleItemRow[] {
  return db
    .prepare('SELECT * FROM sale_items WHERE sale_id = ? ORDER BY rowid')
    .all(saleId) as unknown as SaleItemRow[];
}

/** Notional tax on a net amount — exported for reports and receipts. */
export function taxOn(net: Minor, taxRateBp: number): Minor {
  return applyRate(net, taxRateBp);
}
