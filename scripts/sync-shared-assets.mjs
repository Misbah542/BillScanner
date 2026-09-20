#!/usr/bin/env node
/**
 * Copies the shared rule data (SMS bank-alert patterns, category taxonomy) from
 * packages/shared/data into the Android app's assets folder, so the Kotlin
 * parsers and the TypeScript parsers are driven by the same tables.
 *
 * Run it after editing anything under packages/shared/data:
 *   npm run sync:assets
 */
import { copyFile, mkdir, readdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const from = join(root, 'packages/shared/data');
const to = join(root, 'apps/android/app/src/main/assets/shared');

await mkdir(to, { recursive: true });
const files = (await readdir(from)).filter((f) => f.endsWith('.json'));
for (const file of files) {
  await copyFile(join(from, file), join(to, file));
  console.log(`synced ${file}`);
}
console.log(`\n${files.length} file(s) -> apps/android/app/src/main/assets/shared`);
