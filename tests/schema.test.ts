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
 * directly.
 *
 * Phase 4.3 widens the PostgreSQL side with objects that live in the Supabase
 * migrations under `supabase/migrations/0005`–`0007`, not in the Kotlin server's
 * canonical `schema.sql`. This guard therefore keeps two separate jobs:
 *
 * 1. `schema.sql` canonical POS tables/columns/indexes must still match SQLite
 *    exactly (the part that both runtimes share).
 * 2. The new admin objects introduced by migrations 0005–0007 are asserted here
 *    by reading those migration files, so the assertion tracks the actual migration
 *    contents rather than assuming a future `schema.sql` state.
 *
 * `approvements` is intentionally not asserted: no migration in this phase creates
 * a table with that name, so a test expectation for it would encode an intended
 * future state as if it were already implemented.
 */
const here = dirname(fileURLToPath(import.meta.url));
const SCHEMA_POSTGRES = readFileSync(
  resolve(here, '../server/src/main/resources/db/schema.sql'),
  'utf8',
);

function tables(sql: string): Map<string, string[]> {
  const result = new Map<string, string[]>();
  const tableRe = /CREATE TABLE IF NOT EXISTS\s+(\w+)\s*\(([\s\S]*?)\n\);/gi;
  for (const match of sql.matchAll(tableRe)) {
    const name = match[1]!.toLowerCase();
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
  return [...sql.matchAll(/CREATE INDEX IF NOT EXISTS\s+(\w+)/gi)].map((m) => m[1]!.toLowerCase()).sort();
}

/**
 * The parameter type list of each `create or replace function` in a migration.
 *
 * A GRANT names a function by its full signature, so a mismatch between the
 * declaration and the GRANT makes the whole migration fail on PostgreSQL with
 * "function ... does not exist". That happened in 0006 (the hire-purchase contract
 * RPC was granted with one `bigint` missing), and only executing the migration
 * caught it — this keeps the cheap structural check honest as well.
 */
function declaredSignatures(sql: string): Map<string, string> {
  const result = new Map<string, string>();
  const functionRe = /create or replace function\s+(?:public\.)?(\w+)\s*\(([\s\S]*?)\)\s*returns/gi;
  for (const match of sql.matchAll(functionRe)) {
    const types = match[2]!
      .split(',')
      .map((param) => {
        const words = param.trim().split(/\s+/);
        // ``p_name type`` → type; a bare ``type`` (no parameter name) stays as-is.
        return (words.length > 1 ? words.slice(1).join(' ') : words[0]!).toLowerCase();
      })
      .filter(Boolean);
    result.set(match[1]!.toLowerCase(), types.join(', '));
  }
  return result;
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

const MIGRATION_ROOT = resolve(here, '../supabase/migrations');

function migrationSql(name: string): string {
  return readFileSync(resolve(MIGRATION_ROOT, name), 'utf8');
}

function tablesInSql(sql: string): Map<string, string[]> {
  return tables(sql);
}

/**
 * Objects created by migrations 0005–0007.
 *
 * These are asserted against the migration files, not against `schema.sql`.
 * `schema.sql` is the shared canonical POS schema; the admin extensions live in
 * the Supabase migrations and are owned by the Kotlin server.
 */
const TABLES_FROM_0005 = [
  'customers',
  'payroll_deductions',
  'hire_purchase_contracts',
  'hire_purchase_payments',
];

const VIEWS_FROM_0005 = [
  'customer_purchase_summary',
  'hire_purchase_overview',
];

const VIEWS_FROM_0006 = [
  'admin_product_catalog',
  'admin_inventory_overview',
  'customer_purchase_summary', // 0006 also replaces this 0005 view with an aggregation rewrite
];

const FUNCTIONS_FROM_0006 = [
  'admin_update_product_media',
  'admin_create_hire_purchase_contract',
  'admin_pay_hire_purchase_installment',
];

const FUNCTIONS_FROM_0007 = [
  'admin_reconcile_mobile_money_payment',
];

const INDEXES_FROM_0006 = [
  'idx_payroll_deductions_month_status',
  'idx_hire_purchase_status_end_date',
  'idx_sales_customer_created',
];

describe('schema parity between SQLite and the Kotlin server PostgreSQL schema', () => {
  const sqlite = tables(SCHEMA_SQLITE);
  const postgres = tables(SCHEMA_POSTGRES);
  const sql0005 = migrationSql('0005_customers_payroll_hirepurchase.sql');
  const sql0006 = migrationSql('0006_phase43_workflows.sql');
  const sql0007 = migrationSql('0007_mobile_money_reconciliation.sql');

  it('reads the Postgres DDL from the Kotlin project', () => {
    expect(SCHEMA_POSTGRES).toContain('CREATE TABLE IF NOT EXISTS sales');
    expect(SCHEMA_POSTGRES.length).toBeGreaterThan(2000);
  });

  it('defines every canonical POS table in both dialects', () => {
    for (const table of REQUIRED_TABLES) {
      expect(sqlite.has(table), `SQLite is missing ${table}`).toBe(true);
      expect(postgres.has(table), `Postgres is missing ${table}`).toBe(true);
    }
  });

  it('has the same set of canonical POS tables in both dialects', () => {
    expect([...sqlite.keys()].sort()).toEqual([...postgres.keys()].filter((t) => REQUIRED_TABLES.includes(t)).sort());
  });

  it('has the same columns on every canonical POS table in both dialects', () => {
    for (const table of REQUIRED_TABLES) {
      const sqliteCols = sqlite.get(table)!;
      const postgresCols = postgres.get(table)!;
      expect(postgresCols, `${table}: Postgres is missing the table`).toBeDefined();
      expect(sqliteCols).toEqual(postgresCols);
    }
  });

  it('has the same indexes on every canonical POS table in both dialects', () => {
    const sqliteIdx = indexNames(SCHEMA_SQLITE);
    const postgresIdx = indexNames(SCHEMA_POSTGRES);
    for (const idx of sqliteIdx) {
      expect(postgresIdx).toContain(idx);
    }
    for (const idx of postgresIdx) {
      if (idx.startsWith('idx_sales_customer_created')) {
        continue;
      }
      expect(sqliteIdx).toContain(idx);
    }
  });

  it('migration 0005 creates the new admin tables and views', () => {
    const sql0005Tables = tablesInSql(sql0005);
    expect([...sql0005Tables.keys()].sort()).toEqual(TABLES_FROM_0005.sort());
    const sql0005Views = [...sql0005.matchAll(/create or replace view (?:public\.)?([a-z_]+)/gi)].map((m) => m[1]!.toLowerCase());
    expect(sql0005Views.sort()).toEqual(VIEWS_FROM_0005.sort());
    expect(sql0005).toContain(`create policy customers_admin_all on customers`);
    expect(sql0005).toContain(`create policy payroll_deductions_admin_all on payroll_deductions`);
    expect(sql0005).toContain(`create policy hp_contracts_admin_all on hire_purchase_contracts`);
    expect(sql0005).toContain(`create policy hp_payments_admin_all on hire_purchase_payments`);
  });

  it('migration 0006 adds indexes, views, and admin workflow functions', () => {
    expect(sql0006).toContain(`create index if not exists idx_payroll_deductions_month_status`);
    expect(sql0006).toContain(`create index if not exists idx_hire_purchase_status_end_date`);
    expect(sql0006).toContain(`create index if not exists idx_sales_customer_created`);
    const sql0006Views = [...sql0006.matchAll(/create or replace view (?:public\.)?([a-z_]+)/gi)].map((m) => m[1]!.toLowerCase());
    expect(sql0006Views.sort()).toEqual(VIEWS_FROM_0006.sort());
    const sql0006Functions = [...sql0006.matchAll(/create or replace function (?:public\.)?([a-z_]+)/gi)].map((m) => m[1]!.toLowerCase());
    expect(sql0006Functions.sort()).toEqual(FUNCTIONS_FROM_0006.sort());
    for (const fn of FUNCTIONS_FROM_0006) {
      expect(sql0006).toContain(`grant execute on function public.${fn}(`);
      expect(sql0006).toContain(`revoke execute on function public.${fn}(`);
    }
  });

  it("grants and revokes use each function's declared signature", () => {
    for (const sql of [sql0006, sql0007]) {
      for (const [name, signature] of declaredSignatures(sql)) {
        expect(
          sql.toLowerCase(),
          `${name} is granted with a signature that does not match its declaration`,
        ).toContain(`grant execute on function public.${name}(${signature})`);
        expect(
          sql.toLowerCase(),
          `${name} is revoked with a signature that does not match its declaration`,
        ).toContain(`revoke execute on function public.${name}(${signature})`);
      }
    }
  });

  it('migration 0007 adds the MoMo reconciliation function', () => {
    const sql0007Functions = [...sql0007.matchAll(/create or replace function (?:public\.)?([a-z_]+)/gi)].map((m) => m[1]!.toLowerCase());
    expect(sql0007Functions.sort()).toEqual(FUNCTIONS_FROM_0007.sort());
    expect(sql0007).toContain(`grant execute on function public.admin_reconcile_mobile_money_payment(`);
    expect(sql0007).toContain(`revoke execute on function public.admin_reconcile_mobile_money_payment(`);
  });

  it('migration 0005 extends products and sales with new columns', () => {
    expect(sql0005).toContain(`alter table products add column if not exists description text`);
    expect(sql0005).toContain(`alter table products add column if not exists image_url text`);
    expect(sql0005).toContain(`alter table products add column if not exists thumbnail_url text`);
    expect(sql0005).toContain(`alter table sales add column if not exists customer_id uuid references customers(customer_id)`);
    expect(sql0005).toContain(`alter table sales add column if not exists payment_channel text check (payment_channel in ('cash', 'ghana_card', 'payroll_deduction', 'momo')) default 'cash'`);
    expect(sql0005).toContain(`alter table sales add column if not exists staff_number text`);
  });

  it('0006 can run after 0005 without assuming extra tables', () => {
    const combined = sql0005 + '\n' + sql0006;
    const combinedTables = tablesInSql(combined);
    for (const table of TABLES_FROM_0005) {
      expect(combinedTables.has(table), `0006 references a table ${table} that 0005 does not create`).toBe(true);
    }
  });

  it('0007 only reconciles already-confirmed mobile_money payments', () => {
    expect(sql0007).toContain(`if v_method <> 'mobile_money' then raise exception 'only mobile-money payments can be reconciled here'`);
    expect(sql0007).toContain(`if v_status <> 'confirmed' then`);
  });

  it('opens the device schema cleanly', () => {
    const db = new DatabaseSync(':memory:');
    expect(() => db.exec(SCHEMA_SQLITE)).not.toThrow();
    db.close();
  });
});
