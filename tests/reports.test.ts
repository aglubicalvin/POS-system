import { beforeEach, describe, expect, it } from 'vitest';
import type { Db } from '../src/db/db.ts';
import { checkout } from '../src/domain/checkout.ts';
import { refundSale } from '../src/domain/sales.ts';
import { recordMovement } from '../src/domain/stock.ts';
import { dailySales, lowStockAlerts, receivedVsSold, stockLevels } from '../src/reports/reports.ts';
import { seedStore, type SeededStore } from './helpers.ts';

describe('reports', () => {
  let store: SeededStore;
  let db: Db;
  const today = new Date().toISOString().slice(0, 10);

  beforeEach(() => {
    store = seedStore();
    db = store.db;
  });

  it('reports daily sales and nets off refunds', () => {
    const water = store.products['WATER-500']!.product_id;
    const { sale, items } = checkout(db, {
      deviceId: store.deviceId,
      cashierId: store.cashier.user_id,
      lines: [{ productId: water, quantity: 10 }],
    });
    expect(sale.total).toBe(1500);

    refundSale(db, {
      deviceId: store.deviceId,
      saleId: sale.sale_id,
      userId: store.manager.user_id,
      reason: 'partial return',
      lines: [{ itemId: items[0]!.item_id, quantity: 2 }],
    });

    const [day] = dailySales(db, today, today);
    expect(day).toBeDefined();
    expect(day!.salesCount).toBe(1);
    expect(day!.grossTotal).toBe(1500 - 300);
    expect(day!.netTotal).toBe(1200);
  });

  it('reports stock levels and raises low-stock alerts', () => {
    recordMovement(db, {
      deviceId: store.deviceId,
      productId: store.products['WATER-500']!.product_id,
      quantityDelta: 5,
      reasonCode: 'goods_received',
      userId: store.manager.user_id,
    });

    const levels = stockLevels(db);
    const water = levels.find((l) => l.sku === 'WATER-500')!;
    expect(water.onHand).toBe(5);
    expect(water.threshold).toBe(20);
    expect(water.low).toBe(true);

    const alerts = lowStockAlerts(db);
    expect(alerts.map((a) => a.sku)).toContain('WATER-500');
    // Milo was never stocked, and its threshold is 3, so 0 <= 3 is also low.
    expect(alerts.map((a) => a.sku)).toContain('MILO-400');
  });

  it('reconciles received against sold and detects shrink', () => {
    const water = store.products['WATER-500']!.product_id;
    recordMovement(db, {
      deviceId: store.deviceId,
      productId: water,
      quantityDelta: 100,
      reasonCode: 'goods_received',
      userId: store.manager.user_id,
    });
    checkout(db, {
      deviceId: store.deviceId,
      cashierId: store.cashier.user_id,
      lines: [{ productId: water, quantity: 12 }],
    });

    const row = receivedVsSold(db).find((r) => r.sku === 'WATER-500')!;
    expect(row.received).toBe(100);
    expect(row.sold).toBe(12);
    expect(row.onHand).toBe(88);
    expect(row.balances).toBe(true);

    // Introduce unexplained shrink: reduce the cache without a movement.
    db.prepare('UPDATE stock_on_hand SET quantity = 80 WHERE product_id = ?').run(water);
    const shrunk = receivedVsSold(db).find((r) => r.sku === 'WATER-500')!;
    expect(shrunk.balances).toBe(false);
  });
});
