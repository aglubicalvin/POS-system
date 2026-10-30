/**
 * In-memory stand-in for `expo-secure-store`.
 *
 * The admin test suite runs in Node against pure modules. `phase43.ts` reaches the
 * Supabase client, which reaches the SecureStore-backed session adapter, which pulls
 * `react-native` — a Flow-syntax package Node cannot parse. Aliasing the native
 * module to this stub (see vitest.config.ts) keeps the real validation code under
 * test without pretending the keystore exists.
 */
const store = new Map<string, string>();

export async function getItemAsync(key: string): Promise<string | null> {
  return store.get(key) ?? null;
}

export async function setItemAsync(key: string, value: string): Promise<void> {
  store.set(key, value);
}

export async function deleteItemAsync(key: string): Promise<void> {
  store.delete(key);
}
