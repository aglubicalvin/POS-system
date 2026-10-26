import { beforeEach, describe, expect, it } from 'vitest';
import type { Db } from '../src/db/db.ts';
import { createApproval } from '../src/domain/approvals.ts';
import { disburseStock, receiveGoods, recordManualMovement } from '../src/domain/inventory.ts';
import { PermissionError } from '../src/domain/permissions.ts';
import { onHand } from '../src/domain/stock.ts';
import { seedStore, type SeededStore } from './helpers.ts';

describe('inventory', () => {
  let store: SeededStore;
  let db: Db;

  beforeEach(() => {
    store = seedStore();
    db = store.db;
  });

  it('records a delivery with its supplier, costs and inbound movements', () => {
    const { totalCost } = receiveGoods(db, {
      deviceId: store.deviceId,
      supplierId: store.supplier.supplier_id,
      receivedBy: store.manager.user_id,
      reference: 'INV-2291',
      lines: [
        { productId: store.products['WATER-500']!.product_id, quantity: 100, unitCost: 90 },
        { productId: store.products['SOAP-BAR']!.product_id, quantity: 24, unitCost: 500 },
      ],
    });

    expect(totalCost).toBe(100 * 90 + 24 * 500);
    expect(onHand(db, store.products['WATER-500']!.product_id)).toBe(100);
    expect(onHand(db, store.products['SOAP-BAR']!.product_id)).toBe(24);

    const grn = db.prepare('SELECT supplier_id, reference, total_cost, status FROM goods_received').get() as {
      supplier_id: string;
      reference: string;
      total_cost: number;
      status: string;
    };
    expect(grn.supplier_id).toBe(store.supplier.supplier_id);
    expect(grn.reference).toBe('INV-2291');
    expect(grn.status).toBe('received');
  });

  it('requires a written reason for a manual movement', () => {
    const { productId } = { productId: store.products['WATER-500']!.product_id };
    expect(() =>
      recordManualMovement(db, {
        deviceId: store.deviceId,
        productId,
        quantityDelta: -3,
        userId: store.manager.user_id,
        reasonCode: 'disbursement',
        reason: '   ',
      }),
    ).toThrow(/written reason/);
  });

  it('rejects a reason code that is not a manual code', () => {
    expect(() =>
      recordManualMovement(db, {
        deviceId: store.deviceId,
        productId: store.products['WATER-500']!.product_id,
        quantityDelta: -1,
        userId: store.manager.user_id,
        reasonCode: 'sale',
        reason: 'sneaking a sale through as an adjustment',
      }),
    ).toThrow(/not a manual movement code/);
  });

  it('blocks a cashier from adjusting stock at all', () => {
    expect(() =>
      recordManualMovement(db, {
        deviceId: store.deviceId,
        productId: store.products['WATER-500']!.product_id,
        quantityDelta: -1,
        userId: store.cashier.user_id,
        reasonCode: 'disbursement',
        reason: 'gave one to a friend',
      }),
    ).toThrow(PermissionError);
  });

  it('lets a manager make a small adjustment without approval', () => {
    const result = recordManualMovement(db, {
      deviceId: store.deviceId,
      productId: store.products['WATER-500']!.product_id,
      quantityDelta: -5,
      userId: store.manager.user_id,
      reasonCode: 'disbursement',
      reason: 'staff refreshments',
    });
    expect(result.resultingQty).toBe(-5);
  });

  it('requires an approval for a large adjustment', () => {
    const productId = store.products['WATER-500']!.product_id;

    expect(() =>
      recordManualMovement(db, {
        deviceId: store.deviceId,
        productId,
        quantityDelta: -50,
        userId: store.manager.user_id,
        reasonCode: 'adjustment',
        reason: 'stocktake correction',
      }),
    ).toThrow(PermissionError);

    const approval = createApproval(db, {
      deviceId: store.deviceId,
      requestedBy: store.manager.user_id,
      approverId: store.admin.user_id,
      approverRole: 'admin',
      action: 'stock.adjust.large',
      reason: 'stocktake correction signed off',
    });

    const result = recordManualMovement(db, {
      deviceId: store.deviceId,
      productId,
      quantityDelta: -50,
      userId: store.manager.user_id,
      reasonCode: 'adjustment',
      reason: 'stocktake correction',
      approvalId: approval.approval_id,
    });
    expect(result.resultingQty).toBe(-50);
  });

  it('records a disbursement as a negative movement plus an audit entry', () => {
    receiveGoods(db, {
      deviceId: store.deviceId,
      supplierId: store.supplier.supplier_id,
      receivedBy: store.manager.user_id,
      lines: [{ productId: store.products['MILO-400']!.product_id, quantity: 12, unitCost: 3000 }],
    });

    const { resultingQty } = disburseStock(db, {
      deviceId: store.deviceId,
      productId: store.products['MILO-400']!.product_id,
      quantity: 2,
      userId: store.manager.user_id,
      reason: 'damaged in transit',
    });

    expect(resultingQty).toBe(10);
    const audit = db
      .prepare(`SELECT action, details FROM audit_log WHERE action = 'stock.disbursement'`)
      .get() as { action: string; details: string };
    expect(audit.action).toBe('stock.disbursement');
    expect(JSON.parse(audit.details).reason).toBe('damaged in transit');
  });
});
