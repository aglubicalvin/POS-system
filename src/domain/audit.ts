import { createHash } from 'node:crypto';
import { get, nowIso, run, scalar, transaction, type Db, type SqlValue } from '../db/db.ts';
import { newUuid } from './ids.ts';

/**
 * Tamper-evident audit log.
 *
 * Each row's `hash` covers its own content *and* the previous row's hash for the
 * same device, forming a chain. Editing or deleting a historical row changes the
 * recomputed hash of that row and every row after it, so `verifyChain` detects it
 * without needing a separate signature store.
 */

export const GENESIS_HASH = '0'.repeat(64);

export interface AuditInput {
  deviceId: string;
  actorId: string | null;
  action: string;
  entityType?: string | null;
  entityId?: string | null;
  details?: Record<string, unknown> | null;
  occurredAt?: string;
}

export interface AuditRow {
  audit_id: string;
  device_id: string;
  seq: number;
  occurred_at: string;
  actor_id: string | null;
  action: string;
  entity_type: string | null;
  entity_id: string | null;
  details: string | null;
  prev_hash: string;
  hash: string;
}

/** Deterministic JSON: object keys sorted, so equal data always hashes equally. */
export function stableStringify(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value) ?? 'null';
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`;
  const entries = Object.entries(value as Record<string, unknown>)
    .filter(([, v]) => v !== undefined)
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
  return `{${entries.map(([k, v]) => `${JSON.stringify(k)}:${stableStringify(v)}`).join(',')}}`;
}

export function computeAuditHash(row: {
  device_id: string;
  seq: number;
  occurred_at: string;
  actor_id: string | null;
  action: string;
  entity_type: string | null;
  entity_id: string | null;
  details: string | null;
  prev_hash: string;
}): string {
  const canonical = stableStringify([
    row.device_id,
    row.seq,
    row.occurred_at,
    row.actor_id,
    row.action,
    row.entity_type,
    row.entity_id,
    row.details,
    row.prev_hash,
  ]);
  return createHash('sha256').update(canonical).digest('hex');
}

/**
 * Append an audit entry. Must run inside the transaction of the action it
 * records so the log and the effect commit or roll back together.
 */
export function appendAudit(db: Db, input: AuditInput): AuditRow {
  return transaction(db, () => {
    const seq = Number(scalar(db, 'SELECT COALESCE(MAX(seq), 0) + 1 FROM audit_log WHERE device_id = ?', input.deviceId) ?? 1);
    const prev = get<{ hash: string }>(
      db,
      'SELECT hash FROM audit_log WHERE device_id = ? ORDER BY seq DESC LIMIT 1',
      input.deviceId,
    );
    const prevHash = prev?.hash ?? GENESIS_HASH;
    const details = input.details ? stableStringify(input.details) : null;
    const occurredAt = input.occurredAt ?? nowIso();
    const row: AuditRow = {
      audit_id: newUuid(),
      device_id: input.deviceId,
      seq,
      occurred_at: occurredAt,
      actor_id: input.actorId,
      action: input.action,
      entity_type: input.entityType ?? null,
      entity_id: input.entityId ?? null,
      details,
      prev_hash: prevHash,
      hash: '',
    };
    row.hash = computeAuditHash(row);

    run(
      db,
      `INSERT INTO audit_log
         (audit_id, device_id, seq, occurred_at, actor_id, action, entity_type, entity_id, details, prev_hash, hash)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      row.audit_id,
      row.device_id,
      row.seq,
      row.occurred_at,
      row.actor_id,
      row.action,
      row.entity_type,
      row.entity_id,
      row.details,
      row.prev_hash,
      row.hash,
    );
    return row;
  });
}

export interface ChainVerification {
  ok: boolean;
  entries: number;
  /** First sequence number whose recomputed hash or link is wrong. */
  brokenAtSeq?: number;
  reason?: string;
}

/** Recompute the whole chain for a device and report the first broken link. */
export function verifyChain(db: Db, deviceId: string): ChainVerification {
  const rows = db
    .prepare('SELECT * FROM audit_log WHERE device_id = ? ORDER BY seq ASC')
    .all(deviceId) as unknown as AuditRow[];

  let prevHash = GENESIS_HASH;
  let expectedSeq = 1;

  for (const row of rows) {
    if (row.seq !== expectedSeq) {
      return { ok: false, entries: rows.length, brokenAtSeq: row.seq, reason: `gap in sequence at ${row.seq}` };
    }
    if (row.prev_hash !== prevHash) {
      return { ok: false, entries: rows.length, brokenAtSeq: row.seq, reason: `prev_hash mismatch at ${row.seq}` };
    }
    const recomputed = computeAuditHash(row);
    if (recomputed !== row.hash) {
      return { ok: false, entries: rows.length, brokenAtSeq: row.seq, reason: `content hash mismatch at ${row.seq}` };
    }
    prevHash = row.hash;
    expectedSeq += 1;
  }
  return { ok: true, entries: rows.length };
}

export function listAudit(db: Db, deviceId: string, limit = 100): AuditRow[] {
  return db
    .prepare('SELECT * FROM audit_log WHERE device_id = ? ORDER BY seq DESC LIMIT ?')
    .all(deviceId, limit) as unknown as AuditRow[];
}

export type { SqlValue };
