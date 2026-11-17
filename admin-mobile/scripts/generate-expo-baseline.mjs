#!/usr/bin/env node
/**
 * Generates admin-mobile/expo-compat-baseline.json from the Expo SDK that is
 * actually installed in node_modules.
 *
 * The baseline is the deterministic, committed record of "which package
 * versions the Expo SDK in this project considers compatible". It exists so CI
 * can validate the committed dependency set offline, instead of asking the
 * moving target that is api.expo.dev/v2/sdks/<sdk>/native-modules.
 *
 * This script is ONLY ever run by a developer, on purpose:
 *   npm run expo:baseline:generate
 * then the resulting diff is reviewed and committed.
 *
 * It refuses to run when CI is set, and it never fetches remote metadata.
 * Source of truth: node_modules/expo/bundledNativeModules.json
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const expoPkgPath = join(projectRoot, 'node_modules', 'expo', 'package.json');
const matrixPath = join(projectRoot, 'node_modules', 'expo', 'bundledNativeModules.json');
const pkgJsonPath = join(projectRoot, 'package.json');
const baselinePath = join(projectRoot, 'expo-compat-baseline.json');

/**
 * Packages that are declared in package.json but that the Expo SDK does not
 * version. Recording them keeps the baseline honest: an unlisted package cannot
 * quietly escape compatibility review.
 */
const NOT_IN_SDK_MATRIX_REASONS = {
  expo:
    'The Expo SDK package itself. Its installed version is the anchor of this baseline (the `expo` field above), so it is not a matrix entry.',
  '@expo/log-box':
    'Expo Metro/LogBox runtime helper. Published outside the SDK native-module matrix; pinned to an exact version here.',
  '@supabase/supabase-js': 'Backend client library. Not an Expo module, not versioned by the Expo SDK.',
  semver: 'Build-time tooling used by scripts/check-expo-compat.mjs. Not shipped to the app bundle.',
  typescript: 'Compiler tooling. Not versioned by the Expo SDK.',
  vitest: 'Unit-test runner. Not versioned by the Expo SDK.',
  '@types/react': 'Type declarations for React. Follows the React version pinned by the Expo SDK.',
};

function fail(message) {
  console.error(`\nERROR: expo-baseline: ${message}\n`);
  process.exit(1);
}

if (process.env.CI === 'true' || process.env.CI === '1') {
  fail(
    'refusing to regenerate the baseline in CI. The baseline is committed and reviewed source code; ' +
      'run `npm run expo:baseline:generate` locally and commit the diff.',
  );
}

for (const [label, file] of [
  ['installed expo package.json', expoPkgPath],
  ['installed expo/bundledNativeModules.json', matrixPath],
  ['project package.json', pkgJsonPath],
]) {
  if (!existsSync(file)) {
    fail(`cannot find ${label} at ${file}. Run \`npm ci\` first.`);
  }
}

const expoPkg = JSON.parse(readFileSync(expoPkgPath, 'utf8').replace(/^\uFEFF/, ''));
const matrix = JSON.parse(readFileSync(matrixPath, 'utf8').replace(/^\uFEFF/, ''));
const pkgJson = JSON.parse(readFileSync(pkgJsonPath, 'utf8').replace(/^\uFEFF/, ''));

if (typeof matrix !== 'object' || matrix === null || Array.isArray(matrix)) {
  fail(`unexpected contents in ${matrixPath}; expected an object of package name to version range.`);
}
if (typeof expoPkg.version !== 'string') {
  fail(`cannot determine the installed expo version from ${expoPkgPath}.`);
}

const sdkMajor = Number.parseInt(expoPkg.version.split('.')[0], 10);
if (!Number.isInteger(sdkMajor) || sdkMajor <= 0) {
  fail(`cannot derive an SDK major version from expo@${expoPkg.version}.`);
}

const declared = {
  ...(pkgJson.dependencies || {}),
  ...(pkgJson.devDependencies || {}),
};

const packages = {};
const notInSdkMatrix = {};
const unknownReason = [];

for (const name of Object.keys(declared).sort()) {
  const declaredRange = declared[name];
  if (Object.prototype.hasOwnProperty.call(matrix, name)) {
    packages[name] = matrix[name];
    continue;
  }
  const reason = NOT_IN_SDK_MATRIX_REASONS[name];
  if (!reason) {
    unknownReason.push(name);
  }
  notInSdkMatrix[name] = {
    range: declaredRange,
    reason: reason ?? 'Not versioned by the Expo SDK native-module matrix.',
  };
}

if (unknownReason.length > 0) {
  console.warn(
    `\nWARNING: no explicit reason recorded for: ${unknownReason.join(', ')}\n` +
      '  Add an entry to NOT_IN_SDK_MATRIX_REASONS in scripts/generate-expo-baseline.mjs so the\n' +
      '  baseline documents why the package is outside the SDK matrix.\n',
  );
}

const baseline = {
  description:
    'Deterministic Expo SDK compatibility baseline for admin-mobile. Generated from ' +
    'node_modules/expo/bundledNativeModules.json; committed and reviewed like source code. ' +
    'Regenerate deliberately with `npm run expo:baseline:generate`.',
  expo: expoPkg.version,
  sdkVersion: `${sdkMajor}.0.0`,
  source: 'node_modules/expo/bundledNativeModules.json',
  packages,
  notInSdkMatrix,
};

writeFileSync(baselinePath, `${JSON.stringify(baseline, null, 2)}\n`, 'utf8');

console.log(`OK: wrote ${baselinePath}`);
console.log(`  expo ${baseline.expo} (SDK ${baseline.sdkVersion})`);
console.log(`  ${Object.keys(packages).length} matrix packages, ${Object.keys(notInSdkMatrix).length} explicitly handled`);
console.log('\nReview the diff before committing: the baseline is the contract CI enforces.\n');
