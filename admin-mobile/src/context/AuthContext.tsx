import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import type { Session } from '@supabase/supabase-js';
import {
  evaluateAdminAccess,
  type AdminProfile,
  type AssuranceLevel,
  type GateResult,
} from '@/lib/auth-gate';
import { getSupabaseClient } from '@/lib/supabase';

/**
 * Auth state machine for the administrator app.
 *
 * Guarantee: `status === 'authorized'` is reached ONLY after the database
 * (through RLS) has handed back a profile row proving role='admin' AND, when
 * that row demands it, assurance level aal2 (TOTP MFA). Client-side role
 * checks are never the basis for this state; they only reflect what the
 * backend returned. A 'denied' result signs the user out immediately so an
 * unauthorized account cannot linger with a live session.
 */

export type AuthStatus = 'loading' | 'signed-out' | 'pending-mfa' | 'authorized' | 'denied';

export interface AdminAuthState {
  status: AuthStatus;
  profile: AdminProfile | null;
  gate: GateResult | null;
  /** Non-fatal status line for the UI (network down, etc.). */
  message: string | null;
}

interface AdminAuthApi extends AdminAuthState {
  signIn(email: string, password: string): Promise<{ mfaRequired: boolean }>;
  verifyMfa(code: string): Promise<void>;
  signOut(): Promise<void>;
}

const AdminAuthContext = createContext<AdminAuthApi | null>(null);

export async function fetchAdminProfile(): Promise<AdminProfile | null> {
  const supabase = getSupabaseClient();
  const { data: userData } = await supabase.auth.getUser();
  if (!userData?.user) return null;
  // RLS lets a user read exactly their own row; anything else returns null.
  const { data: profile, error } = await supabase
    .from('profiles')
    .select('role, is_active, mfa_required')
    .eq('id', userData.user.id)
    .maybeSingle<AdminProfile>();
  if (error) {
    // Includes RLS rejections and servers that cannot be reached — both mean
    // "not verifiably authorized", never "proceed".
    return null;
  }
  return profile;
}

async function currentAal(session: Session | null): Promise<AssuranceLevel | null> {
  if (!session) return null;
  const supabase = getSupabaseClient();
  const { data } = await supabase.auth.mfa.getAuthenticatorAssuranceLevel();
  if (!data) return null;
  return data.currentLevel === 'aal2' ? 'aal2' : 'aal1';
}

export function AdminAuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AdminAuthState>({
    status: 'loading',
    profile: null,
    gate: null,
    message: null,
  });

  // Restore a persisted secure session on cold start and re-run the gate:
  // a session that was revoked remotely is rejected here and by the server.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      let supabase: ReturnType<typeof getSupabaseClient>;
      try {
        supabase = getSupabaseClient();
      } catch (e) {
        // Unconfigured environment (missing EXPO_PUBLIC_SUPABASE_*): show a
        // clear message on the login screen rather than crashing.
        setState({
          status: 'signed-out',
          profile: null,
          gate: null,
          message: e instanceof Error ? e.message : 'backend configuration missing',
        });
        return;
      }
      const { data } = await supabase.auth.getSession();
      if (cancelled) return;
      const session = data.session ?? null;
      const profile = session ? await fetchAdminProfile() : null;
      if (cancelled) return;
      const aal = await currentAal(session);
      const gate = evaluateAdminAccess({ profile, aal });
      if (gate.access === 'granted') {
        setState({ status: 'authorized', profile, gate, message: null });
      } else if (gate.access === 'mfa_required') {
        setState({ status: 'pending-mfa', profile, gate, message: gate.reason });
      } else {
        if (session) await supabase.auth.signOut();
        setState({ status: 'signed-out', profile: null, gate, message: null });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  async function applyVerifiedGate(profile: AdminProfile | null, aal: AssuranceLevel | null): Promise<GateResult> {
    const gate = evaluateAdminAccess({ profile, aal });
    const supabase = getSupabaseClient();
    if (gate.access === 'granted') {
      setState({ status: 'authorized', profile, gate, message: null });
    } else if (gate.access === 'mfa_required') {
      setState({ status: 'pending-mfa', profile, gate, message: gate.reason });
    } else {
      await supabase.auth.signOut();
      setState({ status: 'denied', profile: null, gate, message: gate.reason });
    }
    return gate;
  }

  async function signIn(email: string, password: string): Promise<{ mfaRequired: boolean }> {
    const supabase = getSupabaseClient();
    const { data, error } = await supabase.auth.signInWithPassword({ email, password });
    if (error || !data.session) {
      // Supabase rate-limits and masks failures; surface the provider message.
      throw new AuthFlowError(error?.message ?? 'sign-in failed');
    }
    const profile = await fetchAdminProfile();
    if (!profile) {
      // Not recorded as an administrator — remove the session entirely so a
      // non-admin cannot carry a live token for any admin endpoint.
      await supabase.auth.signOut();
      setState((s) => ({
        ...s,
        status: 'denied',
        profile: null,
        gate: { access: 'denied', reason: 'no administrator profile on record for this account' },
        message: 'This account is not authorized as an administrator.',
      }));
      throw new AuthFlowError('not authorized: administrator account required');
    }
    const aal = await currentAal(data.session);
    const gate = await applyVerifiedGate(profile, aal);
    return {
      mfaRequired: gate.access === 'mfa_required',
    };
  }

  async function verifyMfa(code: string): Promise<void> {
    const supabase = getSupabaseClient();
    const { data: factorData, error: factorsError } = await supabase.auth.mfa.listFactors();
    if (factorsError) throw new AuthFlowError(factorsError.message);
    const totp = factorData?.totp.find((f) => f.status === 'verified');
    if (!totp) throw new AuthFlowError('no verified authenticator is registered for this account');
    const { data: challengeData, error: challengeError } = await supabase.auth.mfa.challenge({ factorId: totp.id });
    if (challengeError) throw new AuthFlowError(challengeError.message);
    const { error: verifyError } = await supabase.auth.mfa.verify({
      factorId: totp.id,
      challengeId: challengeData.id,
      code,
    });
    if (verifyError) throw new AuthFlowError(verifyError.message);

    const { data: sessionData } = await supabase.auth.getSession();
    const profile = await fetchAdminProfile();
    const aal = await currentAal(sessionData?.session ?? null);
    const gate = await applyVerifiedGate(profile, aal);
    if (gate.access !== 'granted') throw new AuthFlowError(gate.reason);
  }

  async function signOut(): Promise<void> {
    const supabase = getSupabaseClient();
    await supabase.auth.signOut();
    setState({ status: 'signed-out', profile: null, gate: null, message: null });
  }

  return (
    <AdminAuthContext.Provider
      value={{ ...state, signIn, verifyMfa, signOut }}
    >
      {children}
    </AdminAuthContext.Provider>
  );
}

export function useAdminAuth(): AdminAuthApi {
  const ctx = useContext(AdminAuthContext);
  if (!ctx) throw new Error('useAdminAuth must be used inside <AdminAuthProvider>');
  return ctx;
}

export class AuthFlowError extends Error {}
