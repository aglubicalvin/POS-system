import { markAcked, markFailed, markSent, pendingBatch, type EventType, type OutboxRow } from '../domain/outbox.ts';
import type { Db } from '../db/db.ts';

/**
 * Client-side sync — the UP half (device → server).
 *
 * The transport is injected so the same drain logic runs against the real HTTPS
 * endpoint on a device and against a recording transport in tests. Events are
 * sent in per-device `seq` order; the server's idempotency ledger makes a
 * partially-applied, retried batch safe.
 *
 * The Kotlin server (`server/`) is the ONLY implementation of the apply logic
 * for this direction. The types below are the wire contract it deserialises —
 * they are duplicated here as types, not as behaviour, and
 * `contract/events.json` (produced by `scripts/emit-contract.ts`) is asserted
 * against the Kotlin DTOs so the two sides cannot drift.
 *
 * The DOWN half (server → device) lives in `apply.ts`, which applies relayed
 * events to the local store through the ordered applier in `ordering.ts`.
 */

/** One outbox row, serialised for `POST /sync`. */
export interface SyncEvent {
  eventId: string;
  deviceId: string;
  seq: number;
  eventType: EventType;
  aggregateId: string;
  payload: Record<string, unknown>;
  createdAt: string;
}

/** The server's verdict for one event. `duplicate` means it was recognised, not re-executed. */
export interface ApplyResult {
  eventId: string;
  status: 'applied' | 'duplicate';
  serverTotal?: number;
  clientTotal?: number;
  discrepancy?: boolean;
  reviewFlagged?: boolean;
}

export interface SyncTransport {
  send(events: SyncEvent[]): Promise<ApplyResult[]>;
}

export function toSyncEvent(row: OutboxRow): SyncEvent {
  return {
    eventId: row.event_id,
    deviceId: row.device_id,
    seq: row.seq,
    eventType: row.event_type,
    aggregateId: row.aggregate_id,
    payload: JSON.parse(row.payload) as Record<string, unknown>,
    createdAt: row.created_at,
  };
}

export interface DrainResult {
  sent: number;
  acked: number;
  failed: number;
  results: ApplyResult[];
}

/** Push one batch of pending events. Returns counts so the UI can show backlog. */
export async function drainOutbox(
  db: Db,
  deviceId: string,
  transport: SyncTransport,
  limit = 50,
): Promise<DrainResult> {
  const batch = pendingBatch(db, deviceId, limit);
  if (batch.length === 0) return { sent: 0, acked: 0, failed: 0, results: [] };

  const events = batch.map(toSyncEvent);
  for (const event of events) markSent(db, event.eventId);

  try {
    const results = await transport.send(events);
    let acked = 0;
    for (const result of results) {
      markAcked(db, result.eventId);
      acked += 1;
    }
    return { sent: events.length, acked, failed: events.length - acked, results };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    for (const event of events) markFailed(db, event.eventId, message);
    return { sent: events.length, acked: 0, failed: events.length, results: [] };
  }
}

/** Keep draining until the outbox is empty (bounded, for a sync-now button). */
export async function drainUntilEmpty(
  db: Db,
  deviceId: string,
  transport: SyncTransport,
  maxBatches = 100,
): Promise<DrainResult> {
  const total: DrainResult = { sent: 0, acked: 0, failed: 0, results: [] };
  for (let i = 0; i < maxBatches; i += 1) {
    const batch = await drainOutbox(db, deviceId, transport);
    total.sent += batch.sent;
    total.acked += batch.acked;
    total.failed += batch.failed;
    total.results.push(...batch.results);
    if (batch.sent === 0 || batch.failed > 0) break;
  }
  return total;
}
