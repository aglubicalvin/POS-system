import { beforeEach, describe, expect, it } from 'vitest';
import type { Db } from '../src/db/db.ts';
import { createApproval } from '../src/domain/approvals.ts';
import { checkout, getSale, listSaleItems } from '../src/domain/checkout.ts';
import { outboxStats } from '../src/domain/outbox.ts';
import { onHand, recordMovement } from '../src/domain/stock.ts';
import { refundSale, voidSale } from '../src/domain/sales.ts';
import { PermissionError } from '../src/domain/permissions.ts';
import { seedStore, type SeededStore } from './helpers.ts';

function stockIn(store: SeededStore, sku: string, quantity: number): void {
  recordMovement(store.db, {
    deviceId: store.deviceId,
    productId: store.products[sku]!.product_id,
    quantityDelta: quantity,
    reasonCode: 'goods_received',
    userId: store.manager.user_id,
  });
}

describe('checkout', () => {
  let store: SeededStore;
  let db: Db;

  beforeEach(() => {
    store = seedStore();
    db = store.db;
    stockIn(store, 'WATER-500', 100);
    stockIn(store, 'SOAP-BAR', 40);
    stockIn(store, 'MILO-400', 10);
  });

  it('writes sale, items, stock movements, receipt number and outbox event in one go', () => {
    const { sale, items, receiptNumber } = checkout(db, {
      deviceId: store.deviceId,
      cashierId: store.cashier.user_id,
      lines: [
        { productId: store.products['WATER-500']!.product_id, quantity: 2 },
        { productId: store.products['MILO-400']!.product_id, quantity: 1 },
      ],
    });

    expect(receiptNumber).toMatch(/^[0-9A-F]{8}-\d{8}-0001$/);
    expect(sale.subtotal).toBe(4800); // 300 + 4500
    expect(sale.tax_total).toBe(563); // 12.5% of 4500, rounded half-up
    expect(sale.total).toBe(5363);
    expect(items).toHaveLength(2);

    expect(onHand(db, store.products['WATER-500']!.product_id)).toBe(98);
    expect(onHand(db, store.products['MILO-400']!.product_id)).toBe(9);

    const stats = outboxStats(db, store.deviceId);
    expect(stats.pending).toBe(1);
  });

  it('issues gapless per-device receipt numbers', () => {
    const first = checkout(db, {
      deviceId: store.deviceId,
      cashierId: store.cashier.user_id,
      lines: [{ productId: store.products['WATER-500']!.product_id, quantity: 1 }],
    });
    const second = checkout(db, {
      deviceId: store.deviceId,
      cashierId: store.cashier.user_id,
      lines: [{ productId: store.products['WATER-500']!.product_id, quantity: 1 }],
    });
    expect(first.receiptNumber.endsWith('-0001')).toBe(true);
    expect(second.receiptNumber.endsWith('-0002')).toBe(true);
  });

  it('writes nothing at all when one line is invalid', () => {
    expect(() =>
      checkout(db, {
        deviceId: store.deviceId,
        cashierId: store.cashier.user_id,
        lines: [
          { productId: store.products['WATER-500']!.product_id, quantity: 1 },
          { productId: 'does-not-exist', quantity: 1 },
        ],
      }),
    ).toThrow();

    expect(db.prepare('SELECT COUNT(*) AS n FROM sales').get()).toMatchObject({ n: 0 });
    expect(db.prepare('SELECT COUNT(*) AS n FROM sale_items').get()).toMatchObject({ n: 0 });
    expect(db.prepare('SELECT COUNT(*) AS n FROM stock_movements WHERE reason_code = ?').get('sale')).toMatchObject({ n: 0 });
    expect(onHand(db, store.products['WATER-500']!.product_id)).toBe(100);
    expect(outboxStats(db, store.deviceId).pending).toBe(0);
  });

  it('handles the same product on two lines without losing a movement', () => {
    const productId = store.products['WATER-500']!.product_id;
    const { items } = checkout(db, {
      deviceId: store.deviceId,
      cashierId: store.cashier.user_id,
      lines: [
        { productId, quantity: 3 },
        { productId, quantity: 4 },
      ],
    });
    expect(items).toHaveLength(2);
    expect(onHand(db, productId)).toBe(93);
  });

  it('lets a cashier discount within their limit but not beyond it', () => {
    const productId = store.products['SOAP-BAR']!.product_id; // 800 => gross 800

    // 10% is inside the cashier limit.
    const ok = checkout(db, {
      deviceId: store.deviceId,
      cashierId: store.cashier.user_id,
      lines: [{ productId, quantity: 1, discount: 80 }],
    });
    expect(ok.sale.discount_total).toBe(80);

    // 30% needs a manager approval.
    expect(() =>
      checkout(db, {
        deviceId: store.deviceId,
        cashierId: store.cashier.user_id,
        lines: [{ productId, quantity: 1, discount: 240 }],
      }),
    ).toThrow(PermissionError);

    const approval = createApproval(db, {
      deviceId: store.deviceId,
      requestedBy: store.cashier.user_id,
      approverId: store.manager.user_id,
      approverRole: 'manager',
      action: 'sale.discount.approval',
      reason: 'bulk purchase',
    });
    const approved = checkout(db, {
      deviceId: store.deviceId,
      cashierId: store.cashier.user_id,
      lines: [{ productId, quantity: 1, discount: 240, approvalId: approval.approval_id }],
    });
    // 800 - 240 discount = 560 net, plus 12.5% tax (70) = 630.
    expect(approved.sale.discount_total).toBe(240);
    expect(approved.sale.tax_total).toBe(70);
    expect(approved.sale.total).toBe(630);
  });

  it('blocks a cashier price override but allows a manager', () => {
    const productId = store.products['MILO-400']!.product_id;

    expect(() =>
      checkout(db, {
        deviceId: store.deviceId,
        cashierId: store.cashier.user_id,
        lines: [{ productId, quantity: 1, overrideUnitPrice: 4000 }],
      }),
    ).toThrow(PermissionError);

    const { sale, items } = checkout(db, {
      deviceId: store.deviceId,
      cashierId: store.manager.user_id,
      lines: [{ productId, quantity: 1, overrideUnitPrice: 4000 }],
    });
    expect(items[0]!.price_source).toBe('override');
    expect(sale.subtotal).toBe(4000);
  });

  it('voids a sale by writing a new record and restoring stock', () => {
    const { sale } = checkout(db, {
      deviceId: store.deviceId,
      cashierId: store.cashier.user_id,
      lines: [{ productId: store.products['WATER-500']!.product_id, quantity: 5 }],
    });
    expect(onHand(db, store.products['WATER-500']!.product_id)).toBe(95);

    const voidRecord = voidSale(db, {
      deviceId: store.deviceId,
      saleId: sale.sale_id,
      userId: store.manager.user_id,
      reason: 'cashier mis-keyed the quantity',
    });

    expect(voidRecord.sale_id).not.toBe(sale.sale_id);
    expect(voidRecord.kind).toBe('void');
    expect(voidRecord.original_sale_id).toBe(sale.sale_id);
    expect(onHand(db, store.products['WATER-500']!.product_id)).toBe(100);

    const original = getSale(db, sale.sale_id)!;
    expect(original.status).toBe('voided');
    // Immutable: the original financial figures are untouched.
    expect(original.total).toBe(sale.total);
    expect(original.receipt_number).toBe(sale.receipt_number);
  });

  it('refuses a void from a cashier', () => {
    const { sale } = checkout(db, {
      deviceId: store.deviceId,
      cashierId: store.cashier.user_id,
      lines: [{ productId: store.products['WATER-500']!.product_id, quantity: 1 }],
    });
    expect(() =>
      voidSale(db, { deviceId: store.deviceId, saleId: sale.sale_id, userId: store.cashier.user_id, reason: 'oops' }),
    ).toThrow(PermissionError);
  });

  it('refunds part of a sale without touching the original lines', () => {
    const { sale, items } = checkout(db, {
      deviceId: store.deviceId,
      cashierId: store.cashier.user_id,
      lines: [{ productId: store.products['WATER-500']!.product_id, quantity: 4 }],
    });
    const beforeItems = listSaleItems(db, sale.sale_id);

    const { refund, refundedTotal } = refundSale(db, {
      deviceId: store.deviceId,
      saleId: sale.sale_id,
      userId: store.manager.user_id,
      reason: 'two sachets were leaking',
      lines: [{ itemId: items[0]!.item_id, quantity: 2 }],
    });

    expect(refund.kind).toBe('refund');
    expect(refundedTotal).toBe(300);
    expect(onHand(db, store.products['WATER-500']!.product_id)).toBe(98);
    expect(listSaleItems(db, sale.sale_id)).toEqual(beforeItems);
  });

  it('refuses to refund more than was sold', () => {
    const { sale, items } = checkout(db, {
      deviceId: store.deviceId,
      cashierId: store.cashier.user_id,
      lines: [{ productId: store.products['WATER-500']!.product_id, quantity: 1 }],
    });
    expect(() =>
      refundSale(db, {
        deviceId: store.deviceId,
        saleId: sale.sale_id,
        userId: store.manager.user_id,
        reason: 'too many',
        lines: [{ itemId: items[0]!.item_id, quantity: 2 }],
      }),
    ).toThrow();
  });
});
