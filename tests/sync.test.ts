import { beforeEach, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { get, type Db } from '../src/db/db.ts';
import { checkout } from '../src/domain/checkout.ts';
import { registerDevice } from '../src/domain/devices.ts';
import { openShift } from '../src/domain/shifts.ts';
import { outboxStats, requeueFailed } from '../src/domain/outbox.ts';
import type { ProductRow } from '../src/domain/products.ts';
import { drainOutbox, drainUntilEmpty, toSyncEvent } from '../src/sync/client.ts';
import type { SyncEvent } from '../src/sync/client.ts';
import { pullAndApply } from '../src/sync/apply.ts';
import type { SyncEvent as InboundSyncEvent } from '../src/sync/ordering.ts';
import { STORE_ID, recordingTransport, scriptedPullTransport, seedStore, type SeededStore } from './helpers.ts';

/**
 * Client-side sync behaviour.
 *
 * What the SERVER does with these events — repricing, idempotency, negative-stock
 * flagging — is asserted in the Kotlin server's own suite against real PostgreSQL
 * (`server/src/test/kotlin/com/businessstore/pos/EventApplierTest.kt`). Duplicating
 * that here would mean two implementations of the same rules drifting apart, so
 * this file covers only the device half of the contract.
 */
describe('outbox drain (client side)', () => {
  let store: SeededStore;
  let db: Db;

  beforeEach(() => {
    store = seedStore();
    db = store.db;
  });

  function sell(quantity: number): void {
    checkout(db, {
      deviceId: store.deviceId,
      cashierId: store.cashier.user_id,
      lines: [{ productId: store.products['WATER-500']!.product_id, quantity }],
    });
  }

  function rows(): SyncEvent[] {
    const raw = db.prepare('SELECT * FROM outbox ORDER BY seq').all() as unknown as Parameters<typeof toSyncEvent>[0][];
    return raw.map(toSyncEvent);
  }

  it('uploads pending events in per-device sequence order and marks them acked', async () => {
    sell(1);
    sell(2);

    const recorder = recordingTransport();
    const result = await drainUntilEmpty(db, store.deviceId, recorder.transport);

    expect(result.failed).toBe(0);
    expect(result.acked).toBe(2);
    expect(recorder.received.map((e) => e.seq)).toEqual([1, 2]);
    expect(recorder.received.every((e) => e.eventType === 'sale.created')).toBe(true);
    expect(outboxStats(db, store.deviceId).acked).toBe(2);
    expect(outboxStats(db, store.deviceId).pending).toBe(0);
  });

  it('sends the full sale payload the server reprices from', async () => {
    sell(2);
    const recorder = recordingTransport();
    await drainUntilEmpty(db, store.deviceId, recorder.transport);

    const [event] = recorder.received;
    expect(event).toBeDefined();
    const payload = event!.payload as {
      receiptNumber: string;
      clientTotals: { total: number };
      items: Array<{ productId: string; quantity: number; unitPrice: number }>;
    };
    expect(payload.receiptNumber).toMatch(/^[0-9A-F]{8}-\d{8}-0001$/);
    expect(payload.clientTotals.total).toBe(300);
    expect(payload.items).toHaveLength(1);
    expect(payload.items[0]).toMatchObject({ quantity: 2, unitPrice: 150 });
  });

  it('marks events failed with the error when the upload throws, and re-arms them', async () => {
    sell(1);
    const failing = recordingTransport({ failWith: new Error('network unreachable') });

    const result = await drainOutbox(db, store.deviceId, failing.transport);
    expect(result.acked).toBe(0);
    expect(result.failed).toBe(1);

    const stats = outboxStats(db, store.deviceId);
    expect(stats.failed).toBe(1);
    const row = db.prepare('SELECT last_error FROM outbox').get() as { last_error: string };
    expect(row.last_error).toContain('network unreachable');

    // Failed events are not retried until explicitly re-armed.
    expect(await drainOutbox(db, store.deviceId, recordingTransport().transport)).toMatchObject({ sent: 0 });
    expect(requeueFailed(db, store.deviceId)).toBe(1);
    expect(outboxStats(db, store.deviceId).pending).toBe(1);
  });

  it('re-sends an event whose acknowledgement was lost', async () => {
    sell(1);
    const first = recordingTransport();
    await drainUntilEmpty(db, store.deviceId, first.transport);
    expect(first.received).toHaveLength(1);

    // Simulate a lost ack: the device still believes these need sending.
    db.prepare(`UPDATE outbox SET status = 'pending', sent_at = NULL, acked_at = NULL`).run();

    const second = recordingTransport();
    const redo = await drainUntilEmpty(db, store.deviceId, second.transport);

    expect(redo.sent).toBe(1);
    // Same event identity, so the server can recognise the repeat rather than
    // creating a second sale.
    expect(second.received[0]!.eventId).toBe(first.received[0]!.eventId);
    expect(second.received[0]!.seq).toBe(first.received[0]!.seq);
  });

  it('does not create events when a checkout rolls back', async () => {
    expect(() =>
      checkout(db, {
        deviceId: store.deviceId,
        cashierId: store.cashier.user_id,
        lines: [
          { productId: store.products['WATER-500']!.product_id, quantity: 1 },
          { productId: 'not-a-product', quantity: 1 },
        ],
      }),
    ).toThrow();

    expect(outboxStats(db, store.deviceId).pending).toBe(0);
    expect(rows()).toHaveLength(0);
  });

  it('enqueues shift and sale events together and drains them all', async () => {
    const shift = openShift(db, { deviceId: store.deviceId, userId: store.cashier.user_id, openingFloat: 5000 });
    checkout(db, {
      deviceId: store.deviceId,
      cashierId: store.cashier.user_id,
      shiftId: shift.shift_id,
      lines: [{ productId: store.products['WATER-500']!.product_id, quantity: 1 }],
    });

    const recorder = recordingTransport();
    await drainUntilEmpty(db, store.deviceId, recorder.transport);

    expect(recorder.received.map((e) => e.eventType)).toEqual(['shift.opened', 'sale.created']);
    expect(outboxStats(db, store.deviceId).acked).toBe(2);
  });

  it('returns immediately when there is nothing to send', async () => {
    const recorder = recordingTransport();
    const result = await drainUntilEmpty(db, store.deviceId, recorder.transport);
    expect(result).toMatchObject({ sent: 0, acked: 0, failed: 0 });
    expect(recorder.received).toHaveLength(0);
  });
});

/**
 * Device-side inbound sync: events the server relays from other tills, applied
 * to the local store strictly in per-source seq order via `applyBatchOrdered`.
 * The ordering semantics themselves (duplicates, gaps, rejection rollback) are
 * asserted against the applier directly in `sync-and-auth.test.ts`; these tests
 * cover the wired path: the pull transport seam, the resume-point bookkeeping,
 * the `product.upserted` dispatch, and what this device does with its own
 * relayed events.
 */
describe('inbound pull (server → device, via applyBatchOrdered)', () => {
  let store: SeededStore;
  let db: Db;
  let otherDeviceId: string;

  beforeEach(() => {
    store = seedStore();
    db = store.db;
    otherDeviceId = registerDevice(db, { storeId: STORE_ID, name: 'TILL-2', model: 'Android-Tablet' }).device.device_id;
  });

  function relayedEvent(
    seq: number,
    payload: Record<string, unknown>,
    eventType: string = 'product.upserted',
    deviceId: string = otherDeviceId,
  ): InboundSyncEvent {
    return {
      eventId: `in-${deviceId}-${seq}-${eventType}`,
      deviceId,
      seq,
      eventType,
      aggregateType: 'product',
      aggregateId: String((payload as { product_id?: string }).product_id ?? ''),
      payload,
      createdAt: new Date().toISOString(),
    };
  }

  /** The pull contract: the server sends a product event as a full row. */
  function productPayload(overrides: Partial<ProductRow> & { product_id: string }): Record<string, unknown> {
    return {
      device_id: otherDeviceId,
      sku: 'REMOTE-SKU',
      name: 'Remotely added product',
      unit_price: 250,
      cost_price: 150,
      tax_rate_bp: 1250,
      low_stock_threshold: 5,
      is_active: 1,
      created_at: '2026-01-15T08:00:00.000Z',
      updated_at: '2026-01-15T08:00:00.000Z',
      ...overrides,
    };
  }

  function localProduct(productId: string): ProductRow | undefined {
    return db.prepare('SELECT * FROM products WHERE product_id = ?').get(productId) as ProductRow | undefined;
  }

  it('applies relayed catalog upserts in seq order and reports the resume point', async () => {
    const { transport, seenSince } = scriptedPullTransport([
      [
        relayedEvent(1, productPayload({ product_id: 'p-remote-1', sku: 'REMOTE-1' })),
        relayedEvent(2, productPayload({ product_id: 'p-remote-1', sku: 'REMOTE-1', unit_price: 300, updated_at: '2026-01-16T08:00:00.000Z' })),
      ],
    ]);

    const result = await pullAndApply(db, store.deviceId, transport);

    expect(result.fetched).toBe(2);
    expect(result.applied).toBe(2);
    expect(result.rejected).toBe(0);
    expect(seenSince[0]).toEqual({});
    // seq 2 was a re-upsert of the same product: it must land AFTER seq 1.
    expect(localProduct('p-remote-1')).toMatchObject({ sku: 'REMOTE-1', unit_price: 300 });

    const again = await pullAndApply(db, store.deviceId, transport);
    expect(again.fetched).toBe(0);
    // The next pull resumes where the last one left off.
    expect(seenSince[1]).toEqual({ [otherDeviceId]: 2 });
  });

  it('buffers a gap and drains the buffer when the missing event arrives', async () => {
    const { transport } = scriptedPullTransport([
      [
        relayedEvent(1, productPayload({ product_id: 'p-gap-1', sku: 'GAP-1' })),
        relayedEvent(3, productPayload({ product_id: 'p-gap-2', sku: 'GAP-2' })),
      ],
      [relayedEvent(2, productPayload({ product_id: 'p-gap-3', sku: 'GAP-3' }))],
    ]);

    const first = await pullAndApply(db, store.deviceId, transport);
    expect(first.applied).toBe(1);
    expect(first.buffered).toBe(1);
    expect(first.needFrom).toEqual({ [otherDeviceId]: 2 });
    // The out-of-order event is withheld, not applied.
    expect(localProduct('p-gap-2')).toBeUndefined();

    const second = await pullAndApply(db, store.deviceId, transport);
    // seq 2 applies and flushes the buffered seq 3 from the earlier pull.
    expect(second.applied).toBe(2);
    expect(second.buffered).toBe(0);
    expect(second.needFrom).toEqual({});
    expect(localProduct('p-gap-2')).toMatchObject({ sku: 'GAP-2' });
    expect(localProduct('p-gap-3')).toBeDefined();
  });

  it('treats re-delivered events as duplicates instead of re-executing them', async () => {
    const { transport } = scriptedPullTransport([
      [relayedEvent(1, productPayload({ product_id: 'p-dup-1', sku: 'DUP-1' }))],
      [
        // The transport re-delivers seq 1 along with the new seq 2.
        relayedEvent(1, productPayload({ product_id: 'p-dup-1', sku: 'DUP-1' })),
        relayedEvent(2, productPayload({ product_id: 'p-dup-2', sku: 'DUP-2' })),
      ],
    ]);

    const first = await pullAndApply(db, store.deviceId, transport);
    expect(first).toMatchObject({ fetched: 1, applied: 1, duplicates: 0 });

    const second = await pullAndApply(db, store.deviceId, transport);
    expect(second).toMatchObject({ fetched: 2, duplicates: 1, applied: 1 });
    // Each product ends up present exactly once.
    expect(
      get<{ n: number }>(db, "SELECT COUNT(*) AS n FROM products WHERE product_id IN ('p-dup-1', 'p-dup-2')"),
    ).toMatchObject({ n: 2 });
  });

  it('skips relayed events that originated on this device', async () => {
    const { transport } = scriptedPullTransport([
      [
        relayedEvent(1, productPayload({ product_id: 'p-own-1' }), 'product.upserted', store.deviceId),
        relayedEvent(1, productPayload({ product_id: 'p-other-1', sku: 'OTHER-1' })),
      ],
    ]);

    const result = await pullAndApply(db, store.deviceId, transport);

    expect(result.fetched).toBe(2);
    expect(result.applied).toBe(1);
    expect(result.results.map((r) => r.deviceId)).toEqual([otherDeviceId]);
    expect(localProduct('p-own-1')).toBeUndefined();
    expect(localProduct('p-other-1')).toBeDefined();
  });

  it('applies the contract/pull.json fixture verbatim on both event types', async () => {
    // The fixture is also tested on the Kotlin side (PullApiTest relays it over
    // real HTTP). Here the receiving device's dispatcher must accept it as-is.
    const here = dirname(fileURLToPath(import.meta.url));
    const fixture = JSON.parse(readFileSync(resolve(here, '../contract/pull.json'), 'utf8')) as {
      events: Array<Record<string, unknown>>;
    };
    const events = fixture.events.map((event, index) => ({
      ...event,
      deviceId: otherDeviceId,
      seq: index + 1,
    })) as InboundSyncEvent[];

    const { transport } = scriptedPullTransport([events]);
    const result = await pullAndApply(db, store.deviceId, transport);

    expect(result.applied).toBe(fixture.events.length);
    expect(result.rejected).toBe(0);
    const product = localProduct('fixture-product-0001');
    expect(product).toMatchObject({ sku: 'FIXTURE-SOAP', unit_price: 850, tax_rate_bp: 1250, is_active: 1 });
    const user = db.prepare('SELECT * FROM users WHERE user_id = ?').get('fixture-user-0001') as {
      full_name: string;
      role: string;
      mfa_enabled: number;
      password_hash: string;
      device_id: string | null;
    };
    expect(user).toMatchObject({
      full_name: 'Fixture Manager',
      role: 'manager',
      mfa_enabled: 1,
      password_hash: 'scrypt$16384$8$1$Zml4dHVyZQ==$ZGVyaXZlZF9vZl9maXh0dXJl',
      device_id: null,
    });
  });

  it('refuses a relayed user row that carries a plaintext secret', async () => {
    const { transport } = scriptedPullTransport([
      [
        relayedEvent(1, {
          user_id: 'u-plaintext',
          full_name: 'Cashier With A Plain PIN',
          role: 'cashier',
          pin_hash: '1234',
          mfa_enabled: 0,
        }, 'user.upserted'),
      ],
    ]);

    const result = await pullAndApply(db, store.deviceId, transport);
    expect(result.rejected).toBe(1);
    expect(result.applied).toBe(0);
    const rejected = db
      .prepare('SELECT reason FROM rejected_events WHERE device_id = ? AND seq = 1')
      .get(otherDeviceId) as { reason: string };
    expect(rejected.reason).toContain('does not carry a hashed pin_hash');
  });

  it('records an event type it cannot apply without blocking the stream', async () => {
    const { transport } = scriptedPullTransport([
      [
        relayedEvent(1, {}, 'sale.created'),
        relayedEvent(2, productPayload({ product_id: 'p-after-1', sku: 'AFTER-1' })),
      ],
    ]);

    const result = await pullAndApply(db, store.deviceId, transport);

    expect(result.rejected).toBe(1);
    expect(result.applied).toBe(1);
    // The event seq still advances, so seq 2 below it applies normally.
    expect(localProduct('p-after-1')).toBeDefined();
    const rejected = db
      .prepare('SELECT reason, payload FROM rejected_events WHERE device_id = ? AND seq = 1')
      .get(otherDeviceId) as { reason: string; payload: string };
    expect(rejected.reason).toBe('unsupported inbound event type: sale.created');
    expect(JSON.parse(rejected.payload)).toMatchObject({ seq: 1, eventType: 'sale.created' });
  });
});