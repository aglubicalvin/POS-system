import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { DatabaseSync } from 'node:sqlite';
import { describe, expect, it } from 'vitest';
import { SCHEMA_SQLITE } from '../src/db/schema.ts';

/**
 * The device runs SQLite; the Kotlin server runs PostgreSQL. The server's DDL is
 * the single source of truth for the Postgres dialect and lives in the Kotlin
 * project, so this test reaches across the language boundary and reads that file
 * directly. Same tables, same columns, same indexes — multi-dialect schemas rot
 * silently otherwise, and this is the only guard.
 */
const here = dirname(fileURLToPath(import.meta.url));
const SCHEMA_POSTGRES = readFileSync(
  resolve(here, '../server/src/main/resources/db/schema.sql'),
  'utf8',
);

function tables(sql: string): Map<string, string[]> {
  const result = new Map<string, string[]>();
  const tableRe = /CREATE TABLE IF NOT EXISTS\s+(\w+)\s*\(([\s\S]*?)\n\);/g;
  for (const match of sql.matchAll(tableRe)) {
    const name = match[1]!;
    const columns: string[] = [];
    for (const rawLine of match[2]!.split('\n')) {
      const column = /^\s*([a-z_][a-z0-9_]*)\s+/i.exec(rawLine);
      if (!column) continue;
      const word = column[1]!.toLowerCase();
      if (['unique', 'primary', 'foreign', 'check', 'constraint'].includes(word)) continue;
      columns.push(word);
    }
    result.set(name, columns.sort());
  }
  return result;
}

function indexNames(sql: string): string[] {
  return [...sql.matchAll(/CREATE INDEX IF NOT EXISTS\s+(\w+)/g)].map((m) => m[1]!).sort();
}

/** The tables the brief names, plus the ones the design requires to support them. */
const REQUIRED_TABLES = [
  'approvals',
  'audit_log',
  'device_sync_state',
  'device_tokens',
  'devices',
  'goods_received',
  'goods_received_items',
  'outbox',
  'payments',
  'pending_events',
  'processed_events',
  'products',
  'receipt_counters',
  'rejected_events',
  'sale_items',
  'sales',
  'shifts',
  'stock_movements',
  'stock_on_hand',
  'suppliers',
  'users',
];

describe('schema parity between SQLite and the Kotlin server PostgreSQL schema', () => {
  const sqlite = tables(SCHEMA_SQLITE);
  const postgres = tables(SCHEMA_POSTGRES);

  it('reads the Postgres DDL from the Kotlin project', () => {
    expect(SCHEMA_POSTGRES).toContain('CREATE TABLE IF NOT EXISTS sales');
    expect(SCHEMA_POSTGRES.length).toBeGreaterThan(2000);
  });

  it('defines every required table in both dialects', () => {
    for (const table of REQUIRED_TABLES) {
      expect(sqlite.has(table), `SQLite is missing ${table}`).toBe(true);
      expect(postgres.has(table), `Postgres is missing ${table}`).toBe(true);
    }
  });

  it('has the same set of tables in both dialects', () => {
    expect([...sqlite.keys()].sort()).toEqual([...postgres.keys()].sort());
  });

  it('has the same columns per table in both dialects', () => {
    for (const [table, columns] of sqlite) {
      expect(postgres.get(table), `${table} missing from Postgres`).toEqual(columns);
    }
  });

  it('has the same indexes in both dialects', () => {
    expect(indexNames(SCHEMA_SQLITE)).toEqual(indexNames(SCHEMA_POSTGRES));
  });

  it('opens the device schema cleanly', () => {
    const db = new DatabaseSync(':memory:');
    expect(() => db.exec(SCHEMA_SQLITE)).not.toThrow();
    db.close();
  });
});
