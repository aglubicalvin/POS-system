import { spawnSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { afterEach, describe, expect, it } from 'vitest';

// The checker resolves `semver` from its own tree, and fixtures must never be
// written outside the project, so every fixture is created (and removed) here.
const adminMobileRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const checkerSource = join(adminMobileRoot, 'scripts', 'check-expo-compat.mjs');

type Excluded = Record<string, { range: string; reason: string }>;
type Installed = Record<string, string | null>;

interface FixtureOptions {
  declared: Record<string, string>;
  excluded: Excluded;
  matrix?: Record<string, string>;
  installed: Installed;
  expoVersion?: string;
}

// Fixture packages must not shadow modules the checker itself imports, so the
// "excluded package" under test is `typescript`, not `semver`.
const DEFAULT_DECLARED: Record<string, string> = {
  expo: '~58.0.26',
  'expo-router': '~58.0.32',
  typescript: '~6.0.3',
};

const DEFAULT_EXCLUDED: Excluded = {
  expo: { range: '~58.0.26', reason: 'The SDK package itself; its version is the baseline anchor.' },
  typescript: { range: '~6.0.3', reason: 'Compiler tooling, not versioned by the Expo SDK.' },
};

const DEFAULT_INSTALLED: Installed = {
  expo: '58.0.26',
  'expo-router': '58.0.32',
  typescript: '6.0.3',
};

function writeJson(file: string, value: unknown): void {
  writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function createFixture(options: FixtureOptions): { root: string; script: string } {
  const expoVersion = options.expoVersion ?? '58.0.26';
  const matrix = options.matrix ?? { 'expo-router': '~58.0.32' };
  const root = mkdtempSync(join(adminMobileRoot, '.compat-fixture-'));

  mkdirSync(join(root, 'scripts'), { recursive: true });
  // Copy the committed checker unchanged: the fixture exercises the real file.
  cpSync(checkerSource, join(root, 'scripts', 'check-expo-compat.mjs'));

  writeJson(join(root, 'package.json'), {
    name: 'expo-compat-fixture',
    version: '1.0.0',
    dependencies: options.declared,
  });
  writeJson(join(root, 'expo-compat-baseline.json'), {
    description: 'fixture baseline',
    expo: expoVersion,
    sdkVersion: '58.0.0',
    source: 'node_modules/expo/bundledNativeModules.json',
    packages: matrix,
    notInSdkMatrix: options.excluded,
  });

  mkdirSync(join(root, 'node_modules', 'expo'), { recursive: true });
  writeJson(join(root, 'node_modules', 'expo', 'package.json'), { name: 'expo', version: expoVersion });
  writeJson(join(root, 'node_modules', 'expo', 'bundledNativeModules.json'), matrix);

  for (const [name, version] of Object.entries(options.installed)) {
    if (version === null) {
      continue; // declared but deliberately not installed
    }
    mkdirSync(join(root, 'node_modules', name), { recursive: true });
    writeJson(join(root, 'node_modules', name, 'package.json'), { name, version });
  }

  return { root, script: join(root, 'scripts', 'check-expo-compat.mjs') };
}

function runChecker(script: string, cwd: string): { status: number | null; output: string } {
  const result = spawnSync(process.execPath, [script], { cwd, encoding: 'utf8' });
  return { status: result.status, output: `${result.stdout}${result.stderr}` };
}

describe('check-expo-compat: packages outside the SDK matrix', () => {
  let fixtureRoot: string | null = null;

  afterEach(() => {
    if (fixtureRoot) {
      rmSync(fixtureRoot, { recursive: true, force: true });
      fixtureRoot = null;
    }
  });

  function fixture(options: Partial<FixtureOptions> = {}) {
    const created = createFixture({
      declared: options.declared ?? DEFAULT_DECLARED,
      excluded: options.excluded ?? DEFAULT_EXCLUDED,
      matrix: options.matrix,
      installed: options.installed ?? DEFAULT_INSTALLED,
      expoVersion: options.expoVersion,
    });
    fixtureRoot = created.root;
    return created;
  }

  it('accepts a semver declaration for an explicitly excluded package', () => {
    const { script, root } = fixture();
    const { status, output } = runChecker(script, root);

    expect(status).toBe(0);
    expect(output).toContain('OK: Expo SDK compatibility');
  });

  it('fails closed on a non-semver dependency specification', () => {
    const { script, root } = fixture({
      declared: { ...DEFAULT_DECLARED, typescript: 'file:../shared-typescript' },
      excluded: {
        ...DEFAULT_EXCLUDED,
        typescript: { range: 'file:../shared-typescript', reason: 'fixture: local path dependency' },
      },
    });
    const { status, output } = runChecker(script, root);

    expect(status).toBe(1);
    expect(output).toContain('"typescript": package.json declares "file:../shared-typescript"');
    expect(output).toContain('which is not a semver range');
    expect(output).not.toContain('OK: Expo SDK compatibility');
  });

  it('fails closed on a git specifier for an excluded package', () => {
    const { script, root } = fixture({
      declared: { ...DEFAULT_DECLARED, typescript: 'git+https://example.invalid/typescript.git' },
      excluded: {
        ...DEFAULT_EXCLUDED,
        typescript: { range: 'git+https://example.invalid/typescript.git', reason: 'fixture: git dependency' },
      },
    });
    const { status, output } = runChecker(script, root);

    expect(status).toBe(1);
    expect(output).toContain('is not a semver range');
  });

  it('reports a declared but missing installed package', () => {
    const { script, root } = fixture({
      installed: { ...DEFAULT_INSTALLED, 'expo-router': null },
    });
    const { status, output } = runChecker(script, root);

    expect(status).toBe(1);
    expect(output).toContain('"expo-router": declared in package.json but not installed');
    expect(output).toContain('missing required package');
  });

  it('fails when the installed version metadata is not a valid version', () => {
    const { script, root } = fixture({
      installed: { ...DEFAULT_INSTALLED, typescript: 'banana' },
    });
    const { status, output } = runChecker(script, root);

    expect(status).toBe(1);
    expect(output).toContain('has an invalid version "banana"');
  });

  it('still accepts the committed baseline and its documented exclusions', () => {
    const realScript = join(adminMobileRoot, 'scripts', 'check-expo-compat.mjs');
    const { status, output } = runChecker(realScript, adminMobileRoot);

    expect(status).toBe(0);
    expect(output).toContain('OK: Expo SDK compatibility - expo 58.0.26 (SDK 58.0.0)');
    expect(output).toContain('7 packages outside the SDK matrix are explicitly recorded.');
  });
});
