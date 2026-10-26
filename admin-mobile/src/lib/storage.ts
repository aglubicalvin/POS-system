import * as SecureStore from 'expo-secure-store';

/**
 * supabase-js storage adapter backed by the device keystore/keychain.
 *
 * Session tokens live only here (never AsyncStorage, never JS memory across
 * restarts) per the security requirement: secure device storage for session
 * tokens, remote revocation handled by the backend `device_sessions` /
 * profile flags, see supabase/migrations/0001_admin_foundation.sql.
 */
export const secureStorageAdapter = {
  getItem: (key: string): Promise<string | null> => SecureStore.getItemAsync(key),
  setItem: (key: string, value: string): Promise<void> => SecureStore.setItemAsync(key, value),
  removeItem: (key: string): Promise<void> => SecureStore.deleteItemAsync(key),
};
