import { defineConfig } from 'vitest/config';

// Runs in node against pure modules only (src/lib without React Native
// imports). This mirrors the parent project's node-environment suite.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['tests/**/*.test.ts'],
    testTimeout: 20_000,
  },
});
