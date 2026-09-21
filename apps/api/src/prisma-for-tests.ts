/**
 * The tests import the client through here rather than reaching into src/lib, so a
 * future swap to a per-test transaction or a pooled client is one file to change.
 */
export { prisma } from './lib/prisma.js';
