import { beforeEach, describe, expect, it } from 'vitest';
import type { Db } from '../src/db/db.ts';
import { appendAudit, computeAuditHash, GENESIS_HASH, verifyChain } from '../src/domain/audit.ts';
import { seedStore, type SeededStore } from './helpers.ts';

describe('hash-chained audit log', () => {
  let store: SeededStore;
  let db: Db;

  beforeEach(() => {
    store = seedStore();
    db = store.db;
  });

  it('verifies an untouched chain', () => {
    appendAudit(db, { deviceId: store.deviceId, actorId: store.cashier.user_id, action: 'shift.opened', details: { openingFloat: 10000 } });
    appendAudit(db, { deviceId: store.deviceId, actorId: store.cashier.user_id, action: 'sale.created', details: { total: 1620 } });
    appendAudit(db, { deviceId: store.deviceId, actorId: store.manager.user_id, action: 'sale.voided', details: { reason: 'customer changed mind' } });

    const result = verifyChain(db, store.deviceId);
    expect(result.ok).toBe(true);
    expect(result.entries).toBe(3);

    const first = db.prepare('SELECT * FROM audit_log WHERE seq = 1').get() as { prev_hash: string };
    expect(first.prev_hash).toBe(GENESIS_HASH);
  });

  it('detects a tampered row and reports where the chain broke', () => {
    appendAudit(db, { deviceId: store.deviceId, actorId: store.cashier.user_id, action: 'sale.created', details: { total: 1620 } });
    appendAudit(db, { deviceId: store.deviceId, actorId: store.cashier.user_id, action: 'sale.created', details: { total: 450 } });

    // Rewrite history: inflate the first sale's total.
    db.prepare(`UPDATE audit_log SET details = '{"total":999999}' WHERE seq = 1`).run();

    const result = verifyChain(db, store.deviceId);
    expect(result.ok).toBe(false);
    expect(result.brokenAtSeq).toBe(1);
    expect(result.reason).toContain('content hash mismatch');
  });

  it('detects a deleted row as a sequence gap', () => {
    appendAudit(db, { deviceId: store.deviceId, actorId: store.cashier.user_id, action: 'a' });
    appendAudit(db, { deviceId: store.deviceId, actorId: store.cashier.user_id, action: 'b' });
    appendAudit(db, { deviceId: store.deviceId, actorId: store.cashier.user_id, action: 'c' });

    db.prepare('DELETE FROM audit_log WHERE seq = 2').run();

    const result = verifyChain(db, store.deviceId);
    expect(result.ok).toBe(false);
    expect(result.brokenAtSeq).toBe(3);
  });

  it('is deterministic for identical content', () => {
    const row = {
      device_id: store.deviceId,
      seq: 1,
      occurred_at: '2026-10-09T10:00:00.000Z',
      actor_id: store.cashier.user_id,
      action: 'sale.created',
      entity_type: 'sale',
      entity_id: 'sale-1',
      details: '{"b":1,"a":2}',
      prev_hash: GENESIS_HASH,
    };
    expect(computeAuditHash(row)).toBe(computeAuditHash(row));
    expect(computeAuditHash(row)).toMatch(/^[0-9a-f]{64}$/);
  });
});
