import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, describe, expect, it } from 'vitest';

/**
 * The evidence contract behind the staging identity workflow, exercised through
 * the same script the workflow calls.
 *
 * The previous inline workflow block accepted `read_only_transaction=enforced`
 * and rewrote it to `server_confirmed`, which published confirmation for a
 * protection the binary never checked. These tests pin the replacement: only an
 * exact `server_confirmed` line, an exact `probe_status=ok`, and a zero exit
 * status can produce `probe_completion=ok`.
 */
const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..');
const script = resolve(repoRoot, 'server/tools/probe-evidence.sh');

function findBash(): string | null {
  const candidates = [
    'C:\\Program Files\\Git\\bin\\bash.exe',
    'C:\\Program Files\\Git\\usr\\bin\\bash.exe',
    '/bin/bash',
    '/usr/bin/bash',
  ];
  for (const candidate of candidates) {
    if (!existsSync(candidate)) continue;
    const probe = spawnSync(candidate, ['--version'], { encoding: 'utf8' });
    if (probe.status === 0 && /bash/.test(probe.stdout)) return candidate;
  }
  return null;
}

const bash = findBash();
const itBash = bash ? it : it.skip;

const dirs: string[] = [];
afterAll(() => {
  for (const dir of dirs) rmSync(dir, { recursive: true, force: true });
});

function run(raw: string, status = 0) {
  const dir = mkdtempSync(join(tmpdir(), 'probe-evidence-'));
  dirs.push(dir);
  const rawPath = join(dir, 'probe-raw.txt');
  const evidencePath = join(dir, 'probe-evidence.txt');
  writeFileSync(rawPath, raw, 'utf8');

  const result = spawnSync(bash as string, [script, rawPath, evidencePath, String(status), 'stagingref0000000001'], {
    encoding: 'utf8',
    timeout: 60_000,
    env: { PATH: process.env.PATH ?? '', HOME: process.env.HOME ?? '' },
  });

  const evidence = existsSync(evidencePath) ? readFileSync(evidencePath, 'utf8') : '';
  return { status: result.status, output: `${result.stdout}${result.stderr}`, evidence };
}

function field(evidence: string, name: string): string | null {
  const line = evidence.split(/\r?\n/).find((value) => value.startsWith(`${name}=`));
  return line === undefined ? null : line.slice(name.length + 1);
}

/** A complete, honest probe output. */
function confirmedRaw(extra = ''): string {
  return [
    'read-only state probe',
    '  read_only_transaction=server_confirmed',
    '  database=postgres',
    '  supabase_migrations_exists=true',
    '  migration_history=3 rows from supabase_migrations.schema_migrations',
    '  app_tables=12',
    '  probe_status=ok',
    extra,
  ].join('\n');
}

describe('probe evidence requires an exact server confirmation', () => {
  itBash('accepts one exact server_confirmed line with probe_status=ok and exit 0', () => {
    const result = run(confirmedRaw());
    expect(result.status, result.output).toBe(0);
    expect(field(result.evidence, 'read_only_transaction')).toBe('server_confirmed');
    expect(field(result.evidence, 'probe_status')).toBe('ok');
    expect(field(result.evidence, 'probe_completion')).toBe('ok');
    expect(field(result.evidence, 'target_project_reference')).toBe('stagingref0000000001');
  });

  itBash('rejects the legacy `enforced` value instead of rewriting it', () => {
    const result = run(confirmedRaw().replace('read_only_transaction=server_confirmed', 'read_only_transaction=enforced'));
    expect(field(result.evidence, 'read_only_transaction')).toBe('not_confirmed');
    expect(field(result.evidence, 'probe_completion')).toBe('failed');
    expect(result.evidence).not.toContain('server_confirmed');
  });

  itBash('rejects `on`, `off`, `not_confirmed` and an unexpected value', () => {
    for (const value of ['on', 'off', 'not_confirmed', 'server_confirmed_ish']) {
      const result = run(confirmedRaw().replace('read_only_transaction=server_confirmed', `read_only_transaction=${value}`));
      expect(field(result.evidence, 'read_only_transaction'), value).toBe('not_confirmed');
      expect(field(result.evidence, 'probe_completion'), value).toBe('failed');
    }
  });

  itBash('rejects a missing confirmation line', () => {
    const raw = confirmedRaw()
      .split('\n')
      .filter((line) => !line.includes('read_only_transaction='))
      .join('\n');
    const result = run(raw);
    expect(field(result.evidence, 'read_only_transaction')).toBe('not_confirmed');
    expect(field(result.evidence, 'probe_completion')).toBe('failed');
  });

  itBash('rejects duplicate confirmation lines', () => {
    const result = run(`${confirmedRaw()}\n  read_only_transaction=server_confirmed`);
    expect(field(result.evidence, 'read_only_transaction')).toBe('not_confirmed');
    expect(field(result.evidence, 'probe_completion')).toBe('failed');
  });

  itBash('rejects a zero exit status with a failed probe status', () => {
    const result = run(confirmedRaw().replace('probe_status=ok', 'probe_status=error'));
    expect(field(result.evidence, 'probe_status')).toBe('error');
    expect(field(result.evidence, 'probe_completion')).toBe('failed');
  });

  itBash('rejects duplicate probe_status lines', () => {
    const result = run(`${confirmedRaw()}\n  probe_status=ok`);
    expect(field(result.evidence, 'probe_completion')).toBe('failed');
  });

  itBash('rejects a failed probe even when its output looks confirmed', () => {
    const result = run(confirmedRaw(), 6);
    expect(field(result.evidence, 'probe_completion')).toBe('failed');
  });

  itBash('records the sanitized category of an unconfirmed read-only session', () => {
    const raw = [
      'read-only state probe',
      '  probe_status=error',
      '  error_category=read_only_not_confirmed',
      '  error_detail=transaction_read_only=off',
    ].join('\n');
    const result = run(raw, 6);
    expect(field(result.evidence, 'error_category')).toBe('read_only_not_confirmed');
    expect(field(result.evidence, 'probe_completion')).toBe('failed');
  });
});

describe('probe evidence is sanitized', () => {
  itBash('copies only allowlisted fields and no inventory', () => {
    const raw = confirmedRaw(
      [
        '  jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres',
        '  password=sup3rs3cret',
        '  table=public.profiles',
        '  at org.postgresql.Driver.connect(Driver.java:1)',
      ].join('\n'),
    );
    const result = run(raw);
    expect(result.evidence).not.toContain('pooler.supabase.com');
    expect(result.evidence).not.toContain('sup3rs3cret');
    expect(result.evidence).not.toContain('public.profiles');
    expect(result.evidence).not.toContain('org.postgresql');
    expect(result.evidence).not.toContain('app_tables=12');
    expect(result.evidence).toContain('database=postgres');
  });

  itBash('never echoes the raw output on stdout', () => {
    const result = run(confirmedRaw('  password=sup3rs3cret'));
    expect(result.output).not.toContain('sup3rs3cret');
    expect(result.output).toContain('probe_completion=ok');
  });
});
