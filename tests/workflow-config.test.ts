import { execFileSync, spawnSync } from 'node:child_process';
import {
  chmodSync,
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { extractProbeStep, identityWorkflowText, parseEvidence } from './helpers/probe-step.ts';

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
// The evidence rules live in a script so they can run without GitHub; the
// workflow only invokes it. Tests read both and check each side of that split.
const EVIDENCE_SCRIPT = readFileSync(
  resolve(here, '../server/tools/probe-evidence.sh'),
  'utf8',
);

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

  it('installs Temurin JDK 21 before the Gradle identity probe', () => {
    const yml = read(IDENTITY_WORKFLOW);
    expect(yml).toContain('uses: actions/setup-java@v6.1.0');
    expect(yml).toContain('distribution: temurin');
    expect(yml).toMatch(/java-version:\s*'21'/);
    expect(yml.indexOf('Set up JDK 21 for Gradle')).toBeLessThan(
      yml.indexOf('execProbeState'),
    );
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
    const evidenceIdx = probeStep.indexOf('bash tools/probe-evidence.sh');
    const failureExitIdx = probeStep.indexOf('exit "$STATUS"');
    expect(evidenceIdx).toBeGreaterThan(-1);
    expect(failureExitIdx).toBeGreaterThan(-1);
    expect(evidenceIdx).toBeLessThan(failureExitIdx);
    // The completion field is derived from the captured status, not hard-coded
    // to ok, and the script writes the evidence file on both outcomes.
    expect(EVIDENCE_SCRIPT).toContain('probe_completion=${completion}');
    expect(EVIDENCE_SCRIPT).toContain('completion=failed');
    expect(EVIDENCE_SCRIPT).toContain('completion=ok');
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

  it('fails a zero probe exit when the sanitized evidence is not a confirmation', () => {
    // The helper writes the evidence file and exits 0 even for invalid evidence,
    // so a zero probe status alone must not be able to pass the job. The step
    // reads `probe_completion` back and requires exactly one accepted line of
    // every kind; a duplicate or contradictory line is rejected as ambiguous.
    const evidenceIdx = probeStep.indexOf('bash tools/probe-evidence.sh');
    const readbackIdx = probeStep.indexOf('evidence_read_only_confirmed=$(readback');
    const rejectedIdx = probeStep.indexOf('::error::Read-only evidence rejected');
    expect(evidenceIdx).toBeGreaterThan(-1);
    expect(readbackIdx, 'the step must read the evidence back').toBeGreaterThan(evidenceIdx);
    expect(rejectedIdx, 'the step must reject unconfirmed evidence').toBeGreaterThan(readbackIdx);
    for (const rule of [
      "completion_ok=$(readback '^probe_completion=ok$')",
      "evidence_read_only_confirmed=$(readback '^read_only_transaction=server_confirmed$')",
      "evidence_probe_status_ok=$(readback '^probe_status=ok$')",
    ]) {
      expect(probeStep).toContain(rule);
    }
    // The rejection happens after the raw inventory is deleted and after the
    // probe's own non-zero status has been preserved.
    expect(probeStep.indexOf('rm -f probe-raw.txt')).toBeLessThan(rejectedIdx);
    expect(probeStep.indexOf('exit "$STATUS"')).toBeLessThan(rejectedIdx);
    expect(probeStep).toMatch(/if \[ "\$completion_lines" -ne 1 \][\s\S]*?exit 1/);
  });

  // ---- read-only evidence must be derived, never asserted -----------------

  it('never asserts read_only_transaction unconditionally', () => {
    // Regression: the step used to `echo "read_only_transaction=enforced"`
    // regardless of what the probe reported, so a refused target or a failed
    // connection produced evidence claiming a protection it never applied.
    expect(yml).not.toMatch(/^\s*echo "read_only_transaction=enforced"/m);
    expect(probeStep).not.toContain('echo "read_only_transaction=');
  });

  it('extracts only an exact server_confirmed line and defaults to not_confirmed', () => {
    // Exactly one read_only_transaction line must be present and it must be the
    // exact server-confirmed line. `enforced`, `on`, `off`, a missing line and
    // duplicates are all not_confirmed; nothing is rewritten.
    expect(EVIDENCE_SCRIPT).toContain(
      'if [ "$read_only_lines" -eq 1 ] && [ "$confirmed_lines" -eq 1 ]; then',
    );
    expect(EVIDENCE_SCRIPT).toContain(
      'read_only_transaction=server_confirmed[[:space:]]*$',
    );
    expect(EVIDENCE_SCRIPT).toContain('read_only=read_only_transaction=not_confirmed');
    expect(EVIDENCE_SCRIPT).toContain('echo "$read_only"');
    // The step never emits read_only_transaction itself; only the script does.
    expect(probeStep).not.toMatch(/echo\s+"?read_only_transaction=/);
  });

  it('deletes the raw output before the step exits', () => {
    const deleteIdx = probeStep.indexOf('rm -f probe-raw.txt');
    const failureExitIdx = probeStep.indexOf('exit "$STATUS"');
    expect(deleteIdx).toBeGreaterThan(-1);
    expect(failureExitIdx).toBeGreaterThan(-1);
    expect(deleteIdx).toBeLessThan(failureExitIdx);
  });

  it('keeps the sanitized evidence field allowlist', () => {
    // Only these probe fields may be copied into the evidence file.
    expect(EVIDENCE_SCRIPT).toMatch(
      /grep -E '\^\[\[:space:\]\]\+\(database\|supabase_migrations_exists\|migration_history\)='/,
    );
    // The error category is selected separately from a fixed safe token and
    // emitted only after validation; it is not copied blindly by grep.
    expect(EVIDENCE_SCRIPT).toContain('echo "error_category=${error_category}"');
    // probe_status is not copied from the raw output: it is emitted once from
    // the validated counts, so a duplicate cannot make the evidence ambiguous.
    expect(EVIDENCE_SCRIPT).toContain('echo "probe_status=${probe_status_value}"');
    expect(EVIDENCE_SCRIPT).toContain('probe_status_value=error');
    // The raw file is never copied or dumped into the evidence.
    expect(EVIDENCE_SCRIPT).not.toMatch(/cat probe-raw\.txt/);
    expect(EVIDENCE_SCRIPT).not.toMatch(/tee probe-evidence\.txt < probe-raw\.txt/);
    expect(probeStep).not.toMatch(/cat probe-raw\.txt/);
  });
});

/**
 * Behavioural coverage of the probe step's evidence generation.
 *
 * The step's own shell body is executed by bash (with `-eo pipefail`, as
 * Actions does) against a stub `./gradlew`, in a disposable temp directory.
 * Nothing here contacts a database, a network or GitHub: the stub only prints
 * text the real ProbeState would print.
 */
describe('probe step evidence generation (bash, stub probe)', () => {
  const harness = extractProbeStep(identityWorkflowText(workflowsDir));
  const bash = findBash();
  const run = bash ? it : it.skip;

  function findBash(): string | null {
    const candidates = [
      'C:\\Program Files\\Git\\bin\\bash.exe',
      'C:\\Program Files\\Git\\usr\\bin\\bash.exe',
      '/bin/bash',
      '/usr/bin/bash',
    ];
    for (const candidate of candidates) {
      if (!existsSync(candidate)) continue;
      const probe = spawnSync(candidate, ['--version'], { encoding: 'utf8' });
      if (probe.status === 0 && /bash/.test(probe.stdout)) return candidate;
    }
    return null;
  }

  interface StepRun {
    status: number | null;
    stdout: string;
    evidence: Record<string, string> | null;
    rawExists: boolean;
    dir: string;
  }

  /** Execute the step body with a stub probe that prints `output` and exits `status`. */
  function runStep(probeOutput: string, probeStatus: number): StepRun {
    const dir = mkdtempSync(join(tmpdir(), 'probe-step-'));
    const serverDir = join(dir, 'server');
    // The step starts with `cd server`; this directory holds the stub gradlew.
    mkdirSync(serverDir, { recursive: true });
    // The step invokes `bash tools/probe-evidence.sh`, so the script it runs
    // must be present exactly where the checkout would put it.
    mkdirSync(join(serverDir, 'tools'), { recursive: true });
    copyFileSync(
      resolve(here, '../server/tools/probe-evidence.sh'),
      join(serverDir, 'tools', 'probe-evidence.sh'),
    );
    // The stub reads its output from an absolute path: `$0` and `pwd` are not
    // reliable under Git Bash's MSYS path translation on Windows.
    writeFileSync(join(dir, 'probe-output.txt'), probeOutput, 'utf8');
    const stubPath = join(serverDir, 'gradlew');
    writeFileSync(stubPath, stubScript(join(dir, 'probe-output.txt'), probeStatus), 'utf8');
    // The step invokes `./gradlew`, so the stub must carry the execute bit. On
    // Windows the bit is meaningless (hence local runs passing), but on Linux a
    // non-executable stub makes bash exit 126 -- the very failure this suite
    // exists to catch, and a false negative if not set.
    chmodSync(stubPath, 0o755);
    const scriptPath = join(dir, 'step.sh');
    writeFileSync(scriptPath, harness.script, 'utf8');

    const executed = spawnSync(bash as string, ['-eo', 'pipefail', scriptPath], {
      cwd: dir,
      encoding: 'utf8',
      // Git Bash's own directory is not always on PATH on Windows, and the step
      // calls `bash tools/probe-evidence.sh` by name.
      env: {
        PATH: `${dirname(bash as string)}${delimiter}${process.env.PATH ?? ''}`,
        HOME: process.env.HOME ?? '',
      },
      // A wedged child must not hang the suite forever.
      timeout: 60_000,
    });

    const evidencePath = join(serverDir, harness.evidenceFile);
    const evidence = existsSync(evidencePath)
      ? parseEvidence(readFileSync(evidencePath, 'utf8'))
      : null;
    return {
      status: executed.status,
      stdout: `${executed.stdout ?? ''}${executed.stderr ?? ''}`,
      evidence,
      rawExists: existsSync(join(serverDir, harness.rawFile)),
      dir,
    };
  }

  /** A stub `gradlew` that prints the output file and returns a status. */
  function stubScript(outputFile: string, status: number): string {
    return [
      '#!/usr/bin/env bash',
      `cat ${shellQuote(outputFile)}`,
      `exit ${status}`,
      '',
    ].join('\n');
  }

  function shellQuote(text: string): string {
    return `'${text.replace(/'/g, `'\\''`)}'`;
  }

  run('reports server_confirmed when the probe confirms a read-only session', () => {
    const result = runStep(
      [
        '== read-only state probe ==',
        '  target host: db.internal:5432/postgres',
        '  read_only_transaction=server_confirmed',
        '  database=postgres',
        '  supabase_migrations_exists=true',
        '  migration_history=9 rows from supabase_migrations.schema_migrations',
        '-- public tables --',
        '  internal_table_a',
        '  internal_table_b',
        '  probe_status=ok',
      ].join('\n'),
      0,
    );
    try {
      expect(result.status, result.stdout).toBe(0);
      const evidence = result.evidence;
      expect(evidence).not.toBeNull();
      expect(evidence!).toEqual({
        target_project_reference: 'unset',
        username_suffix_validation: 'PASS',
        read_only_transaction: 'server_confirmed',
        database: 'postgres',
        supabase_migrations_exists: 'true',
        migration_history: '9 rows from supabase_migrations.schema_migrations',
        probe_status: 'ok',
        probe_completion: 'ok',
      });
      // The inventory (table list, host) never reaches the published evidence.
      expect(result.stdout).not.toContain('internal_table');
      expect(result.stdout).not.toContain('db.internal');
    } finally {
      rmSync(result.dir, { recursive: true, force: true });
    }
  });

  run('reports not_confirmed when the probe never confirmed read-only mode', () => {
    // A failed connection: the probe reports its sanitized failure and exits 5.
    const result = runStep(
      [
        '== read-only state probe ==',
        '  target host: db.internal:6543/postgres',
        '  probe_status=error',
        '  error_category=connection_failed',
        '  error_detail=sqlstate=08001 message=Connection to <host> refused.',
      ].join('\n'),
      5,
    );
    try {
      expect(result.status).toBe(5);
      expect(result.evidence).not.toBeNull();
      const evidence = result.evidence!;
      expect(evidence.read_only_transaction).toBe('not_confirmed');
      expect(evidence.probe_completion).toBe('failed');
      expect(evidence.probe_status).toBe('error');
      expect(evidence.error_category).toBe('connection_failed');
      expect(result.stdout).toContain('::error::Read-only identity probe failed (exit 5)');
    } finally {
      rmSync(result.dir, { recursive: true, force: true });
    }
  });

  run('rejects the legacy `enforced` value instead of rewriting it', () => {
    // A binary built before the server-side check only printed `enforced`; that
    // value never demonstrated the protection, so it is not a confirmation.
    const result = runStep(
      '== read-only state probe ==\n  read_only_transaction=enforced\n  probe_status=ok',
      0,
    );
    try {
      expect(result.status, result.stdout).toBe(1);
      expect(result.evidence?.read_only_transaction).toBe('not_confirmed');
      expect(result.evidence?.probe_completion).toBe('failed');
      expect(result.stdout).toContain('::error::Read-only evidence rejected');
      expect(result.rawExists).toBe(false);
    } finally {
      rmSync(result.dir, { recursive: true, force: true });
    }
  });

  run('does not accept a near-miss confirmation line', () => {
    // Only the exact line confirms; a prefix, a different value or a line with
    // leading content must not be read as enforcement.
    const result = runStep(
      [
        '== read-only state probe ==',
        '  read_only_transaction=enforced_by_convention',
        '  note: read_only_transaction=enforced',
        '  database=postgres',
        '  probe_status=ok',
      ].join('\n'),
      0,
    );
    try {
      expect(result.status, result.stdout).toBe(1);
      expect(result.evidence?.read_only_transaction).toBe('not_confirmed');
      expect(result.stdout).toContain('::error::Read-only evidence rejected');
    } finally {
      rmSync(result.dir, { recursive: true, force: true });
    }
  });

  run('deletes the raw output on success and on failure', () => {
    for (const [out, status] of [
      ['== read-only state probe ==\n  read_only_transaction=server_confirmed\n  probe_status=ok', 0],
      ['== read-only state probe ==\n  probe_status=error\n  error_category=connection_failed', 7],
    ] as const) {
      const result = runStep(out, status);
      try {
        expect(result.status).toBe(status);
        expect(result.rawExists, 'probe-raw.txt must be deleted before the step exits').toBe(false);
        // The raw file is deleted; the stub's own inputs and the allowlisted
        // summary are all that remain, and probe-raw.txt is not among them.
        expect(readdirSync(join(result.dir, 'server')).sort()).toEqual([
          'gradlew',
          'probe-evidence.txt',
          'tools',
        ]);
        const evidence = result.evidence!;
        expect(Object.keys(evidence).sort()).toEqual(
          status === 0
            ? ['probe_completion', 'probe_status', 'read_only_transaction', 'target_project_reference', 'username_suffix_validation']
            : ['error_category', 'probe_completion', 'probe_status', 'read_only_transaction', 'target_project_reference', 'username_suffix_validation'],
        );
      } finally {
        rmSync(result.dir, { recursive: true, force: true });
      }
    }
  });

  run('produces sanitized evidence even when the probe printed nothing', () => {
    // Exit 126 (the historical gradlew-not-executable failure) still leaves an
    // artifact behind, so evidence never depends on the probe having run.
    const result = runStep('', 126);
    try {
      expect(result.status).toBe(126);
      expect(result.evidence?.probe_completion).toBe('failed');
      expect(result.evidence?.read_only_transaction).toBe('not_confirmed');
      expect(result.evidence?.error_category).toBe('execution_failed_without_probe_category');
      expect(result.rawExists).toBe(false);
    } finally {
      rmSync(result.dir, { recursive: true, force: true });
    }
  });

  run('classifies a Java 21 toolchain failure without publishing raw Gradle output', () => {
    const rawOutput = [
      '> Task :compileProbeState FAILED',
      'error: invalid source release: 21',
      'sensitive runner path: /home/runner/work/private-project',
      'credential-like-value=must-never-appear-in-evidence',
    ].join('\n');
    const result = runStep(rawOutput, 1);
    try {
      expect(result.status).toBe(1);
      expect(result.evidence?.error_category).toBe('java_toolchain_mismatch');
      expect(result.evidence?.probe_completion).toBe('failed');
      expect(result.stdout).not.toContain('invalid source release');
      expect(result.stdout).not.toContain('/home/runner/work/private-project');
      expect(result.stdout).not.toContain('must-never-appear-in-evidence');
      expect(result.rawExists).toBe(false);
    } finally {
      rmSync(result.dir, { recursive: true, force: true });
    }
  });

  run('preserves the probe exit code rather than collapsing it to 1', () => {
    for (const status of [2, 3, 4, 5]) {
      const result = runStep('  probe_status=error', status);
      try {
        expect(result.status).toBe(status);
      } finally {
        rmSync(result.dir, { recursive: true, force: true });
      }
    }
  });

  /**
   * The strict-evidence integration contract.
   *
   * A probe process that exits 0 is not evidence: the helper records the
   * failure in the summary and still exits 0 so the artifact survives. These
   * cases run the REAL workflow step body under `bash -eo pipefail` against a
   * stub probe and require the step itself to fail.
   */
  const exactConfirmation = '  read_only_transaction=server_confirmed';
  const okStatus = '  probe_status=ok';
  const probeHeader = ['== read-only state probe ==', '  target host: db.internal:5432/postgres'];
  const probeInventory = ['  database=postgres', '  password=sup3rs3cret', '  table=public.profiles'];
  const rawWith = (...lines: string[]) => [...probeHeader, ...lines, ...probeInventory].join('\n');

  interface InvalidEvidenceCase {
    name: string;
    output: string;
    probeStatus: number;
    expectedExit: number;
  }

  const invalidEvidenceCases: InvalidEvidenceCase[] = [
    {
      name: 'legacy enforced value',
      output: rawWith('  read_only_transaction=enforced', okStatus),
      probeStatus: 0,
      expectedExit: 1,
    },
    {
      name: 'on value',
      output: rawWith('  read_only_transaction=on', okStatus),
      probeStatus: 0,
      expectedExit: 1,
    },
    {
      name: 'off value',
      output: rawWith('  read_only_transaction=off', okStatus),
      probeStatus: 0,
      expectedExit: 1,
    },
    {
      name: 'missing confirmation',
      output: rawWith(okStatus),
      probeStatus: 0,
      expectedExit: 1,
    },
    {
      name: 'duplicate confirmation',
      output: rawWith(exactConfirmation, exactConfirmation, okStatus),
      probeStatus: 0,
      expectedExit: 1,
    },
    {
      name: 'unexpected confirmation value',
      output: rawWith('  read_only_transaction=server_confirmed_ish', okStatus),
      probeStatus: 0,
      expectedExit: 1,
    },
    {
      name: 'duplicate probe_status=ok',
      output: rawWith(exactConfirmation, okStatus, okStatus),
      probeStatus: 0,
      expectedExit: 1,
    },
    {
      name: 'probe_status=error',
      output: rawWith(exactConfirmation, '  probe_status=error'),
      probeStatus: 0,
      expectedExit: 1,
    },
    {
      name: 'exact confirmation with a non-zero probe exit',
      output: rawWith(exactConfirmation, okStatus),
      probeStatus: 6,
      expectedExit: 6,
    },
  ];

  const allowlistedKeys = [
    'database',
    'error_category',
    'migration_history',
    'probe_completion',
    'probe_status',
    'read_only_transaction',
    'supabase_migrations_exists',
    'target_project_reference',
    'username_suffix_validation',
  ];

  for (const testCase of invalidEvidenceCases) {
    run(`fails the workflow step on invalid evidence: ${testCase.name}`, () => {
      const result = runStep(testCase.output, testCase.probeStatus);
      try {
        // Zero probe exit is not enough; the step's own status must fail, and
        // the probe's status must be preserved when it was already non-zero.
        expect(result.status, result.stdout).toBe(testCase.expectedExit);
        expect(result.status).not.toBe(0);
        // Sanitized evidence is still generated and never claims success.
        expect(result.evidence, 'sanitized evidence must still be generated').not.toBeNull();
        expect(result.evidence!.probe_completion).toBe('failed');
        // probe_status is accepted only when exactly one ok line and exit 0;
        // the confirmation failures leave it `ok` and are caught by the
        // completion/confirmation gate instead.
        const statusWasAccepted =
          testCase.probeStatus === 0 &&
          !['duplicate probe_status=ok', 'probe_status=error'].includes(testCase.name);
        expect(result.evidence!.probe_status).toBe(statusWasAccepted ? 'ok' : 'error');
        // Only allowlisted fields are present.
        expect(Object.keys(result.evidence!).every((key) => allowlistedKeys.includes(key))).toBe(true);
        // Raw inventory is deleted before the step exits.
        expect(result.rawExists, 'probe-raw.txt must be deleted before the step exits').toBe(false);
        // The failure is reported as an Actions error, never as success.
        if (testCase.probeStatus === 0) {
          expect(result.stdout).toContain('::error::Read-only evidence rejected');
        } else {
          expect(result.stdout).toContain(
            `::error::Read-only identity probe failed (exit ${testCase.probeStatus})`,
          );
        }
        // Raw inventory, host and secrets are never printed.
        expect(result.stdout).not.toContain('sup3rs3cret');
        expect(result.stdout).not.toContain('public.profiles');
        expect(result.stdout).not.toContain('db.internal');
      } finally {
        rmSync(result.dir, { recursive: true, force: true });
      }
    });
  }

  run('keeps errexit enabled and runs the probe before deleting anything', () => {
    expect(harness.script).not.toMatch(/^\s*set \+e\b/m);
    const probeIdx = harness.script.indexOf('execProbeState');
    const deleteIdx = harness.script.indexOf('rm -f probe-raw.txt');
    expect(probeIdx).toBeGreaterThan(-1);
    expect(deleteIdx).toBeGreaterThan(probeIdx);
  });
});


/**
 * Finding 4 (independent review): the staging workflow must not depend solely on
 * an external Environment setting the reviewer could not inspect. The job that
 * consumes staging credentials is pinned to refs/heads/main, so a manual
 * dispatch that selects another branch is skipped before the environment, its
 * vars or its secrets are resolved.
 */
describe('staging workflow main-only release gate', () => {
  const yml = read(STAGING_WORKFLOW).replace(/\r\n/g, '\n');

  it('keeps the manual-only trigger', () => {
    expect(yml).toMatch(/^on:\s*\n\s*workflow_dispatch:/m);
    expect(yml).not.toMatch(/^\s+(push|pull_request|schedule|workflow_run):/m);
  });

  it('restricts the job that holds staging credentials to refs/heads/main', () => {
    // Job-level condition: evaluated before the environment, its secrets and any
    // step. A late script check after secrets are consumed would not satisfy this.
    expect(yml).toMatch(/^    if: github\.ref == 'refs\/heads\/main'$/m);
    const jobIdx = yml.indexOf('\n  apply:\n');
    const gateIdx = yml.indexOf("    if: github.ref == 'refs/heads/main'");
    const stepsIdx = yml.indexOf('\n    steps:\n');
    expect(jobIdx).toBeGreaterThan(-1);
    expect(gateIdx).toBeGreaterThan(jobIdx);
    expect(gateIdx).toBeLessThan(stepsIdx);
    // The restriction is the job condition, not a shell test inside a step.
    expect(yml).not.toMatch(/if \[+ "\$GITHUB_REF"/);
  });

  it('keeps the staging environment on the restricted job only', () => {
    expect(yml).toContain('environment: pos-staging-admin');
    expect(yml).not.toContain('environment: pos-dev-admin');
    expect(read(DEV_WORKFLOW)).not.toContain('pos-staging-admin');
  });

  it('uploads only sanitized evidence, never a raw transcript', () => {
    expect(yml).toContain('path: server/build/probe-readonly-evidence.txt');
    expect(yml).not.toMatch(/probe-readonly-\*\.log/);
    expect(yml).not.toMatch(/Tee-Object/);
    expect(read(DEV_WORKFLOW)).toContain('path: server/build/probe-readonly-evidence.txt');
  });

  it('documents that the GitHub Environment protections are externally verified', () => {
    // The YAML cannot prove required reviewers, prevent-self-review, the
    // deployment branch policy or secret scoping: those live in GitHub settings.
    expect(yml).toMatch(/NOT verified by this file/);
    expect(yml).toMatch(/repository administrator must confirm them independently/);
  });
});
