import type { DatabaseSync } from "node:sqlite";

/** Also mirrored into SCHEMA_SQLITE and the Kotlin server's schema.sql. */
export const SYNC_ORDERING_SCHEMA = `
CREATE TABLE IF NOT EXISTS device_sync_state (
  device_id        TEXT PRIMARY KEY,
  last_applied_seq INTEGER NOT NULL DEFAULT 0,
  last_hash        TEXT
);
CREATE TABLE IF NOT EXISTS pending_events (
  device_id   TEXT NOT NULL,
  seq         INTEGER NOT NULL,
  payload     TEXT NOT NULL,
  received_at TEXT NOT NULL,
  PRIMARY KEY (device_id, seq)
);
CREATE TABLE IF NOT EXISTS rejected_events (
  device_id   TEXT NOT NULL,
  seq         INTEGER NOT NULL,
  reason      TEXT NOT NULL,
  payload     TEXT NOT NULL,
  rejected_at TEXT NOT NULL,
  PRIMARY KEY (device_id, seq)
);
`;

export interface SyncEvent {
  deviceId: string;
  seq: number; // per-device counter, starts at 1, no holes
  hash?: string; // device-computed hash of this event (only used when verifyChain is on)
  prevHash?: string | null; // hash of the device's previous event; null for seq 1
  [key: string]: unknown;
}

/** Throw this from applyEvent for an event that is permanently invalid (bad SKU, failed validation...). */
export class EventRejected extends Error {
  reason: string;
  constructor(reason: string) {
    super(reason);
    this.name = "EventRejected";
    this.reason = reason;
  }
}

class ChainBroken extends Error {}

export type ApplyEvent = (db: DatabaseSync, event: SyncEvent) => void;

export interface DeviceResult {
  deviceId: string;
  ackedThrough: number; // highest seq the server has applied; the device may delete up to here
  applied: number;
  duplicates: number;
  buffered: number;
  rejected: number;
  dropped: number; // gap events refused because the pending buffer was full
  needFrom?: number; // a gap remains: the device must resend starting at this seq
  error?: "chain_broken"; // device stopped, nothing further applied; needs investigation
}

export interface OrderedOptions {
  maxPending?: number; // per device, default 1000
  verifyChain?: boolean; // require hash/prevHash on every event and check them
  now?: () => string;
}

interface State {
  last: number;
  lastHash: string | null;
}

type Row = Record<string, unknown>;

function getState(db: DatabaseSync, deviceId: string): State {
  db.prepare("INSERT INTO device_sync_state (device_id) VALUES (?) ON CONFLICT (device_id) DO NOTHING").run(deviceId);
  const row = db
    .prepare("SELECT last_applied_seq, last_hash FROM device_sync_state WHERE device_id = ?")
    .get(deviceId) as Row;
  return { last: Number(row.last_applied_seq), lastHash: (row.last_hash as string | null) ?? null };
}

function tx<T>(db: DatabaseSync, fn: () => T): T {
  db.exec("BEGIN IMMEDIATE");
  try {
    const out = fn();
    db.exec("COMMIT");
    return out;
  } catch (e) {
    db.exec("ROLLBACK");
    throw e;
  }
}

function validate(e: SyncEvent, verifyChain: boolean): void {
  if (typeof e.deviceId !== "string" || e.deviceId === "") throw new TypeError("event.deviceId must be a non-empty string");
  if (!Number.isSafeInteger(e.seq) || e.seq < 1) throw new TypeError(`event.seq must be an integer >= 1 (device ${e.deviceId})`);
  if (verifyChain && (typeof e.hash !== "string" || !(e.prevHash === null || typeof e.prevHash === "string"))) {
    throw new TypeError(`verifyChain needs hash and prevHash on every event (device ${e.deviceId}, seq ${e.seq})`);
  }
}

/**
 * Applies events per device, strictly in seq order.
 * - seq <= last applied: duplicate, acknowledged and skipped
 * - seq == last + 1:     applied (with its state update, in one transaction)
 * - seq >  last + 1:     buffered, and the result tells the device where to resend from
 * A gap on one device never blocks another device.
 *
 * Call it outside any open transaction. It opens one per event. A crash mid-batch is safe: resend and duplicates are skipped.
 * applyEvent must not manage transactions itself. Throw EventRejected for permanently bad events (they are logged and the
 * sequence moves on); any other error rolls that event back and propagates, so a retry will redo it.
 */
