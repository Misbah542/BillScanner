#!/usr/bin/env node
/**
 * One command to get a working checkout: install, bring up Postgres, create the
 * .env file, migrate, seed and build.
 *
 *   npm run setup
 *
 * It is safe to re-run after a pull — every step is idempotent, and an existing
 * .env is left alone rather than overwritten.
 */
import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const env = join(root, 'apps/api/.env');

function run(command, args, { optional = false } = {}) {
  process.stdout.write(`\n\u001b[36m› ${command} ${args.join(' ')}\u001b[0m\n`);
  const result = spawnSync(command, args, { cwd: root, stdio: 'inherit', shell: process.platform === 'win32' });
  if (result.status === 0) return true;
  if (optional) {
    process.stdout.write(`\u001b[33m  skipped (exit ${result.status ?? 'not found'})\u001b[0m\n`);
    return false;
  }
  process.exit(result.status ?? 1);
}

/** Synchronous, because this script is a straight line and async buys nothing. */
function sleepOneSecond() {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 1000);
}

function has(command) {
  const probe = spawnSync(command, ['--version'], { stdio: 'ignore', shell: process.platform === 'win32' });
  return probe.status === 0;
}

run('npm', ['install']);

if (existsSync(env)) {
  console.log('\n  apps/api/.env exists, leaving it as it is');
} else {
  copyFileSync(join(root, 'apps/api/.env.example'), env);
  console.log('\n  wrote apps/api/.env from .env.example — set a real JWT_SECRET before you deploy anything');
}

// Postgres is the only service the API needs; the scan queue drains through the
// outbox table, so there is no broker to start.
const docker = has('docker') && run('docker', ['compose', 'up', '-d', 'postgres'], { optional: true });
if (docker) {
  process.stdout.write('\n\u001b[36m› waiting for Postgres\u001b[0m\n');
  const deadline = Date.now() + 60_000;
  let ready = false;
  while (Date.now() < deadline) {
    const probe = spawnSync('docker', ['compose', 'exec', '-T', 'postgres', 'pg_isready', '-U', 'snaptab', '-d', 'snaptab'], {
      cwd: root,
      stdio: 'ignore'
    });
    if (probe.status === 0) {
      ready = true;
      break;
    }
    sleepOneSecond();
  }
  console.log(ready ? '  ready' : '  still not ready after 60s, carrying on anyway');
} else {
  console.log('\n  no Docker here — start Postgres yourself and point DATABASE_URL at it');
}

run('npm', ['run', 'build', '--workspace', '@snaptab/shared']);
run('npm', ['run', 'sync:assets']);
run('npm', ['run', 'db:generate']);
run('npm', ['run', 'db:migrate']);

process.env.SEED_DEMO = '1';
run('npm', ['run', 'db:seed'], { optional: true });

run('npm', ['run', 'build', '--workspace', '@snaptab/api']);

console.log(`
\u001b[32mReady.\u001b[0m

  npm run dev          the API on http://localhost:4000
  npm run dev:worker    the scan/outbox worker, in another terminal
  npm test              95 shared + 131 API tests

The Android app needs JDK 17 and the Android SDK; see docs/ANDROID.md. To try it
with no server at all:  cd apps/android && ./gradlew installDemoDebug
`);
