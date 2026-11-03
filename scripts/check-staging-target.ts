/**
 * Staging identity check CLI.
 *
 * Verifies that the configured staging target is internally consistent, then
 * stops. It never opens a connection: there is nothing to connect to until the
 * project owner designates a staging project.
 *
 *   npm run staging:check
 *
 * Exit codes: 0 = identity verified locally, 1 = BLOCKED.
 */
import { describeStagingTarget, resolveStagingTarget, STAGING_REF_ENV } from '../src/config/staging-target.ts';

const resolution = resolveStagingTarget(process.env);

if (!resolution.ok) {
  console.error(resolution.message);
  console.error(`Set ${STAGING_REF_ENV} to the designated staging project reference, then re-run.`);
  process.exit(1);
}

console.log('Staging target identity is internally consistent:');
console.log(`  ${describeStagingTarget(resolution.target)}`);
console.log('  host: resolved from the staging configuration at run time (not printed here)');
console.log('Local identity check only. No database connection was attempted.');
console.log('Remote identity verification remains BLOCKED until the staging project is designated and approved.');