export function applyBatchOrdered(
  db: DatabaseSync,
  events: SyncEvent[],
  applyEvent: ApplyEvent,
  opts: OrderedOptions = {},
): DeviceResult[] {
  const maxPending = opts.maxPending ?? 1000;
  const verifyChain = opts.verifyChain ?? false;
  const now = opts.now ?? (() => new Date().toISOString());

  for (const e of events) validate(e, verifyChain);
  const byDevice = new Map<string, SyncEvent[]>();
  for (const e of events) {
    const list = byDevice.get(e.deviceId) ?? [];
    list.push(e);
    byDevice.set(e.deviceId, list);
  }

  const results: DeviceResult[] = [];
  for (const [deviceId, list] of byDevice) {
    list.sort((a, b) => a.seq - b.seq);
    const r: DeviceResult = { deviceId, ackedThrough: 0, applied: 0, duplicates: 0, buffered: 0, rejected: 0, dropped: 0 };

    // Must run inside a transaction: applies one event and advances the device's state.
    const applyAndAdvance = (st: State, e: SyncEvent): void => {
      if (verifyChain && (e.prevHash ?? null) !== st.lastHash) {
        throw new ChainBroken(`device ${deviceId} seq ${e.seq}: prevHash does not match the last applied hash`);
      }
      let rejection: string | null = null;
      db.exec("SAVEPOINT apply_evt");
      try {
        applyEvent(db, e);
        db.exec("RELEASE apply_evt");
      } catch (err) {
        db.exec("ROLLBACK TO apply_evt");
        db.exec("RELEASE apply_evt");
        if (err instanceof EventRejected) rejection = err.reason;
        else throw err;
      }
      if (rejection !== null) {
        db.prepare(
          "INSERT INTO rejected_events (device_id, seq, reason, payload, rejected_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT (device_id, seq) DO NOTHING",
        ).run(deviceId, e.seq, rejection, JSON.stringify(e), now());
        r.rejected++;
      } else {
        r.applied++;
      }
      db.prepare("UPDATE device_sync_state SET last_applied_seq = ?, last_hash = COALESCE(?, last_hash) WHERE device_id = ?").run(
        e.seq,
        verifyChain ? (e.hash as string) : null,
        deviceId,
      );
    };

    try {
      for (const e of list) {
        tx(db, () => {
          const st = getState(db, deviceId);
          if (e.seq <= st.last) {
            r.duplicates++;
          } else if (e.seq === st.last + 1) {
            applyAndAdvance(st, e);
          } else {
            const { n } = db.prepare("SELECT COUNT(*) AS n FROM pending_events WHERE device_id = ?").get(deviceId) as Row;
            if (Number(n) >= maxPending) {
              r.dropped++;
              return;
            }
            const res = db
              .prepare(
                "INSERT INTO pending_events (device_id, seq, payload, received_at) VALUES (?, ?, ?, ?) ON CONFLICT (device_id, seq) DO NOTHING",
              )
              .run(deviceId, e.seq, JSON.stringify(e), now());
            if (Number(res.changes) === 1) r.buffered++;
            else r.duplicates++;
          }
        });
      }

      // Drain buffered events that are now next in line.
      for (;;) {
        const progressed = tx(db, () => {
          const st = getState(db, deviceId);
          db.prepare("DELETE FROM pending_events WHERE device_id = ? AND seq <= ?").run(deviceId, st.last);
          const row = db
            .prepare("SELECT payload FROM pending_events WHERE device_id = ? AND seq = ?")
            .get(deviceId, st.last + 1) as Row | undefined;
          if (!row) return false;
          db.prepare("DELETE FROM pending_events WHERE device_id = ? AND seq = ?").run(deviceId, st.last + 1);
          applyAndAdvance(st, JSON.parse(row.payload as string) as SyncEvent);
          return true;
        });
        if (!progressed) break;
      }
    } catch (err) {
      if (err instanceof ChainBroken) r.error = "chain_broken";
      else throw err;
    }

    const st = tx(db, () => getState(db, deviceId));
    r.ackedThrough = st.last;
    const hasPending = db.prepare("SELECT 1 AS x FROM pending_events WHERE device_id = ? LIMIT 1").get(deviceId) !== undefined;
    if (hasPending) r.needFrom = st.last + 1;
    results.push(r);
  }
  return results;
}
