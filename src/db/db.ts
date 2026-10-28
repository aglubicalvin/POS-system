import { DatabaseSync } from 'node:sqlite';
import { SCHEMA_SQLITE } from './schema.ts';

export type Db = DatabaseSync;

/** SQLite values we actually persist. node:sqlite supports these bind types. */
export type SqlValue = string | number | bigint | null;

const txDepth = new WeakMap<Db, number>();

export function nowIso(): string {
  return new Date().toISOString();
}

/**
 * Open a database and ensure the schema exists. Passing ':memory:' gives each
 * test an isolated store; on a device this points at the local SQLite file and
 * on CI it can point at a temp file to exercise WAL + durability.
 * Unified migration tracking.
 */
export function openDb(path = ':memory:'): Db {
  const db = new DatabaseSync(path);
  db.exec('PRAGMA foreign_keys = ON;');
  if (path !== ':memory:') {
    db.exec('PRAGMA journal_mode = WAL;');
    db.exec('PRAGMA synchronous = FULL;');
  }
  // Create migration tracking table for unified migrations
  db.exec(`CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    applied_at TEXT NOT NULL DEFAULT (datetime('now'))
  );`);
  db.exec(SCHEMA_SQLITE);
  try {
    db.exec("INSERT OR IGNORE INTO schema_migrations (version, name) VALUES ('0000', 'baseline_schema');");
  } catch {}
  txDepth.set(db, 0);
  return db;
}

export function exec(db: Db, sql: string): void {
  db.exec(sql);
}

export function run(db: Db, sql: string, ...params: SqlValue[]) {
  return db.prepare(sql).run(...params);
}

export function all<T = Record<string, SqlValue>>(db: Db, sql: string, ...params: SqlValue[]): T[] {
  return db.prepare(sql).all(...params) as T[];
}

export function get<T = Record<string, SqlValue>>(db: Db, sql: string, ...params: SqlValue[]): T | undefined {
  return db.prepare(sql).get(...params) as T | undefined;
}

export function scalar(db: Db, sql: string, ...params: SqlValue[]): SqlValue {
  const row = get<Record<string, SqlValue>>(db, sql, ...params);
  if (!row) return null;
  const first = Object.values(row)[0];
  return first ?? null;
}

/**
 * Run `fn` in a single atomic transaction. Nested calls become SAVEPOINTs so a
 * checkout can compose helpers (stock, audit, outbox) without each one opening
 * its own transaction or silently committing an outer one.
 */
export function transaction<T>(db: Db, fn: () => T): T {
  const depth = txDepth.get(db) ?? 0;
  const savepoint = `sp_${depth}`;

  db.exec(depth === 0 ? 'BEGIN IMMEDIATE' : `SAVEPOINT ${savepoint}`);
  txDepth.set(db, depth + 1);
  try {
    const result = fn();
    db.exec(depth === 0 ? 'COMMIT' : `RELEASE ${savepoint}`);
    return result;
  } catch (error) {
    try {
      db.exec(depth === 0 ? 'ROLLBACK' : `ROLLBACK TO ${savepoint}`);
      if (depth > 0) db.exec(`RELEASE ${savepoint}`);
    } catch {
      // A failed rollback leaves the connection unusable; surface the original error.
    }
    throw error;
  } finally {
    txDepth.set(db, depth);
  }
}

/** Test helper: true when no transaction is currently open. */
export function inTransaction(db: Db): boolean {
  return (txDepth.get(db) ?? 0) > 0;
}
