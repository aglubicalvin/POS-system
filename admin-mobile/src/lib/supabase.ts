import { createClient, type SupabaseClient } from '@supabase/supabase-js';
import { secureStorageAdapter } from './storage';

/**
 * Single Supabase client for the admin app.
 *
 * - Reads config from EXPO_PUBLIC_* env vars (set per environment; see
 *   .env.example). The anon key is a PUBLIC key — it carries no privileges on
 *   its own; every real permission check is RLS + the `is_admin()` helper.
 * - Sessions persist to the secure keystore via the SecureStore adapter.
 * - No service-role key is ever referenced here; it must not be embedded in
 *   the mobile bundle.
 */
let client: SupabaseClient | null = null;

export function getSupabaseClient(): SupabaseClient {
  if (client) return client;
  const url = process.env.EXPO_PUBLIC_SUPABASE_URL;
  const anonKey = process.env.EXPO_PUBLIC_SUPABASE_ANON_KEY;
  if (!url || !anonKey) {
    throw new Error(
      'Supabase is not configured: set EXPO_PUBLIC_SUPABASE_URL and EXPO_PUBLIC_SUPABASE_ANON_KEY (see .env.example).',
    );
  }
  client = createClient(url, anonKey, {
    auth: {
      storage: secureStorageAdapter,
      persistSession: true,
      autoRefreshToken: true,
      detectSessionInUrl: false,
    },
  });
  return client;
}

/** Test seam: substitute a client (or clear with null). */
export function setSupabaseClient(next: SupabaseClient | null): void {
  client = next;
}
