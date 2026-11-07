import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

/**
 * Staging connection contract, workflow side.
 *
 * The certificate is materialized on the runner by one script and consumed by
 * both staging workflows through the same four variables. These tests read the
 * workflow files and execute the materialization script against synthetic
 * material; no workflow is dispatched and no network or database is touched.
 */
const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..');
const workflowsDir = resolve(repoRoot, '.github/workflows');
const materializeScript = resolve(repoRoot, 'server/tools/materialize-staging-root-cert.sh');
const read = (name: string) => readFileSync(resolve(workflowsDir, name), 'utf8');

const IDENTITY = 'probe-staging-identity.yml';
const ROLE = 'probe-staging.yml';

describe('both staging workflows receive the same connection contract', () => {
  const workflows = [IDENTITY, ROLE];

  it.each(workflows)('%s materializes the CA instead of reading a configured path', (name) => {
    const yml = read(name);
    expect(yml).toContain('server/tools/materialize-staging-root-cert.sh');
    expect(yml).toContain('POS_STAGING_DB_ROOT_CERT_B64: ${{ vars.POS_STAGING_DB_ROOT_CERT_B64 }}');
    expect(yml).toContain(
      'POS_STAGING_DB_ROOT_CERT_SHA256: ${{ vars.POS_STAGING_DB_ROOT_CERT_SHA256 }}',
    );
    // A path stored in configuration cannot exist on an ephemeral runner.
    expect(yml).not.toMatch(/POS_STAGING_DB_ROOT_CERT:\s*\$\{\{\s*vars\./);
  });

  it.each(workflows)('%s consumes the validated pooler hostname', (name) => {
    const yml = read(name);
    expect(yml).toContain('POS_STAGING_POOLER_HOST: ${{ vars.POS_STAGING_POOLER_HOST }}');
    // Never a default, never a fallback, never a literal host.
    expect(yml).not.toMatch(/\|\|\s*['"]?aws-/);
    expect(yml).not.toMatch(/aws-\d+-[a-z0-9-]+\.pooler\.supabase\.com/);
  });

  it.each(workflows)('%s removes the materialized CA on success and failure', (name) => {
    const yml = read(name);
    const cleanup = yml.slice(yml.indexOf('Remove staged certificate'));
    expect(cleanup, 'cleanup step missing').not.toBe('');
    expect(cleanup).toContain('if: always()');
    expect(cleanup).toContain('rm -f -- "${RUNNER_TEMP:-/tmp}/staging-root-ca.pem"');
  });

  it.each(workflows)('%s materializes the CA before anything connects', (name) => {
    const yml = read(name);
    const materializeIdx = yml.indexOf('server/tools/materialize-staging-root-cert.sh');
    const connectIdx = Math.min(
      ...[yml.indexOf('execProbeState'), yml.indexOf('probe-readonly.ps1')].filter((i) => i >= 0),
    );
    expect(materializeIdx).toBeGreaterThan(-1);
    expect(connectIdx).toBeGreaterThan(-1);
    expect(materializeIdx).toBeLessThan(connectIdx);
  });

  it('builds the identity CHECK_URL from the configured pooler hostname only', () => {
    const yml = read(IDENTITY);
    expect(yml).toContain('CHECK_URL: jdbc:postgresql://${{ vars.POS_STAGING_POOLER_HOST }}:5432/postgres');
  });

  it('passes the shared staging variables to the PowerShell role-creation path', () => {
    const yml = read(ROLE);
    for (const variable of [
      'POS_STAGING_PROJECT_REF: ${{ vars.POS_STAGING_PROJECT_REF }}',
      'POS_STAGING_POOLER_HOST: ${{ vars.POS_STAGING_POOLER_HOST }}',
      'POS_STAGING_DB_ROOT_CERT_SHA256: ${{ vars.POS_STAGING_DB_ROOT_CERT_SHA256 }}',
      'PROBE_ADMIN_USER: ${{ vars.PROBE_ADMIN_USER }}',
    ]) {
      expect(yml).toContain(variable);
    }
    // The apply gate survives: no -Apply unless the dispatch input says so.
    expect(yml).toContain("$apply = '${{ inputs.apply }}' -eq 'true'");
    expect(yml).toContain('if ($apply) { ./server/tools/probe-readonly.ps1 -Apply }');
  });
});

/** Locate a POSIX bash, as the workflow tests do. */
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

/**
 * Behavioural coverage of the materialization script: it is the only thing that
 * turns the configured CA into a file the Java path can use, so its refusal
 * paths matter as much as its success path.
 */
describe('staged root certificate materialization', () => {
  let dir: string;
  let runnerTemp: string;
  let envFile: string;

  beforeEach(() => {
    dir = mkdtempSync(join(tmpdir(), 'materialize-ca-'));
    runnerTemp = join(dir, 'runner-temp');
    envFile = join(dir, 'github-env');
    mkdirSync(runnerTemp, { recursive: true });
    writeFileSync(envFile, '', 'utf8');
  });

  afterEach(() => {
    rmSync(dir, { recursive: true, force: true });
  });

  /** A synthetic PEM plus the SHA-256 of its DER encoding. */
  function certificate(seed = 1): { pem: string; base64: string; fingerprint: string } {
    const der = Buffer.alloc(48);
    for (let i = 0; i < der.length; i++) der[i] = (i + seed) & 0xff;
    const pem = `-----BEGIN CERTIFICATE-----\n${der.toString('base64')}\n-----END CERTIFICATE-----\n`;
    const fingerprint = createHash('sha256').update(der).digest('hex');
    return { pem, base64: Buffer.from(pem, 'utf8').toString('base64'), fingerprint };
  }

  function run(env: Record<string, string>) {
    return spawnSync(bash as string, [materializeScript], {
      encoding: 'utf8',
      timeout: 60_000,
      env: {
        PATH: process.env.PATH ?? '',
        HOME: process.env.HOME ?? '',
        RUNNER_TEMP: runnerTemp,
        GITHUB_ENV: envFile,
        ...env,
      },
    });
  }

  const itBash = bash ? it : it.skip;

  itBash('writes a private file and exports only its path', () => {
    const { base64, fingerprint } = certificate();
    const result = run({
      POS_STAGING_DB_ROOT_CERT_B64: base64,
      POS_STAGING_DB_ROOT_CERT_SHA256: fingerprint,
    });

    expect(result.status, `${result.stdout}${result.stderr}`).toBe(0);
    const certPath = join(runnerTemp, 'staging-root-ca.pem');
    expect(existsSync(certPath)).toBe(true);

    // The runner is Linux, so the exported path uses forward slashes; on a
    // Windows host the temp path keeps its native prefix, so compare normalized.
    const exported = readFileSync(envFile, 'utf8').trim();
    expect(exported.startsWith('POS_STAGING_DB_ROOT_CERT=')).toBe(true);
    expect(normalize(exported.slice('POS_STAGING_DB_ROOT_CERT='.length))).toBe(normalize(certPath));

    // Nothing secret is printed: not the certificate, not the base64, not the
    // fingerprint.
    const printed = `${result.stdout}${result.stderr}`;
    expect(printed).not.toContain('BEGIN CERTIFICATE');
    expect(printed).not.toContain(base64.slice(0, 24));
    expect(printed).not.toContain(fingerprint);
  });

  itBash('refuses when the fingerprint does not match the certificate', () => {
    const { base64 } = certificate();
    const result = run({
      POS_STAGING_DB_ROOT_CERT_B64: base64,
      POS_STAGING_DB_ROOT_CERT_SHA256: 'a'.repeat(64),
    });

    expect(result.status).not.toBe(0);
    expect(`${result.stdout}${result.stderr}`).toContain('does not match');
    expect(readFileSync(envFile, 'utf8')).toBe('');
    // A refused certificate must not be left behind for a later step to trust.
    expect(existsSync(join(runnerTemp, 'staging-root-ca.pem'))).toBe(false);
  });

  itBash('refuses missing material instead of falling back', () => {
    const cases: Record<string, string>[] = [
      { POS_STAGING_DB_ROOT_CERT_SHA256: 'a'.repeat(64) },
      { POS_STAGING_DB_ROOT_CERT_B64: 'AAAA' },
      {},
    ];
    for (const env of cases) {
      const result = run(env);
      expect(result.status, JSON.stringify(env)).not.toBe(0);
      expect(`${result.stdout}${result.stderr}`).toContain('::error::');
      expect(readFileSync(envFile, 'utf8')).toBe('');
    }
  });

  itBash('refuses a payload that is not a PEM certificate', () => {
    const junk = Buffer.from('not a certificate at all', 'utf8').toString('base64');
    const result = run({
      POS_STAGING_DB_ROOT_CERT_B64: junk,
      POS_STAGING_DB_ROOT_CERT_SHA256: 'b'.repeat(64),
    });
    expect(result.status).not.toBe(0);
    expect(`${result.stdout}${result.stderr}`).toContain('PEM');
    expect(existsSync(join(runnerTemp, 'staging-root-ca.pem'))).toBe(false);
  });

  itBash('accepts a fingerprint written with colons or upper case', () => {
    const { base64, fingerprint } = certificate(7);
    const spaced = fingerprint.toUpperCase().match(/.{2}/g)!.join(':');
    const result = run({
      POS_STAGING_DB_ROOT_CERT_B64: base64,
      POS_STAGING_DB_ROOT_CERT_SHA256: spaced,
    });
    expect(result.status, `${result.stdout}${result.stderr}`).toBe(0);
    expect(existsSync(join(runnerTemp, 'staging-root-ca.pem'))).toBe(true);
  });
});
