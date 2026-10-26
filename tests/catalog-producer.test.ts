import { beforeEach, describe, expect, it } from 'vitest';
import type { Db } from '../src/db/db.ts';
import { createUser } from '../src/domain/users.ts';
import { createProduct, updateProduct } from '../src/domain/products.ts';
import { drainUntilEmpty } from '../src/sync/client.ts';
import { recordingTransport, seedStore, type SeededStore } from './helpers.ts';

/**
 * The producer half of catalog/user sync.
 *
 * What a RECEIVING device does with these events is asserted in
 * `sync.test.ts` (pull path) and the Kotlin applier suite; what the SERVER does
 * is asserted in Kotlin. This file covers only the device that ENQUEUES them.
 */
describe('catalog and user event producers', () => {
  let store: SeededStore;
  let db: Db;

  beforeEach(() => {
    store = seedStore();
    db = store.db;
  });

  function outboxRows(): Array<Record<string, unknown>> {
    return db.prepare('SELECT * FROM outbox ORDER BY seq').all() as unknown as Array<Record<string, unknown>>;
  }

  it('createProduct emits product.upserted with the full row', () => {
    const row = createProduct(db, {
      deviceId: store.deviceId,
      sku: 'TILLEY-TP1',
      name: 'Toilet Paper Roll',
      unitPrice: 250,
      costPrice: 140,
      taxRateBp: 1250,
      lowStockThreshold: 10,
    });
    // exactly one event: the seeding baseline was cleared by seedStore
    const rows = outboxRows();
    expect(rows).toHaveLength(1);
    const event = rows[0]!;
    expect(event.event_type).toBe('product.upserted');
    expect(event.aggregate_type).toBe('product');
    expect(event.aggregate_id).toBe(row.product_id);
    expect(event.status).toBe('pending');
    const payload = JSON.parse(event.payload as string);
    expect(payload).toEqual(row);
    // the payload is the same shape the pull relay sends: full row
    expect(Object.keys(payload)).toContain('unit_price');
  });

  it('updateProduct emits a second event carrying the edited row', () => {
    const row = createProduct(db, {
      deviceId: store.deviceId,
      sku: 'SIM-CARD',
      name: 'MTN SIM Card',
      unitPrice: 200,
    });
    updateProduct(db, { deviceId: store.deviceId, productId: row.product_id, unitPrice: 350, name: 'MTN SIM Card XL' });

    const rows = outboxRows();
    expect(rows).toHaveLength(2);
    expect(rows.map((r) => r.aggregate_id)).toEqual([row.product_id, row.product_id]);

    // The two edits have distinct identities: the idempotency key is per-edit,
    // so the second edit cannot collapse into the first.
    expect(rows[0]!.idempotency_key).not.toBe(rows[1]!.idempotency_key);
    const second = JSON.parse(rows[1]!.payload as string);
    expect(second).toMatchObject({ unit_price: 350, name: 'MTN SIM Card XL' });

    // And the seqs stay ordered oldest-first for the drain.
    expect(rows.map((r) => Number(r.seq))).toEqual([1, 2]);
  });

  it('a failed edit writes no row and no event', () => {
    const row = createProduct(db, { deviceId: store.deviceId, sku: 'INK-70', name: 'Ink 70ml', unitPrice: 900 });
    const before = db.prepare('SELECT COUNT(*) AS n FROM outbox').get() as { n: number };

    expect(() => updateProduct(db, { deviceId: store.deviceId, productId: row.product_id, unitPrice: -5 })).toThrow();
    expect(() =>
      updateProduct(db, { deviceId: store.deviceId, productId: 'no-such-product', name: 'Ghost' }),
    ).toThrow();

    const after = db.prepare('SELECT COUNT(*) AS n FROM outbox').get() as { n: number };
    expect(after).toEqual(before);
    expect(
      db.prepare('SELECT unit_price FROM products WHERE product_id = ?').get(row.product_id),
    ).toMatchObject({ unit_price: 900 });
  });

  it('createUser emits user.upserted with hashes and no device binding', () => {
    const user = createUser(db, {
      deviceId: store.deviceId,
      fullName: 'Ama Serwaa',
      role: 'cashier',
      pin: '9876',
    });    const rows = outboxRows();
    expect(rows).toHaveLength(1);
    expect(rows[0]!.event_type).toBe('user.upserted');
    expect(rows[0]!.aggregate_type).toBe('user');

    const payload = JSON.parse(rows[0]!.payload as string);
    // Store-global on the wire: the receiving till must not learn this device.
    expect(payload.device_id).toBeNull();
    // Secrets travel only as hashes.
    expect(payload.pin_hash).toMatch(/^(scrypt\$|\$argon2)/);
    expect(JSON.stringify(payload)).not.toContain('9876');
    // The enrolling device keeps the binding locally.
    expect(user.device_id).toBe(store.deviceId);
  });

  it('drains produced events to the server in seq order', async () => {
    createProduct(db, { deviceId: store.deviceId, sku: 'LID-1', name: 'Lid', unitPrice: 50 });
    createUser(db, {
      emittedByDevice: store.deviceId,
      fullName: 'Backup Manager',
      role: 'manager',
      password: 'secret-1',
    });

    const recorder = recordingTransport();
    const result = await drainUntilEmpty(db, store.deviceId, recorder.transport);

    expect(result.failed).toBe(0);
    expect(recorder.received.map((e) => e.eventType)).toEqual(['product.upserted', 'user.upserted']);
  });
});
