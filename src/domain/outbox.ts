import { all, get, nowIso, run, transaction, type Db } from '../db/db.ts';
import { eventIdempotencyKey, newUuid } from './ids.ts';

/**
 * Durable outbox.
 *
 * Every state change that the server must learn about is appended to `outbox`
 * inside the same transaction that made the change, so a crash between "sale
 * committed" and "upload attempted" cannot lose the event. A background sync
 * loop drains it in per-device `seq` order and the server applies each event
 * idempotently using `idempotency_key`.
 */

export type EventType =
  | 'sale.created'
  | 'sale.voided'
  | 'sale.refunded'
  | 'payment.updated'
  | 'goods_received.created'
  | 'stock.movement'
  | 'shift.opened'
  | 'shift.closed'
  | 'user.upserted'
  | 'product.upserted';

export type OutboxStatus = 'pending' | 'sent' | 'acked' | 'failed';

export interface OutboxRow {
  event_id: string;
  device_id: string;
  seq: number;
  event_type: EventType;
  aggregate_type: string;
  aggregate_id: string;
  payload: string;
  idempotency_key: string;
  status: OutboxStatus;
  attempts: number;
  last_error: string | null;
  created_at: string;
  sent_at: string | null;
  acked_at: string | null;
}

export interface EnqueueInput {
  deviceId: string;
  eventType: EventType;
  aggregateType: string;
  aggregateId: string;
  payload: Record<string, unknown>;
  createdAt?: string;
  /**
   * Overrides the derived key for event types where repeat enqueues are
   * DISTINCT logical events, not retries — e.g. successive edits of the same
   * catalog row. The default (device, type, aggregate) key would collapse the
   * second edit into the first and never transmit it.
   */
  idempotencyKey?: string;
  /** Optional version from the product row; included in the payload so the
   * receiving device can detect stale edits across pulls. */
  version?: number;
}

export class OutboxError extends Error {
  override name = 'OutboxError';
}

/**
 * Append an event. The idempotency key is derived from (device, type, aggregate)
 * so enqueuing the same logical event twice is a no-op rather than a duplicate.
 */
export function enqueue(db: Db, input: EnqueueInput): OutboxRow {
  return transaction(db, () => {
    const key = input.idempotencyKey ?? eventIdempotencyKey(input.deviceId, input.eventType, input.aggregateId);
    const existing = findByKey(db, key);
    if (existing) return existing;

    const seq = Number(
      get<{ next_seq: number }>(
        db,
        'SELECT COALESCE(MAX(seq), 0) + 1 AS next_seq FROM outbox WHERE device_id = ?',
        input.deviceId,
      )?.next_seq ?? 1,
    );

    const row: OutboxRow = {
      event_id: newUuid(),
      device_id: input.deviceId,
      seq,
      event_type: input.eventType,
      aggregate_type: input.aggregateType,
      aggregate_id: input.aggregateId,
      payload: JSON.stringify(input.payload),
      idempotency_key: key,
      status: 'pending',
      attempts: 0,
      last_error: null,
      created_at: input.createdAt ?? nowIso(),
      sent_at: null,
      acked_at: null,
    };

    run(
      db,
      `INSERT INTO outbox
         (event_id, device_id, seq, event_type, aggregate_type, aggregate_id, payload,
          idempotency_key, status, attempts, last_error, created_at, sent_at, acked_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      row.event_id,
      row.device_id,
      row.seq,
      row.event_type,
      row.aggregate_type,
      row.aggregate_id,
      row.payload,
      row.idempotency_key,
      row.status,
      row.attempts,
      row.last_error,
      row.created_at,
      row.sent_at,
      row.acked_at,
    );
    return row;
  });
}

export function findByKey(db: Db, idempotencyKey: string): OutboxRow | undefined {
  return get<OutboxRow>(db, 'SELECT * FROM outbox WHERE idempotency_key = ?', idempotencyKey);
}

export function findEvent(db: Db, eventId: string): OutboxRow | undefined {
  return get<OutboxRow>(db, 'SELECT * FROM outbox WHERE event_id = ?', eventId);
}

/**
 * Up to `limit` events ready to send, oldest first. `failed` events are retried
 * only after the caller has had a chance to inspect `last_error`.
 */
export function pendingBatch(db: Db, deviceId: string, limit = 50): OutboxRow[] {
  return all<OutboxRow>(
    db,
    `SELECT * FROM outbox
      WHERE device_id = ? AND status IN ('pending', 'sent')
      ORDER BY seq ASC
      LIMIT ?`,
    deviceId,
    limit,
  );
}

export function markSent(db: Db, eventId: string): void {
  run(
    db,
    `UPDATE outbox SET status = 'sent', attempts = attempts + 1, sent_at = ? WHERE event_id = ?`,
    nowIso(),
    eventId,
  );
}

export function markAcked(db: Db, eventId: string): void {
  run(db, `UPDATE outbox SET status = 'acked', acked_at = ?, last_error = NULL WHERE event_id = ?`, nowIso(), eventId);
}

export function markFailed(db: Db, eventId: string, error: string): void {
  run(
    db,
    `UPDATE outbox SET status = 'failed', attempts = attempts + 1, last_error = ? WHERE event_id = ?`,
    error.slice(0, 500),
    eventId,
  );
}

/** Re-arm failed events, e.g. after a reconnect or a server-side fix. */
export function requeueFailed(db: Db, deviceId: string): number {
  const before = get<{ n: number }>(
    db,
    `SELECT COUNT(*) AS n FROM outbox WHERE device_id = ? AND status = 'failed'`,
    deviceId,
  )?.n ?? 0;
  run(db, `UPDATE outbox SET status = 'pending', last_error = NULL WHERE device_id = ? AND status = 'failed'`, deviceId);
  return before;
}

export function outboxStats(db: Db, deviceId: string): Record<OutboxStatus, number> {
  const rows = all<{ status: OutboxStatus; n: number }>(
    db,
    'SELECT status, COUNT(*) AS n FROM outbox WHERE device_id = ? GROUP BY status',
    deviceId,
  );
  const stats: Record<OutboxStatus, number> = { pending: 0, sent: 0, acked: 0, failed: 0 };
  for (const row of rows) stats[row.status] = row.n;
  return stats;
}
