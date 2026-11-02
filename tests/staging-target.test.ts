import { describe, expect, it } from 'vitest';
import {
  ADMIN_USER_PATTERN,
  KNOWN_NON_STAGING_REFS,
  StagingTargetError,
  describeStagingTarget,
  requireStagingTarget,
  resolveStagingTarget,
} from '../src/config/staging-target.ts';

/**
 * All values here are synthetic. No real project reference appears in this
 * file, and nothing in these tests opens a connection.
 */
const STAGING_REF = 'stagingref0000000001';
const DEV_REF = 'devref00000000000001';

const full = {
  POS_STAGING_PROJECT_REF: STAGING_REF,
  POS_STAGING_ADMIN_USER: `postgres.${STAGING_REF}`,
};

describe('staging target resolution', () => {
  it('resolves a fully configured staging target', () => {
    const result = resolveStagingTarget(full);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.target).toEqual({
      projectRef: STAGING_REF,
      adminUser: `postgres.${STAGING_REF}`,
      database: 'postgres',
    });
  });

  it('fails closed when POS_STAGING_PROJECT_REF is absent', () => {
    const result = resolveStagingTarget({ POS_STAGING_ADMIN_USER: `postgres.${STAGING_REF}` });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.code).toBe('missing-ref');
    expect(result.message).toContain('POS_STAGING_PROJECT_REF');
    expect(result.message).toContain('BLOCKED');
  });

  it('fails closed on a blank POS_STAGING_PROJECT_REF', () => {
    const result = resolveStagingTarget({ ...full, POS_STAGING_PROJECT_REF: '   ' });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.code).toBe('missing-ref');
  });

  it('rejects a malformed project reference', () => {
    for (const bad of ['short', 'HAS-UPPERCASE-0000001', 'has_underscore_00001', 'x'.repeat(21)]) {
      const result = resolveStagingTarget({ ...full, POS_STAGING_PROJECT_REF: bad });
      expect(result.ok, bad).toBe(false);
      if (result.ok) continue;
      expect(result.code).toBe('malformed-ref');
    }
  });

  it('rejects a username whose suffix is the wrong project', () => {
    const result = resolveStagingTarget({
      ...full,
      POS_STAGING_ADMIN_USER: `postgres.${DEV_REF}`,
    });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.code).toBe('ref-mismatch');
    expect(result.message).toContain(DEV_REF);
    expect(result.message).toContain(STAGING_REF);
  });

  it('rejects a malformed administrator username', () => {
    for (const bad of ['postgres', 'postgres.', 'admin.postgresref00000001', 'postgres.NotALowerRef', '']) {
      const result = resolveStagingTarget({ ...full, POS_STAGING_ADMIN_USER: bad });
      expect(result.ok, bad).toBe(false);
      if (result.ok) continue;
      expect(['missing-admin-user', 'malformed-admin-user']).toContain(result.code);
    }
  });

  it('rejects a missing administrator username', () => {
    const result = resolveStagingTarget({ POS_STAGING_PROJECT_REF: STAGING_REF });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.code).toBe('missing-admin-user');
  });

  it('rejects an accidental development target', () => {
    const devRef = KNOWN_NON_STAGING_REFS[0]!;
    const result = resolveStagingTarget({
      POS_STAGING_PROJECT_REF: devRef,
      POS_STAGING_ADMIN_USER: `postgres.${devRef}`,
    });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.code).toBe('non-staging-ref');
    expect(result.message).toContain('development');
  });

  it('rejects every known non-staging reference (development and former verify)', () => {
    expect(KNOWN_NON_STAGING_REFS.length).toBeGreaterThanOrEqual(2);
    for (const ref of KNOWN_NON_STAGING_REFS) {
      const result = resolveStagingTarget({
        POS_STAGING_PROJECT_REF: ref,
        POS_STAGING_ADMIN_USER: `postgres.${ref}`,
      });
      expect(result.ok, ref).toBe(false);
      if (result.ok) continue;
      expect(result.code).toBe('non-staging-ref');
    }
  });

  it('honours an overridden non-staging deny list', () => {
    const result = resolveStagingTarget({ ...full, POS_NON_STAGING_REFS: ` ${STAGING_REF} , other ` });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.code).toBe('non-staging-ref');
  });

  it('rejects the shared pooler username when it names another project', () => {
    // The hostname is identical for every project in the region, so only the
    // username distinguishes them; a mismatched suffix must block.
    const result = resolveStagingTarget({
      POS_STAGING_PROJECT_REF: STAGING_REF,
      POS_STAGING_ADMIN_USER: 'postgres.otherref00000000000z',
    });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.code).toBe('ref-mismatch');
  });

  it('rejects an empty environment entirely', () => {
    const result = resolveStagingTarget({});
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.code).toBe('missing-ref');
  });

  it('never falls back to a default reference', () => {
    const result = resolveStagingTarget({ POS_STAGING_ADMIN_USER: 'postgres.stagingref0000000001' });
    expect(result.ok).toBe(false);
    // A blocked result carries no target, so no caller can accidentally connect.
    expect('target' in result).toBe(false);
  });
});

describe('requireStagingTarget', () => {
  it('returns the target when configured', () => {
    expect(requireStagingTarget(full).projectRef).toBe(STAGING_REF);
  });

  it('throws a typed, actionable error when blocked', () => {
    expect(() => requireStagingTarget({})).toThrow(StagingTargetError);
    try {
      requireStagingTarget({});
      expect.unreachable('should have thrown');
    } catch (error) {
      expect(error).toBeInstanceOf(StagingTargetError);
      const typed = error as StagingTargetError;
      expect(typed.code).toBe('missing-ref');
      expect(typed.message).toContain('BLOCKED');
    }
  });
});

describe('helpers', () => {
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
