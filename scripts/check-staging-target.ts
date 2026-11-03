/**
 * Staging identity check CLI.
 *
 * Verifies that the configured staging target is internally consistent, then
 * stops. It never opens a connection: this check reads configuration only.
 *
 *   npm run staging:check
 *
 * Exit codes: 0 = local staging configuration validated, 1 = BLOCKED.
 *
 * This script validates configuration only. A pass means the local staging
 * settings are internally consistent; it says nothing about which database the
 * settings point at, because no connection is opened here. Remote identity is
 * verified separately by the approved read-only workflow probe
 * (.github/workflows/probe-staging-identity.yml).
 */
import { describeStagingTarget, resolveStagingTarget, STAGING_REF_ENV } from '../src/config/staging-target.ts';

const resolution = resolveStagingTarget(process.env);

if (!resolution.ok) {
  console.error(resolution.message);
  console.error(`Set ${STAGING_REF_ENV} to the designated staging project reference, then re-run.`);
  process.exit(1);
}

console.log('Local staging configuration validation passed:');
console.log(`  ${describeStagingTarget(resolution.target)}`);
console.log('  host: resolved from the staging configuration at run time (not printed here)');
console.log('This validation script does not connect to a database.');
console.log(
  'Remote identity verification is performed separately by the approved workflow probe ' +
    '(probe-staging-identity.yml), which runs the read-only ProbeState audit.',
);
