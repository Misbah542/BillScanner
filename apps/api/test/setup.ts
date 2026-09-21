import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Loads .env.test before anything imports src/env.ts, which validates config at
 * module load. Without this the first import would throw on a missing DATABASE_URL.
 */
const envPath = resolve(import.meta.dirname, '../.env.test');
try {
  for (const line of readFileSync(envPath, 'utf8').split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const index = trimmed.indexOf('=');
    if (index < 0) continue;
    const key = trimmed.slice(0, index);
    if (process.env[key] === undefined) process.env[key] = trimmed.slice(index + 1);
  }
} catch {
  // CI provides the variables directly.
}
process.env.NODE_ENV = 'test';
