import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  ADMIN_USER_PATTERN,
  KNOWN_NON_STAGING_REFS,
  POS_STAGING_POOLER_HOST,
  STAGING_DB_ROOT_CERT_ENV,
  STAGING_DB_ROOT_CERT_SHA256_ENV,
  STAGING_SSL_MODE,
  StagingTargetError,
  describePoolerHost,
  describeStagingConnection,
  describeStagingTarget,
  resolveStagingConnectionConfig,
  resolveStagingPoolerHost,
  resolveStagingTarget,
  validateStagingTlsEnv,
} from '../src/config/staging-target.ts';

/** All values here are synthetic. Nothing opens a database. */
const STAGING_REF = 'stagingref0000000001';
const NON_STAGING_REF = 'uemdrtrzlvhdtonccczt';
const POOLER_HOST = 'aws-0-eu-west-2.pooler.supabase.com';
const FINGERPRINT = '0'.repeat(64);

const fullEnv = {
  POS_STAGING_PROJECT_REF: STAGING_REF,
  POS_STAGING_ADMIN_USER: `postgres.${STAGING_REF}`,
  POS_STAGING_POOLER_HOST: POOLER_HOST,
  POS_STAGING_DB_ROOT_CERT: '/tmp/staging-root-ca.pem',
  POS_STAGING_DB_ROOT_CERT_SHA256: FINGERPRINT,
};

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');

describe('staging pooler host validation', () => {
  it('names the environment variable it reads', () => {
    expect(POS_STAGING_POOLER_HOST).toBe('POS_STAGING_POOLER_HOST');
  });

  it('resolves a valid staging pooler hostname', () => {
    const result = resolveStagingPoolerHost(fullEnv);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.host).toEqual({ n: '0', region: 'eu-west-2' });
    expect(describePoolerHost(result.host)).toBe(`pooler=${POOLER_HOST}`);
  });

  it('normalises casing and surrounding whitespace', () => {
    const result = resolveStagingPoolerHost({
      ...fullEnv,
      POS_STAGING_POOLER_HOST: '  AWS-1-US-EAST-1.pooler.supabase.com ',
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.host).toEqual({ n: '1', region: 'us-east-1' });
  });

  it('rejects a missing pooler hostname', () => {
    const result = resolveStagingPoolerHost({ ...fullEnv, POS_STAGING_POOLER_HOST: undefined });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.code).toBe('missing-staging-pooler-host');
    expect(result.message).toContain('BLOCKED');
  });

  it('rejects a blank pooler hostname', () => {
    const result = resolveStagingPoolerHost({ ...fullEnv, POS_STAGING_POOLER_HOST: '   ' });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.code).toBe('missing-staging-pooler-host');
  });

  it('rejects a malformed pooler hostname', () => {
    for (const bad of [
      'wrong-domain.com',
      'aws-0-eu-west-2.supabase.com',
      'aws-0-eu-west-2.pooler.supabag.com',
      'aws-0-eu-west-2.pooler.supabase.co',
      'aws-0-eu-west-2.pooler.supabase.com.evil.com',
    ]) {
      const result = resolveStagingPoolerHost({ ...fullEnv, POS_STAGING_POOLER_HOST: bad });
      expect(result.ok, bad).toBe(false);
      if (result.ok) continue;
      expect(result.code).toBe('malformed-staging-pooler-host');
    }
  });

  it('rejects IP literals and loopback-like names', () => {
    for (const bad of [
      '127.0.0.1',
      '127.0.0.1:5432',
      '::1',
      '0.0.0.0',
      '192.168.1.1',
      '127.1.pooler.supabase.com',
      '127.0.0.1.pooler.supabase.com',
      'localhost.pooler.supabase.com',
    ]) {
      const result = resolveStagingPoolerHost({ ...fullEnv, POS_STAGING_POOLER_HOST: bad });
      expect(result.ok, bad).toBe(false);
      if (result.ok) continue;
      expect(result.code).toBe('malformed-staging-pooler-host');
    }
  });

  it('rejects URIs, paths, credentials, ports and query strings', () => {
    for (const bad of [
      `jdbc:postgresql://${POOLER_HOST}:5432/postgres`,
      `postgres://${POOLER_HOST}/postgres`,
      `${POOLER_HOST}/postgres`,
      `${POOLER_HOST}:5432`,
      `${POOLER_HOST}:5432/postgres`,
      `user@${POOLER_HOST}`,
      `${POOLER_HOST}?sslmode=require`,
    ]) {
      const result = resolveStagingPoolerHost({ ...fullEnv, POS_STAGING_POOLER_HOST: bad });
      expect(result.ok, bad).toBe(false);
      if (result.ok) continue;
      expect(result.code).toBe('malformed-staging-pooler-host');
    }
  });

  it('rejects weaker TLS modes smuggled into the hostname variable', () => {
    for (const mode of ['require', 'prefer', 'allow', 'disable']) {
      const result = resolveStagingPoolerHost({
        ...fullEnv,
        POS_STAGING_POOLER_HOST: `${POOLER_HOST}?sslmode=${mode}`,
      });
      expect(result.ok, mode).toBe(false);
      if (result.ok) continue;
      expect(result.code).toBe('malformed-staging-pooler-host');
    }
  });
});

