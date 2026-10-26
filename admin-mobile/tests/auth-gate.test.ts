import { describe, expect, it } from 'vitest';
import { evaluateAdminAccess, type GateResult, type AssuranceLevel } from '../src/lib/auth-gate';

const adminProfile = { role: 'admin', is_active: true, mfa_required: false };
const adminMfaProfile = { role: 'admin', is_active: true, mfa_required: true };

function run(profile: Parameters<typeof evaluateAdminAccess>[0]['profile'], aal: AssuranceLevel | null): GateResult {
  return evaluateAdminAccess({ profile, aal });
}

describe('evaluateAdminAccess — unauthorized users are rejected', () => {
  it('denies when no profile row exists (user not recorded as admin)', () => {
    expect(run(null, 'aal2')).toEqual({
      access: 'denied',
      reason: 'no administrator profile on record for this account',
    });
  });

  it('denies a cashier account even with an authenticated session', () => {
    const result = run({ role: 'cashier', is_active: true, mfa_required: false }, 'aal2');
    expect(result.access).toBe('denied');
  });

  it('denies a manager account even with an authenticated session', () => {
    const result = run({ role: 'manager', is_active: true, mfa_required: false }, 'aal2');
    expect(result.access).toBe('denied');
  });

  it('denies a deactivated admin', () => {
    const result = run({ role: 'admin', is_active: false, mfa_required: false }, 'aal2');
    expect(result.access).toBe('denied');
  });

  it('fails closed when the backend-verified aal is absent (stale/revoked session)', () => {
    const result = run(adminProfile, null);
    expect(result).toEqual({
      access: 'denied',
      reason: 'session assurance could not be verified',
    });
  });
});

describe('evaluateAdminAccess — MFA requirement', () => {
  it('demands MFA before granting access when mfa_required is set', () => {
    expect(run(adminMfaProfile, 'aal1')).toEqual({
      access: 'mfa_required',
      reason: 'administrator sign-in requires the second factor',
    });
  });

  it('grants after aal2 for an MFA-required admin', () => {
    expect(run(adminMfaProfile, 'aal2')).toEqual({ access: 'granted' });
  });

  it('grants immediately for an MFA-exempt admin', () => {
    expect(run(adminProfile, 'aal1')).toEqual({ access: 'granted' });
  });
});
