import { execFileSync, spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * CLI behaviour of scripts/check-staging-target.ts.
 *
 * The script is executed as a child process with a synthetic environment, so
 * these assertions cover the message a reviewer actually sees. Every reference
 * here is synthetic; nothing opens a connection.
 */
const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..');
const script = resolve(repoRoot, 'scripts/check-staging-target.ts');

const STAGING_REF = 'stagingref0000000001';
const DEV_REF = 'uemdrtrzlvhdtonccczt';

function runValidator(env: Record<string, string | undefined>) {
  const child = spawnSync(process.execPath, [script], {
    cwd: repoRoot,
    encoding: 'utf8',
    env: { PATH: process.env.PATH ?? '', ...env },
  });
  return { status: child.status, stdout: child.stdout, stderr: child.stderr };
}

describe('check-staging-target CLI messaging', () => {
  it('states that local validation passed and that this script never connects', () => {
    const { status, stdout, stderr } = runValidator({
      POS_STAGING_PROJECT_REF: STAGING_REF,
      POS_STAGING_ADMIN_USER: `postgres.${STAGING_REF}`,
    });

    expect(status, stderr).toBe(0);
    expect(stdout).toContain('Local staging configuration validation passed');
    expect(stdout).toContain('This validation script does not connect to a database.');
    // Remote verification is described as a separate, workflow-performed step.
    expect(stdout).toMatch(/Remote identity verification is performed separately/);
    expect(stdout).toContain('probe-staging-identity.yml');
    expect(stdout).toContain('ProbeState');
  });

  it('no longer claims remote verification is blocked', () => {
    const { stdout } = runValidator({
      POS_STAGING_PROJECT_REF: STAGING_REF,
      POS_STAGING_ADMIN_USER: `postgres.${STAGING_REF}`,
    });
    // The misleading wording asserted the probe could not run at all; the
    // probe runs as a separate workflow, so a passing config is not "blocked".
    expect(stdout).not.toContain('Remote identity verification remains BLOCKED');
    expect(stdout.toLowerCase()).not.toContain('blocked until the staging project');
  });

  it('prints no host, database URL or credential', () => {
    const { stdout } = runValidator({
      POS_STAGING_PROJECT_REF: STAGING_REF,
      POS_STAGING_ADMIN_USER: `postgres.${STAGING_REF}`,
      POS_STAGING_ADMIN_PASS: 'do-not-print-me',
      CHECK_PASS: 'do-not-print-me',
    });
    expect(stdout).not.toContain('do-not-print-me');
    expect(stdout).not.toMatch(/jdbc:/);
    expect(stdout).not.toContain('.supabase.com');
  });

  it('still blocks a known non-staging project and never adopts the pass wording', () => {
    const { status, stdout, stderr } = runValidator({
      POS_STAGING_PROJECT_REF: DEV_REF,
      POS_STAGING_ADMIN_USER: `postgres.${DEV_REF}`,
    });

    expect(status).toBe(1);
    expect(stderr).toContain('BLOCKED');
    // The denylist entry is rejected as a non-staging project.
    expect(stderr).toContain('known non-staging project');
    expect(stdout).not.toContain('Local staging configuration validation passed');
  });

  it('still fails closed on a missing reference', () => {
    const { status, stderr } = runValidator({});
    expect(status).toBe(1);
    expect(stderr).toContain('POS_STAGING_PROJECT_REF');
  });

  it('runs under the Node that ships with the workflow (no unrecognised flags)', () => {
    // Node >= 23 runs the TypeScript via type stripping; the workflow depends on
    // that, so the script must not gain syntax it cannot parse.
    const { status } = runValidator({
      POS_STAGING_PROJECT_REF: STAGING_REF,
      POS_STAGING_ADMIN_USER: `postgres.${STAGING_REF}`,
    });
    expect(status).toBe(0);
  });
});

describe('check-staging-target is committed and wired up', () => {
  it('is tracked in the git index', () => {
    const out = execFileSync('git', ['ls-files', '--error-unmatch', 'scripts/check-staging-target.ts'], {
      cwd: repoRoot,
      encoding: 'utf8',
    });
    expect(out.trim()).toBe('scripts/check-staging-target.ts');
  });
});
