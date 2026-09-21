import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    environment: 'node',
    setupFiles: ['test/setup.ts'],
    /**
     * These are real integration tests against one Postgres database, and each file
     * truncates every table between tests. Running files in parallel would have them
     * wiping each other's rows, so they run one at a time.
     */
    fileParallelism: false,
    testTimeout: 20_000,
    hookTimeout: 20_000
  }
});
