import { defineConfig } from 'vitest/config';
import { fileURLToPath } from 'node:url';

// Runs in node against pure modules only (src/lib without React Native
// imports). This mirrors the parent project's node-environment suite.
//
// `expo-secure-store` is a native module that pulls `react-native` (Flow syntax,
// unparseable outside Metro), so the session adapter is pointed at an in-memory
// stub. The modules under test stay real.
export default defineConfig({
  resolve: {
    alias: {
      'expo-secure-store': fileURLToPath(new URL('./tests/stubs/expo-secure-store.ts', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['tests/**/*.test.ts'],
    testTimeout: 20_000,
  },
});
