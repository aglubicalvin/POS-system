import { beforeEach, describe, expect, it } from 'vitest';
import type { Db } from '../src/db/db.ts';
import { checkout } from '../src/domain/checkout.ts';
import { confirmPayment, createPayment } from '../src/domain/payments.ts';
import { PermissionError } from '../src/domain/permissions.ts';
import { closeShift, expectedCash, openShift } from '../src/domain/shifts.ts';
import { seedStore, type SeededStore } from './helpers.ts';

describe('shifts', () => {
  let store: SeededStore;
  let db: Db;

  beforeEach(() => {
    store = seedStore();
    db = store.db;
  });

  function sellWithCash(shiftId: string, quantity: number): number {
    const { sale } = checkout(db, {
      deviceId: store.deviceId,
      cashierId: store.cashier.user_id,
      shiftId,
      lines: [{ productId: store.products['WATER-500']!.product_id, quantity }],
    });
    const payment = createPayment(db, { saleId: sale.sale_id, method: 'cash', amount: sale.total });
    confirmPayment(db, payment.payment_id);
    return sale.total;
  }

  it('computes expected cash from the float plus confirmed cash takings', () => {
    const shift = openShift(db, { deviceId: store.deviceId, userId: store.cashier.user_id, openingFloat: 10000 });
    const takings = sellWithCash(shift.shift_id, 3);
    expect(takings).toBe(450);
    expect(expectedCash(db, shift.shift_id)).toBe(10450);
  });

  it('closes with zero variance when the drawer matches', () => {
    const shift = openShift(db, { deviceId: store.deviceId, userId: store.cashier.user_id, openingFloat: 10000 });
    sellWithCash(shift.shift_id, 3);

    const result = closeShift(db, {
      shiftId: shift.shift_id,
      userId: store.cashier.user_id,
      cashCount: 10450,
    });
    expect(result.variance).toBe(0);
    expect(result.needsApproval).toBe(false);
    expect(result.shift.status).toBe('closed');
  });

  it('requires a manager to approve a material variance', () => {
    const shift = openShift(db, { deviceId: store.deviceId, userId: store.cashier.user_id, openingFloat: 10000 });
    sellWithCash(shift.shift_id, 3);

    expect(() =>
      closeShift(db, { shiftId: shift.shift_id, userId: store.cashier.user_id, cashCount: 8000 }),
    ).toThrow(PermissionError);

    const approved = closeShift(db, {
      shiftId: shift.shift_id,
      userId: store.cashier.user_id,
      cashCount: 8000,
      approverId: store.manager.user_id,
    });
    expect(approved.variance).toBe(-2450);
    expect(approved.needsApproval).toBe(true);
    expect(approved.shift.approved_by).toBe(store.manager.user_id);
  });

  it('does not allow two open shifts on one device', () => {
    openShift(db, { deviceId: store.deviceId, userId: store.cashier.user_id, openingFloat: 0 });
    expect(() => openShift(db, { deviceId: store.deviceId, userId: store.cashier.user_id, openingFloat: 0 })).toThrow();
  });
});