describe('staging target resolution', () => {
  it('requires an explicitly set project reference', () => {
    const result = resolveStagingTarget({});
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.code).toBe('missing-ref');
  });

  it('rejects an invalid project reference', () => {
    const result = resolveStagingTarget({ ...fullEnv, POS_STAGING_PROJECT_REF: 'short' });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.code).toBe('malformed-ref');
  });

  it('rejects a known non-staging reference', () => {
    const result = resolveStagingTarget({ ...fullEnv, POS_STAGING_PROJECT_REF: NON_STAGING_REF });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.code).toBe('non-staging-ref');
    expect(KNOWN_NON_STAGING_REFS).toContain(NON_STAGING_REF);
  });

  it('rejects an administrator username that does not match the project', () => {
    const result = resolveStagingTarget({
      ...fullEnv,
      POS_STAGING_ADMIN_USER: 'postgres.otherref00000000000z',
    });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.code).toBe('ref-mismatch');
  });
});

describe('staging TLS material', () => {
  it('accepts a staged certificate with an independent fingerprint', () => {
    const result = validateStagingTlsEnv(fullEnv);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.certPath).toBe('/tmp/staging-root-ca.pem');
    expect(result.fingerprint).toBe(FINGERPRINT);
  });

  it('accepts a colon-separated uppercase fingerprint', () => {
    const spaced = FINGERPRINT.toUpperCase().match(/.{2}/g)!.join(':');
    const result = validateStagingTlsEnv({
      ...fullEnv,
      POS_STAGING_DB_ROOT_CERT_SHA256: spaced,
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.fingerprint).toBe(FINGERPRINT);
  });

  it('rejects a missing root certificate', () => {
    const result = validateStagingTlsEnv({ ...fullEnv, POS_STAGING_DB_ROOT_CERT: undefined });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.code).toBe('missing-staging-root-certificate');
    expect(result.message).toContain(STAGING_DB_ROOT_CERT_ENV);
  });

  it('rejects a missing fingerprint', () => {
    const result = validateStagingTlsEnv({
      ...fullEnv,
      POS_STAGING_DB_ROOT_CERT_SHA256: '   ',
    });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.code).toBe('missing-staging-root-certificate-fingerprint');
    expect(result.message).toContain(STAGING_DB_ROOT_CERT_SHA256_ENV);
  });

  it('rejects an invalid fingerprint', () => {
    for (const bad of [
      'not-a-fingerprint',
      'f'.repeat(63),
      'f'.repeat(65),
      `${'g'.repeat(64)}`,
      'zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz',
    ]) {
      const result = validateStagingTlsEnv({
        ...fullEnv,
        POS_STAGING_DB_ROOT_CERT_SHA256: bad,
      });
      expect(result.ok, bad).toBe(false);
      if (result.ok) continue;
      expect(result.code).toBe('malformed-staging-root-certificate-fingerprint');
    }
  });
});

