#!/usr/bin/env node
/**
 * Blocking, deterministic Expo SDK compatibility check for admin-mobile.
 *
 * It validates the committed dependency set against the committed baseline
 * (expo-compat-baseline.json) and against the compatibility matrix that ships
 * inside the installed `expo` package. No network calls, no upstream metadata:
 * an unchanged commit always produces the same verdict.
 *
 * What it verifies
 *   1. The baseline file exists, parses, and is well formed.
 *   2. The installed expo version matches the baseline anchor.
 *   3. The installed SDK metadata still matches the committed matrix ranges.
 *   4. Every declared package is either covered by the matrix or explicitly
 *      recorded as "not versioned by the SDK".
 *   5. Declared ranges are a subset of the SDK's allowed range, so any version
 *      an install could pick stays inside the SDK contract.
 *   6. Installed versions satisfy both the declared range and the matrix range;
 *      a declared-but-uninstalled package is reported as missing.
 *
 * Exit codes: 0 = compatible, 1 = incompatible or unverifiable.
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import semver from 'semver';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const baselinePath = join(projectRoot, 'expo-compat-baseline.json');
const pkgJsonPath = join(projectRoot, 'package.json');
const nodeModules = join(projectRoot, 'node_modules');
const expoPkgPath = join(nodeModules, 'expo', 'package.json');
const matrixPath = join(nodeModules, 'expo', 'bundledNativeModules.json');

const failures = [];
const notes = [];

function fail(message) {
  failures.push(message);
}

function readJson(file, label) {
  if (!existsSync(file)) {
    fail(`cannot read ${label}: ${file} is missing. Run \`npm ci\` before this check.`);
    return null;
  }
  try {
    // Tolerate a UTF-8 BOM: Windows editors add one and JSON.parse rejects it.
    return JSON.parse(readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
  } catch (error) {
    fail(`cannot read ${label}: ${file} is not valid JSON (${error.message}).`);
    return null;
  }
}

function installedVersion(name) {
  const file = join(nodeModules, name, 'package.json');
  if (!existsSync(file)) {
    return null;
  }
  try {
    return JSON.parse(readFileSync(file, 'utf8')).version ?? null;
  } catch {
    return null;
  }
}

const baseline = readJson(baselinePath, 'compatibility baseline');
const pkgJson = readJson(pkgJsonPath, 'package.json');
const expoPkg = readJson(expoPkgPath, 'installed expo package.json');
const matrix = readJson(matrixPath, 'installed expo/bundledNativeModules.json');

// Guard the baseline's own shape before trusting any of its content.
if (baseline) {
  if (typeof baseline.expo !== 'string' || !semver.valid(baseline.expo)) {
    fail('baseline is invalid: `expo` must be an exact version string.');
  }
  if (typeof baseline.sdkVersion !== 'string' || !/^\d+\.0\.0$/.test(baseline.sdkVersion)) {
    fail('baseline is invalid: `sdkVersion` must look like "58.0.0".');
  }
  if (typeof baseline.packages !== 'object' || baseline.packages === null || Array.isArray(baseline.packages)) {
    fail('baseline is invalid: `packages` must be an object of package name to SDK range.');
  }
  if (
    typeof baseline.notInSdkMatrix !== 'object' ||
    baseline.notInSdkMatrix === null ||
    Array.isArray(baseline.notInSdkMatrix)
  ) {
    fail('baseline is invalid: `notInSdkMatrix` must be an object.');
  }
}

if (failures.length === 0) {
  // 1. Installed expo must be exactly what the baseline was generated from.
  const expoVersion = expoPkg?.version;
  if (!expoVersion || !semver.valid(expoVersion)) {
    fail(`cannot determine the installed expo version from ${expoPkgPath}.`);
  } else if (expoVersion !== baseline.expo) {
    fail(
      `installed expo is ${expoVersion} but the baseline was generated for expo ${baseline.expo}.\n` +
        '    The SDK compatibility matrix may have changed. Run `npm run expo:baseline:generate`,\n' +
        '    review the diff, and commit it with the dependency change.',
    );
  } else {
    const sdkMajor = Number.parseInt(expoVersion.split('.')[0], 10);
    const expectedSdk = `${sdkMajor}.0.0`;
    if (expectedSdk !== baseline.sdkVersion) {
      fail(`baseline sdkVersion is ${baseline.sdkVersion} but expo ${expoVersion} implies ${expectedSdk}.`);
    }
  }

  // 2. Installed SDK metadata must still match the committed ranges.
  if (matrix) {
    for (const [name, range] of Object.entries(baseline.packages)) {
      const live = matrix[name];
      if (live === undefined) {
        fail(
          `SDK metadata drift: "${name}" is in the baseline but no longer in the installed expo matrix.\n` +
            '    Run `npm run expo:baseline:generate` and review the diff.',
        );
      } else if (live !== range) {
        fail(
          `SDK metadata drift: "${name}" baseline range ${range} but installed matrix says ${live}.\n` +
            '    Run `npm run expo:baseline:generate` and review the diff.',
        );
      }
    }
    for (const name of Object.keys(baseline.packages)) {
      const declaredRange = { ...(pkgJson?.dependencies || {}), ...(pkgJson?.devDependencies || {}) }[name];
      if (declaredRange === undefined) {
        notes.push(`baseline lists "${name}" but package.json no longer declares it; regenerate the baseline.`);
        fail(`stale baseline entry: "${name}" is no longer a declared dependency.`);
      }
    }
  }

  const declared = {
    ...(pkgJson?.dependencies || {}),
    ...(pkgJson?.devDependencies || {}),
  };

  // 3. Every declared package must be covered, one way or the other.
  for (const name of Object.keys(declared).sort()) {
    const inMatrix = Object.prototype.hasOwnProperty.call(baseline.packages, name);
    const inExplicit = Object.prototype.hasOwnProperty.call(baseline.notInSdkMatrix, name);
    if (!inMatrix && !inExplicit) {
      fail(
        `"${name}" is declared in package.json but the baseline does not cover it.\n` +
          '    Run `npm run expo:baseline:generate`, review the diff, and commit it.',
      );
    }
  }

  // 4. Matrix packages: declared range inside the SDK range, installed version inside both.
  for (const [name, sdkRange] of Object.entries(baseline.packages).sort(([a], [b]) => a.localeCompare(b))) {
    const declaredRange = declared[name];
    if (declaredRange === undefined) {
      continue; // already reported as a stale baseline entry above
    }

    if (!semver.validRange(declaredRange)) {
      fail(`"${name}": package.json range "${declaredRange}" is not a valid semver range.`);
      continue;
    }
    if (!semver.validRange(sdkRange)) {
      fail(`"${name}": baseline range "${sdkRange}" is not a valid semver range.`);
      continue;
    }
    if (!semver.subset(declaredRange, sdkRange)) {
      fail(
        `"${name}": package.json declares ${declaredRange}, which is not contained in the SDK range ${sdkRange}.\n` +
          '    An install inside the declared range could resolve outside the SDK contract.',
      );
    }

    const installed = installedVersion(name);
    if (installed === null) {
      fail(`"${name}": declared in package.json but not installed in node_modules (missing required package).`);
      continue;
    }
    if (!semver.satisfies(installed, sdkRange)) {
      fail(`"${name}": installed ${installed} does not satisfy the SDK range ${sdkRange}.`);
    } else if (!semver.satisfies(installed, declaredRange)) {
      fail(`"${name}": installed ${installed} does not satisfy the declared range ${declaredRange}.`);
    }
  }

  // 5. Packages outside the SDK matrix: still declared, still installed, still recorded.
  for (const [name, entry] of Object.entries(baseline.notInSdkMatrix).sort(([a], [b]) => a.localeCompare(b))) {
    const declaredRange = declared[name];
    if (declaredRange === undefined) {
      fail(`stale baseline entry: "${name}" is recorded in notInSdkMatrix but is no longer declared.`);
      continue;
    }
    if (!entry || typeof entry.range !== 'string') {
      fail(`baseline is invalid: notInSdkMatrix["${name}"].range must be a string.`);
      continue;
    }
    if (entry.range !== declaredRange) {
      fail(
        `"${name}": package.json declares ${declaredRange} but the baseline records ${entry.range}.\n` +
          '    Run `npm run expo:baseline:generate` and review the diff.',
      );
    }
    const installed = installedVersion(name);
    if (installed === null) {
      fail(`"${name}": declared in package.json but not installed in node_modules (missing required package).`);
    } else if (semver.validRange(declaredRange) && !semver.satisfies(installed, declaredRange)) {
      fail(`"${name}": installed ${installed} does not satisfy the declared range ${declaredRange}.`);
    }
    notes.push(`"${name}" is outside the SDK matrix and explicitly handled (${entry.range}).`);
  }
}

const checkedCount =
  baseline && baseline.packages && typeof baseline.packages === 'object' ? Object.keys(baseline.packages).length : 0;

if (failures.length > 0) {
  console.error('\nFAILED: Expo SDK compatibility check\n');
  for (const failure of failures) {
    console.error(`  - ${failure}`);
  }
  console.error(`\n  ${failures.length} problem(s) across ${checkedCount} matrix packages.\n`);
  process.exit(1);
}

console.log(`\nOK: Expo SDK compatibility - expo ${baseline.expo} (SDK ${baseline.sdkVersion})`);
console.log(`  ${checkedCount} matrix packages satisfy the committed baseline and the installed SDK metadata.`);
console.log(
  `  ${Object.keys(baseline.notInSdkMatrix).length} packages outside the SDK matrix are explicitly recorded.`,
);
if (process.env.VERBOSE === '1') {
  for (const note of notes) {
    console.log(`  - ${note}`);
  }
}
console.log('');
