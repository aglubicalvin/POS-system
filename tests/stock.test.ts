import { beforeEach, describe, expect, it } from 'vitest';
import type { Db } from '../src/db/db.ts';
import { onHand, productsNeedingReview, rebuildStockOnHand, recordMovement, verifyStockCache } from '../src/domain/stock.ts';
import { seedStore, type SeededStore } from './helpers.ts';

describe('stock ledger', () => {
  let store: SeededStore;
  let db: Db;

  beforeEach(() => {
    store = seedStore();
    db = store.db;
  });

  it('keeps stock_on_hand in step with movements', () => {
    const productId = store.products['WATER-500']!.product_id;
    recordMovement(db, { deviceId: store.deviceId, productId, quantityDelta: 50, reasonCode: 'goods_received', userId: store.manager.user_id });
    recordMovement(db, { deviceId: store.deviceId, productId, quantityDelta: -12, reasonCode: 'sale', userId: store.cashier.user_id });
    expect(onHand(db, productId)).toBe(38);
    expect(verifyStockCache(db).ok).toBe(true);
  });

  it('rebuilds the cache from the ledger when it has drifted', () => {
    const productId = store.products['SOAP-BAR']!.product_id;
    recordMovement(db, { deviceId: store.deviceId, productId, quantityDelta: 20, reasonCode: 'goods_received', userId: store.manager.user_id });

    // Simulate corruption of the cache.
    db.prepare('UPDATE stock_on_hand SET quantity = 999 WHERE product_id = ?').run(productId);
    expect(onHand(db, productId)).toBe(999);
    expect(verifyStockCache(db).ok).toBe(false);

    rebuildStockOnHand(db);
    expect(onHand(db, productId)).toBe(20);
    expect(verifyStockCache(db).ok).toBe(true);
  });

  it('allows negative stock and flags it only when asked', () => {
    const productId = store.products['MILO-400']!.product_id;

    const unflagged = recordMovement(db, {
      deviceId: store.deviceId,
      productId,
      quantityDelta: -3,
      reasonCode: 'sale',
      userId: store.cashier.user_id,
    });
    expect(unflagged.requires_review).toBe(0);
    expect(onHand(db, productId)).toBe(-3);

    const flagged = recordMovement(db, {
      deviceId: store.deviceId,
      productId,
      quantityDelta: -2,
      reasonCode: 'sale',
      userId: store.cashier.user_id,
      reviewIfNegative: true,
    });
    expect(flagged.requires_review).toBe(1);
    expect(productsNeedingReview(db)).toHaveLength(1);
  });

  it('rejects a zero or fractional movement', () => {
    const productId = store.products['WATER-500']!.product_id;
    expect(() =>
      recordMovement(db, { deviceId: store.deviceId, productId, quantityDelta: 0, reasonCode: 'adjustment', userId: store.manager.user_id }),
    ).toThrow();
    expect(() =>
      recordMovement(db, { deviceId: store.deviceId, productId, quantityDelta: 1.5, reasonCode: 'adjustment', userId: store.manager.user_id }),
    ).toThrow();
  });
});