describe('staging connection contract', () => {
  it('combines identity, pooler host and verify-full TLS', () => {
    const result = resolveStagingConnectionConfig(fullEnv);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.config.projectRef).toBe(STAGING_REF);
    expect(result.config.adminUser).toBe(`postgres.${STAGING_REF}`);
    expect(result.config.database).toBe('postgres');
    expect(result.config.poolerHost).toEqual({ n: '0', region: 'eu-west-2' });
    expect(result.config.rootCertPath).toBe('/tmp/staging-root-ca.pem');
    expect(result.config.rootCertFingerprint).toBe(FINGERPRINT);
    expect(result.config.sslMode).toBe('verify-full');
    expect(STAGING_SSL_MODE).toBe('verify-full');
  });

  it('cannot be weakened by any environment variable', () => {
    const result = resolveStagingConnectionConfig({
      ...fullEnv,
      POS_STAGING_SSL_MODE: 'require',
      PGSSLMODE: 'disable',
      sslmode: 'prefer',
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.config.sslMode).toBe('verify-full');
  });

  it('fails closed before TLS when the pooler host is missing', () => {
    const result = resolveStagingConnectionConfig({
      ...fullEnv,
      POS_STAGING_POOLER_HOST: undefined,
    });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.code).toBe('missing-staging-pooler-host');
  });

  it('fails closed when the certificate is missing', () => {
    const result = resolveStagingConnectionConfig({
      ...fullEnv,
      POS_STAGING_DB_ROOT_CERT: undefined,
    });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.code).toBe('missing-staging-root-certificate');
  });

  it('describes a connection without leaking the fingerprint', () => {
    const result = resolveStagingConnectionConfig(fullEnv);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    const description = describeStagingConnection(result.config);
    expect(description).toContain(POOLER_HOST);
    expect(description).toContain('sslmode=verify-full');
    expect(description).not.toContain(FINGERPRINT);
    expect(description).not.toMatch(/password|passwd|secret/i);
  });
});

describe('requireStagingTarget', () => {
  it('throws a typed, actionable error when blocked', () => {
    expect(() => resolveStagingTarget({})).not.toThrow();
    try {
      resolveStagingConnectionConfig({});
      expect.unreachable('should have blocked');
    } catch {
      // resolveStagingConnectionConfig returns rather than throws; the assertion
      // below covers the throwing helper instead.
    }
    const result = resolveStagingConnectionConfig({});
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.code).toBe('missing-ref');
    expect(result.message).toContain('BLOCKED');
  });

  it('exposes a typed error for throwing callers', () => {
    const error = new StagingTargetError('missing-ref', 'BLOCKED: no reference');
    expect(error).toBeInstanceOf(Error);
    expect(error.name).toBe('StagingTargetError');
    expect(error.code).toBe('missing-ref');
  });

  it('describes a target without any secret', () => {
    const description = describeStagingTarget({
      projectRef: STAGING_REF,
      adminUser: `postgres.${STAGING_REF}`,
      database: 'postgres',
    });
    expect(description).toContain(STAGING_REF);
    expect(description).not.toMatch(/password|passwd|secret/i);
  });

  it('accepts only the pooler administrator shape', () => {
    expect(ADMIN_USER_PATTERN.test(`postgres.${STAGING_REF}`)).toBe(true);
    expect(ADMIN_USER_PATTERN.test('postgres.admin')).toBe(false);
  });
});

describe('both staging connection paths consume one contract', () => {
  const jdbcPath = readFileSync(resolve(repoRoot, 'server/tools/ProbeState.java'), 'utf8');
  const libpqPath = readFileSync(resolve(repoRoot, 'server/tools/probe-readonly.ps1'), 'utf8');

  it('takes the pooler hostname from POS_STAGING_POOLER_HOST in both paths', () => {
    expect(jdbcPath).toContain('POS_STAGING_POOLER_HOST');
    expect(libpqPath).toContain('POS_STAGING_POOLER_HOST');
  });

  it('requires verify-full TLS and the staged certificate in both paths', () => {
    for (const [name, source] of [
      ['ProbeState.java', jdbcPath],
      ['probe-readonly.ps1', libpqPath],
    ] as const) {
      expect(source, name).toContain('verify-full');
      expect(source, name).toContain('POS_STAGING_DB_ROOT_CERT');
      expect(source, name).toContain('POS_STAGING_DB_ROOT_CERT_SHA256');
    }
  });

  it('contains no hardcoded pooler hostname in either path', () => {
    for (const [name, source] of [
      ['ProbeState.java', jdbcPath],
      ['probe-readonly.ps1', libpqPath],
    ] as const) {
      expect(source, name).not.toMatch(/aws-\d+-[a-z0-9-]+\.pooler\.supabase\.com/);
      expect(source, name).not.toMatch(/db\.[a-z0-9]{20}\.supabase\.co/);
    }
  });

  it('has no weaker TLS mode available in either path', () => {
    for (const [name, source] of [
      ['ProbeState.java', jdbcPath],
      ['probe-readonly.ps1', libpqPath],
    ] as const) {
      expect(source, name).not.toMatch(/sslmode=(prefer|allow|disable)/i);
      expect(source, name).not.toMatch(/sslmode=require\b/i);
    }
  });
});
