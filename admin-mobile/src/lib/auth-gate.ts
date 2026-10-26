/**
 * Pure server-verified administrator access gate.
 *
 * The profile shape here is what the app READS BACK from the database — not
 * what the client claims about itself. `profiles.role` is served to the client
 * only because the RLS policy lets a user read exactly their own row (plus
 * admin rows), so these values are produced by the backend, not trusted from
 * the device. Unauthorized users get `denied` no matter which screen they
 * navigate to or which API they call: every screen in the (admin) group runs
 * this gate, and the database itself refuses their queries.
 */

export interface AdminProfile {
  role: string;
  is_active: boolean;
  mfa_required: boolean;
}

export type AssuranceLevel = 'aal1' | 'aal2';

export type GateResult =
  | { access: 'granted' }
  | { access: 'mfa_required'; reason: string }
  | { access: 'denied'; reason: string };

export function evaluateAdminAccess(input: {
  profile: AdminProfile | null;
  aal: AssuranceLevel | null;
}): GateResult {
  if (!input.profile) {
    return { access: 'denied', reason: 'no administrator profile on record for this account' };
  }
  if (!input.profile.role) return { access: 'denied', reason: 'no role on record' };
  if (!input.profile.is_active) {
    return { access: 'denied', reason: 'this account is deactivated' };
  }
  if (input.profile.role !== 'admin') {
    return { access: 'denied', reason: `role '${input.profile.role}' is not authorized for the admin app` };
  }
  // Assurance level comes from the backend; null means we could not verify
  // it (stale or revoked session), so fail closed rather than grant.
  if (input.profile.mfa_required && input.aal !== 'aal2') {
    return { access: 'mfa_required', reason: 'administrator sign-in requires the second factor' };
  }
  if (input.aal === null) {
    return { access: 'denied', reason: 'session assurance could not be verified' };
  }
  return { access: 'granted' };
}
