import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * Workflow configuration guard.
 *
 * These assertions protect the separation between the development deployment
 * environment and the staging one, and the invariants that make the probe safe
 * (manual-only, dry-run by default, no hard-coded project references, no
 * credentials on the command line). They read the workflow files as text — no
 * workflow is dispatched and no network is used.
 */
const here = dirname(fileURLToPath(import.meta.url));
const workflowsDir = resolve(here, '../.github/workflows');
const read = (name: string) => readFileSync(resolve(workflowsDir, name), 'utf8');

const DEV_WORKFLOW = 'probe-readonly.yml';
const STAGING_WORKFLOW = 'probe-staging.yml';
const IDENTITY_WORKFLOW = 'probe-staging-identity.yml';
const STAGING_TARGET_VALIDATOR = 'node scripts/check-staging-target.ts';

describe('deployment workflow separation', () => {
  it('keeps the development workflow on the development environment', () => {
    const yml = read(DEV_WORKFLOW);
    expect(yml).toContain('environment: pos-dev-admin');
    expect(yml).not.toContain('environment: pos-staging-admin');
  });

  it('keeps the staging workflow on the staging environment', () => {
    const yml = read(STAGING_WORKFLOW);
    expect(yml).toContain('environment: pos-staging-admin');
    expect(yml).not.toContain('environment: pos-dev-admin');
  });

  it('never runs on push or pull_request (manual dispatch only)', () => {
    for (const name of [DEV_WORKFLOW, STAGING_WORKFLOW]) {
      const yml = read(name);
      expect(yml, name).toMatch(/^on:\s*\n\s*workflow_dispatch:/m);
      expect(yml, name).not.toMatch(/^\s+(push|pull_request):/m);
    }
  });

  it('defaults apply to false in both workflows', () => {
    for (const name of [DEV_WORKFLOW, STAGING_WORKFLOW]) {
      const yml = read(name);
      const block = yml.split(/^\s+apply:/m)[1] ?? '';
      expect(block.slice(0, 200), name).toMatch(/type:\s*boolean/);
      expect(block.slice(0, 200), name).toMatch(/default:\s*false/);
    }
  });

  it('contains no hard-coded Supabase project references', () => {
    const known = ['uemdrtrzlvhdtonccczt', 'numwzjogaanfhlrfgrqk', 'mjqoydqcmecjvzbckgk'];
    for (const file of readdirSync(workflowsDir).filter((f) => f.endsWith('.yml'))) {
      const yml = read(file);
      for (const ref of known) {
        expect(yml, `${file} must not hard-code ${ref}`).not.toContain(ref);
      }
      // no generic 20-char lowercase project-ref literal in a workflow file
      expect(yml, `${file} must not contain a project-ref literal`).not.toMatch(
        /\b[a-z0-9]{20}\b/,
      );
    }
  });

  it('keeps checkout credential-less and read-only permissions', () => {
    for (const name of [DEV_WORKFLOW, STAGING_WORKFLOW]) {
      const yml = read(name);
      expect(yml, name).toContain('persist-credentials: false');
      expect(yml, name).toContain('permissions:');
      expect(yml, name).toContain('contents: read');
    }
  });

  it('passes credentials only through the environment, never as arguments', () => {
    for (const name of [DEV_WORKFLOW, STAGING_WORKFLOW]) {
      const yml = read(name);
      // secrets appear only as env bindings
      expect(yml, name).toMatch(/PROBE_ADMIN_PASS:\s*\$\{\{\s*secrets\.PROBE_ADMIN_PASS\s*\}\}/);
      expect(yml, name).toMatch(/PROBE_PW:\s*\$\{\{\s*secrets\.PROBE_PW\s*\}\}/);
      // never interpolated directly into a run command
      expect(yml, name).not.toMatch(/secrets\.[A-Z_]+\s*\}\}\s*-/);
    }
  });
});

describe('staging identity workflow target validation', () => {
  it('is manual dispatch only and runs on the staging environment', () => {
    const yml = read(IDENTITY_WORKFLOW);
    expect(yml).toMatch(/^on:\s*\n\s*workflow_dispatch:/m);
    expect(yml).not.toMatch(/^\s+(push|pull_request|schedule|workflow_run):/m);
    expect(yml).toContain('environment: pos-staging-admin');
    expect(yml).not.toContain('environment: pos-dev-admin');
  });

  it('rejects known non-staging projects through the shared validator at run time', () => {
    const yml = read(IDENTITY_WORKFLOW);
    // The denylist lives in src/config/staging-target.ts. The workflow must invoke
    // that module so a development or former-verification reference is refused in
    // the Actions runtime path, not only in unit tests.
    expect(yml).toContain(STAGING_TARGET_VALIDATOR);
    // The environment stores the administrator login as PROBE_ADMIN_USER; the
    // validator reads POS_STAGING_ADMIN_USER, so the two must be mapped.
    expect(yml).toContain('POS_STAGING_ADMIN_USER: ${{ vars.PROBE_ADMIN_USER }}');
  });

  it('does not keep a per-workflow copy of the non-staging denylist', () => {
    // A duplicate list would drift from src/config/staging-target.ts. The workflow
    // must stay free of project-reference literals, which the repository-wide
    // assertion above also enforces.
    const yml = read(IDENTITY_WORKFLOW);
    expect(yml).not.toMatch(/uemdrtrzlvhdtonccczt|numwzjogaanfhlrfgrqk/);
  });

  it('fails closed before connecting when the validator rejects the target', () => {
    const yml = read(IDENTITY_WORKFLOW);
    const beforeValidator = yml.split(STAGING_TARGET_VALIDATOR)[0] ?? '';
    // `set -e` must be in force in the step that runs the validator, so a refused
    // target fails the job rather than falling through to the probe.
    expect(beforeValidator).toMatch(/set -euo pipefail/);
    // The validator must run before the probe is launched.
    expect(yml.indexOf(STAGING_TARGET_VALIDATOR)).toBeLessThan(
      yml.indexOf('execProbeState'),
    );
  });
});

describe('identity probe exit-status and evidence handling', () => {
  // Regression coverage for GitHub Actions run 41560464733, which died on
  // exit 126 and then failed to produce any evidence. These assertions read
  // workflow text and the git index only — no dispatch, no network, no
  // database connection.
  const yml = read(IDENTITY_WORKFLOW);

  // GitHub starts every bash `run:` with `bash --noprofile --norc -eo pipefail
  // {0}`, so a bare failing command aborts the step before any evidence can be
  // written. The probe must therefore run as the condition of an if-statement
  // (which errexit never triggers on), capturing its status into a variable.
  const probeStep = yml.slice(yml.indexOf('Run read-only staging identity probe'));

  it('records server/gradlew as executable (mode 100755) in the git index', () => {
    // File-system permission bits are ignored on the Windows dev machines
    // (core.fileMode), and Actions runners restore mode from the index on
    // checkout, so only the index/HEAD mode decides whether `./gradlew` can be
    // executed on Linux. A non-executable wrapper masks as exit code 126.
    const out = execFileSync('git', ['ls-files', '-s', 'server/gradlew'], {
      cwd: resolve(here, '..'),
      encoding: 'utf8',
    });
    // <mode> <blob> <stage>\t<path> — the mode field must read 100755.
    expect(out.trim()).toMatch(/^100755 [0-9a-f]{40} 0\tserver\/gradlew$/);
  });

  it('keeps errexit enabled while capturing the probe status safely', () => {
    // The conditional-execution pattern required by the audit: the failing
    // command is an if-condition, not a bare statement.
    expect(probeStep).toMatch(
      /if \.\/gradlew -q execProbeState > probe-raw\.txt 2>&1; then\s*\n\s*STATUS=0\s*\n\s*else\s*\n\s*STATUS=\$\?/,);
    // errexit is not disabled to make the capture possible. Matched against
    // command lines only, so mentions of the pattern in comments don't trip it.
    expect(probeStep).not.toMatch(/^\s*(?:set \+e|set \+o errexit)\b/m);
  });

  it('writes sanitized evidence whether the probe succeeds or fails', () => {
    // Evidence generation must precede the failure exit so a failed run still
    // leaves its allowlisted summary behind for the artifact step.
    const evidenceIdx = probeStep.indexOf('tee probe-evidence.txt');
    const failureExitIdx = probeStep.indexOf('exit "$STATUS"');
    expect(evidenceIdx).toBeGreaterThan(-1);
    expect(failureExitIdx).toBeGreaterThan(-1);
    expect(evidenceIdx).toBeLessThan(failureExitIdx);
    // The completion field in the evidence file is derived from the captured
    // status, not hard-coded to ok.
    expect(probeStep).toMatch(/probe_completion=\$\{COMPLETION\}/);
    expect(probeStep).toMatch(/COMPLETION=failed/);
    expect(probeStep).toMatch(/COMPLETION=ok/);
  });

  it('deletes raw output and never uploads it as an artifact', () => {
    // The raw probe output is database inventory: deleted in the probe step and
    // absent from every artifact path.
    expect(probeStep).toMatch(/rm -f probe-raw\.txt/);
    const deleteIdx = probeStep.indexOf('rm -f probe-raw.txt');
    const failureExitIdx = probeStep.indexOf('exit "$STATUS"');
    expect(deleteIdx).toBeLessThan(failureExitIdx);
    // Only the sanitized summary is ever uploaded.
    expect(yml).toMatch(/path: server\/probe-evidence\.txt/);
    expect(yml).not.toMatch(/path:[^\n]*probe-raw/);
  });

  it('blocks a failed probe from being reported as a green workflow', () => {
    // The step exits with the probe's own status, and the message pattern is
    // emitted under `::error::` so the job visibly fails rather than gliding past.
    expect(probeStep).toMatch(/if \[ "\$STATUS" -ne 0 \]; then\s*\n[\s\S]*?exit "\$STATUS"/);
    expect(probeStep).toContain('::error::Read-only identity probe failed');
    // No place may assume success: the workflow must not swallow the gradle
    // exit with a bare `|| true` inside the probe step.
    expect(probeStep).not.toMatch(/\.\/gradlew[^\n]*\|\| true/);
  });
});
