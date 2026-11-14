import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Caller-level tests for F-02.
 *
 * These execute the REAL functions from server/tools/probe-readonly.ps1
 * (Clear-SensitivePaths, Remove-SensitivePath, Set-ProbeFailure) in a child
 * PowerShell process, driving the same code path the script uses. They do not
 * re-implement the helper, and they do not run the database probe.
 *
 * Set-ProbeFailure calls `exit`, so each scenario runs in its own process and
 * its exit code is the assertion.
 */

const repoRoot = resolve(__dirname, '..');
const scriptPath = join(repoRoot, 'server', 'tools', 'probe-readonly.ps1');

function findPowerShell(): string | null {
  const candidates = process.platform === 'win32' ? ['powershell', 'pwsh'] : ['pwsh', 'powershell'];
  for (const candidate of candidates) {
    const probe = spawnSync(candidate, ['-NoProfile', '-Command', '$PSVersionTable.PSVersion.Major'], {
      encoding: 'utf8',
      timeout: 60_000,
    });
    if (probe.status === 0) return candidate;
  }
  return null;
}

const powershell = findPowerShell();

// A silent skip here would let CI report green while none of these scenarios
// ran. GitHub's ubuntu-latest and windows-latest images both ship pwsh, so an
// absent PowerShell is a broken environment, not an acceptable outcome: the
// suite fails loudly instead.
if (!powershell) {
  throw new Error(
    'PowerShell (pwsh) is required for this suite and was not found. ' +
      'GitHub ubuntu-latest and windows-latest both preinstall it, so this ' +
      'environment cannot validate the probe orchestration.',
  );
}
const run = it;

/** Load only the script's function definitions; its main body never runs. */
function loaderSource(): string {
  return [
    '$ErrorActionPreference = "Stop"',
    `$ast = [System.Management.Automation.Language.Parser]::ParseFile('${scriptPath.replace(/'/g, "''")}', [ref]$null, [ref]$null)`,
    '$funcs = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true)',
    'foreach ($f in $funcs) { . ([scriptblock]::Create($f.Extent.Text)) }',
    'foreach ($v in $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] }, $true)) {',
    '  if ($v.Left.Extent.Text -like "*CleanupFailures" -or $v.Left.Extent.Text -like "*CleanupOnlyExitCode") {',
  '    . ([scriptblock]::Create($v.Extent.Text))',
  '  }',
    '}',
    'if (-not (Get-Command Set-ProbeFailure -ErrorAction SilentlyContinue)) { throw "Set-ProbeFailure missing" }',
    'if (-not (Get-Command Clear-SensitivePaths -ErrorAction SilentlyContinue)) { throw "Clear-SensitivePaths missing" }',
  ].join('\n');
}

interface Case {
  status: number | null;
  output: string;
}

function runCase(body: string, work: string, name: string): Case {
  const file = join(work, `${name}.ps1`);
  writeFileSync(file, `${loaderSource()}\n${body}\n`, 'utf8');
  const r = spawnSync(powershell as string, ['-NoProfile', '-NonInteractive', '-File', file], {
    encoding: 'utf8',
    timeout: 120_000,
  });
  return { status: r.status, output: `${r.stdout ?? ''}${r.stderr ?? ''}` };
}

function workDir(): string {
  return mkdtempSync(join(tmpdir(), 'cleanup-caller-'));
}

/** A path that cannot be deleted: a non-empty directory removed without -Recurse. */
function stuckDir(base: string, name: string): string {
  const dir = join(base, name);
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'ca.pem'), 'CERTIFICATE-CONTENTS-MUST-NOT-LEAK', 'utf8');
  return dir;
}

interface DeferredExitPlacement {
  dockerCatch: number;
  certFinally: number;
  certFinallyEnd: number;
  certCleanup: number;
  deferredGuard: number;
  deferredExit: number;
  violations: string[];
}

/** Index of the line that closes the brace block opened on `start`. */
function closingLine(lines: string[], start: number): number {
  let depth = 1;
  for (let i = start + 1; i < lines.length; i++) {
    const line = lines[i] ?? '';
    for (const ch of line) {
      if (ch === '{') depth++;
      else if (ch === '}') depth--;
    }
    if (depth === 0) return i;
  }
  return -1;
}

/**
 * Locate the deferred Docker-failure exit and the certificate-cleanup finally it
 * must follow, structurally.
 *
 * The block is found from the catch that records $dockerFailure and from the
 * certificate-cleanup call INSIDE that finally. The first `} finally {` in the
 * file is the SHA-256 disposal, so using it satisfied the ordering assertion
 * while the exit could still be moved above the cleanup it depends on.
 */
function analyseDeferredExit(text: string): DeferredExitPlacement {
  const lines = text.split('\n');
  const fnStart = lines.findIndex((line) => line.includes('function Set-ProbeFailure'));
  let fnEnd = -1;
  for (let i = fnStart + 1; fnStart > -1 && i < lines.length; i++) {
    const line = lines[i] ?? '';
    if (!/^\s/.test(line) && line.trim() === '}') {
      fnEnd = i;
      break;
    }
  }

  const deferredExit =
    fnEnd > -1
      ? lines.findIndex((line, i) => i > fnEnd && line.trim() === 'exit $script:CleanupOnlyExitCode')
      : -1;
  const dockerCatch = lines.findIndex((line) => line.includes('$dockerFailure = $_'));
  const certFinally =
    dockerCatch > -1
      ? lines.findIndex((line, i) => i > dockerCatch && line.trim() === '} finally {')
      : -1;
  const certFinallyEnd = certFinally > -1 ? closingLine(lines, certFinally) : -1;
  const certCleanup =
    certFinally > -1 && certFinallyEnd > -1
      ? lines.findIndex(
          (line, i) =>
            i > certFinally &&
            i < certFinallyEnd &&
            line.includes('temporary certificate directory') &&
            line.includes('Label'),
        )
      : -1;
  const deferredGuard = lines.findIndex((line) => line.includes('if ($null -ne $dockerFailure)'));

  const violations: string[] = [];
  if (fnEnd < 0) violations.push('Set-ProbeFailure has no closing brace, so its exits cannot be excluded');
  if (dockerCatch < 0) violations.push('the catch does not record $dockerFailure, so no deferred failure is captured');
  if (certFinally < 0) violations.push('no finally block follows the Docker catch');
  if (certFinallyEnd < 0) violations.push('the certificate-cleanup finally block does not close');
  if (certCleanup < 0) violations.push('the finally block does not clear the temporary certificate directory');
  if (deferredGuard < 0) violations.push('the deferred exit is not guarded by $dockerFailure');
  if (deferredExit < 0) violations.push('no exit outside Set-ProbeFailure uses $script:CleanupOnlyExitCode');
  if (deferredGuard > -1 && deferredExit > -1 && deferredExit < deferredGuard) {
    violations.push('the deferred exit occurs before the $dockerFailure guard');
  }
  if (certFinallyEnd > -1 && deferredExit > -1 && deferredExit < certFinallyEnd) {
    violations.push('the deferred exit occurs before the certificate-cleanup finally block completes');
  }
  return { dockerCatch, certFinally, certFinallyEnd, certCleanup, deferredGuard, deferredExit, violations };
}

/** Test-only mutation: move the deferred exit above the try/finally it depends on. */
function moveDeferredExitBeforeCertificateCleanup(text: string): string {
  const lines = text.split('\n');
  const start = lines.findIndex((line) => line.includes('if ($null -ne $dockerFailure)'));
  if (start < 0) return text;
  const end = closingLine(lines, start);
  if (end < 0) return text;
  const anchor = lines.findIndex((line) => line.includes('$dockerFailure = $null'));
  if (anchor < 0 || anchor > start) return text;
  const moved = lines.slice(start, end + 1);
  const rest = [...lines.slice(0, start), ...lines.slice(end + 1)];
  return [...rest.slice(0, anchor + 1), ...moved, ...rest.slice(anchor + 1)].join('\n');
}

describe('F-02 caller: cleanup failures reach the final exit status', () => {
  // CASE 1 - main succeeds, cleanup succeeds -> exit 0
  run('case 1: success and clean cleanup exits 0', () => {
    const work = workDir();
    try {
      const transcript = join(work, 'raw.log');
      writeFileSync(transcript, 'TRANSCRIPT-CONTENTS', 'utf8');
      const r = runCase(
        [
          `[void](Clear-SensitivePaths -Targets @(@{ Path = '${transcript.replace(/'/g, "''")}'; Label = 'raw transcript' }))`,
          `Set-ProbeFailure -PrimaryStatus 0 -Context 'probe'`,
        ].join('\n'),
        work,
        'case1',
      );
      expect(r.status, r.output).toBe(0);
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });

  // CASE 2a - main succeeds, transcript cleanup fails -> non-zero
  run('case 2a: successful probe with a stuck transcript exits non-zero', () => {
    const work = workDir();
    try {
      const stuck = stuckDir(work, 'raw.log');
      const r = runCase(
        [
          `[void](Clear-SensitivePaths -Targets @(@{ Path = '${stuck.replace(/'/g, "''")}'; Label = 'raw transcript' }))`,
          `Set-ProbeFailure -PrimaryStatus 0 -Context 'probe'`,
        ].join('\n'),
        work,
        'case2a',
      );
      expect(r.status, r.output).toBe(4);
      expect(r.output).toContain('raw transcript');
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });

  // CASE 2b - main succeeds, certificate directory cleanup fails -> non-zero
  run('case 2b: successful probe with a stuck certificate directory exits non-zero', () => {
    const work = workDir();
    try {
      // Deleted without -Recurse in the script's own call shape.
      const stuck = stuckDir(work, 'certdir');
      const r = runCase(
        [
          `[void](Clear-SensitivePaths -Targets @(@{ Path = '${stuck.replace(/'/g, "''")}'; Label = 'temporary certificate directory' }))`,
          `Set-ProbeFailure -PrimaryStatus 0 -Context 'probe'`,
        ].join('\n'),
        work,
        'case2b',
      );
      expect(r.status, r.output).toBe(4);
      expect(r.output).toContain('temporary certificate directory');
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });

  // CASE 4 - main succeeds, several cleanups fail -> all attempted, non-zero
  run('case 4: multiple cleanup failures are all attempted and reported', () => {
    const work = workDir();
    try {
      const stuckA = stuckDir(work, 'a.log');
      const stuckB = stuckDir(work, 'b.log');
      const ok = join(work, 'c.log');
      writeFileSync(ok, 'x', 'utf8');
      const r = runCase(
        [
          `$failures = Clear-SensitivePaths -Targets @(`,
          `  @{ Path = '${stuckA.replace(/'/g, "''")}'; Label = 'raw transcript' },`,
          `  @{ Path = '${stuckB.replace(/'/g, "''")}'; Label = 'certificate copy' },`,
          `  @{ Path = '${ok.replace(/'/g, "''")}'; Label = 'third path' })`,
          `Write-Host ("COUNT=" + $failures.Count)`,
          `Set-ProbeFailure -PrimaryStatus 0 -Context 'probe'`,
        ].join('\n'),
        work,
        'case4',
      );
      // Both stuck paths are reported, and the third was still attempted.
      expect(r.output).toContain('raw transcript');
      expect(r.output).toContain('certificate copy');
      expect(r.status, r.output).toBe(4);
      // The third path was removed, so it is not reported as a failure.
      expect(r.output).not.toContain('third path');
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });

  // CASE 3 - main fails, cleanup succeeds -> original status preserved
  run('case 3: a failed probe with clean cleanup keeps its own exit code', () => {
    const work = workDir();
    try {
      const transcript = join(work, 'raw.log');
      writeFileSync(transcript, 'TRANSCRIPT-CONTENTS', 'utf8');
      const r = runCase(
        [
          `[void](Clear-SensitivePaths -Targets @(@{ Path = '${transcript.replace(/'/g, "''")}'; Label = 'raw transcript' }))`,
          `Set-ProbeFailure -PrimaryStatus 42 -Context 'psql'`,
        ].join('\n'),
        work,
        'case3',
      );
      expect(r.status, r.output).toBe(42);
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });

  // CASE 4b - main fails, cleanup also fails -> primary preserved, cleanup reported
  run('case 4b: a failed probe with a stuck transcript keeps the primary status', () => {
    const work = workDir();
    try {
      const stuck = stuckDir(work, 'raw.log');
      const r = runCase(
        [
          `[void](Clear-SensitivePaths -Targets @(@{ Path = '${stuck.replace(/'/g, "''")}'; Label = 'raw transcript' }))`,
          `Set-ProbeFailure -PrimaryStatus 42 -Context 'psql'`,
        ].join('\n'),
        work,
        'case4b',
      );
      // The original status wins; the cleanup failure is reported alongside it.
      expect(r.status, r.output).toBe(42);
      expect(r.output).toContain('raw transcript');
      expect(r.output).toContain('42');
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });

  // TEST 7 - a failure does not stop later cleanups
  run('case 7: cleanup continues after a failure', () => {
    const work = workDir();
    try {
      const stuck = stuckDir(work, 'first.log');
      const second = join(work, 'second.log');
      writeFileSync(second, 'x', 'utf8');
      const r = runCase(
        [
          `[void](Clear-SensitivePaths -Targets @(`,
          `  @{ Path = '${stuck.replace(/'/g, "''")}'; Label = 'first' },`,
          `  @{ Path = '${second.replace(/'/g, "''")}'; Label = 'second' }))`,
          `if (Test-Path '${second.replace(/'/g, "''")}') { "SECOND_PRESENT" } else { "SECOND_GONE" }`,
          `Set-ProbeFailure -PrimaryStatus 0 -Context 'probe'`,
        ].join('\n'),
        work,
        'case7',
      );
      expect(r.output).toContain('SECOND_GONE');
      expect(r.status, r.output).toBe(4);
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });

  // TEST 8 - diagnostics never contain file contents
  run('case 8: cleanup diagnostics never contain file contents', () => {
    const work = workDir();
    try {
      const stuck = stuckDir(work, 'secret.log');
      const r = runCase(
        [
          `[void](Clear-SensitivePaths -Targets @(@{ Path = '${stuck.replace(/'/g, "''")}'; Label = 'raw transcript' }))`,
          `Set-ProbeFailure -PrimaryStatus 0 -Context 'probe'`,
        ].join('\n'),
        work,
        'case8',
      );
      expect(r.status, r.output).toBe(4);
      expect(r.output).not.toContain('CERTIFICATE-CONTENTS-MUST-NOT-LEAK');
      expect(r.output).not.toContain('TRANSCRIPT-CONTENTS');
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });

  // The script must not contain a file deletion outside the collector.
  run('no sensitive file deletion bypasses the collector', () => {
    const text = readFileSync(scriptPath, 'utf8');
    const deletions = text
      .split('\n')
      .filter((line) => line.includes('Remove-Item') && line.includes('-LiteralPath'));
    for (const line of deletions) {
      expect(line, `silent deletion: ${line.trim()}`).not.toContain('SilentlyContinue');
    }
    // Every Remove-SensitivePath call is inside Clear-SensitivePaths.
    const calls = text.match(/Remove-SensitivePath -Path/g) ?? [];
    expect(calls.length).toBe(1);
  });

  // Every terminal exit must go through Set-ProbeFailure, except the dry-run
  // exit which happens before any sensitive file is created.
  run('every terminal exit applies the collected result', () => {
    const text = readFileSync(scriptPath, 'utf8');
    const lines = text.split('\n');

    // Find the Set-ProbeFailure body so its own exits are accounted for.
    const fnStart = lines.findIndex((line) => line.includes('function Set-ProbeFailure'));
    expect(fnStart).toBeGreaterThan(-1);
    // The closing brace of a top-level function is the first line whose trimmed
    // content is "}" and which is not indented; inner braces are indented.
    const fnEnd = lines.findIndex(
      (line, i) => i > fnStart && line.trim() === '}' && !/^\s/.test(line),
    );
    expect(fnEnd).toBeGreaterThan(fnStart);

    const exitLines = lines
      .map((line, i) => ({ line: line.trim(), i }))
      .filter(({ line }) => /^exit\s+\S+/.test(line));

    const outsideFunction = exitLines.filter(({ i }) => i < fnStart || i > fnEnd);
    // Two terminal exits are legitimate outside Set-ProbeFailure, and ONLY these
    // two, each for a reason that means it cannot skip a collected result:
    //
    //   1. `exit 0` for the dry run. It happens before any sensitive file or
    //      credential exists, so there is nothing to collect.
    //   2. `exit $script:CleanupOnlyExitCode` in the deferred $dockerFailure
    //      block. Docker raised before returning a status, so the exit has to
    //      happen AFTER the finally block that performs the certificate cleanup,
    //      and the status is the same 4 whether or not that cleanup also failed.
    //      Routing it through Set-ProbeFailure would exit identically; it would
    //      only change the wording that reports an additional cleanup failure.
    expect(
      outsideFunction.map(({ line }) => line),
      `unexpected terminal exits outside Set-ProbeFailure: ${JSON.stringify(outsideFunction)}`,
    ).toEqual(['exit 0', 'exit $script:CleanupOnlyExitCode']);

    // The dry-run exit must be guarded by the dry-run condition.
    const dryRunIndex = lines.findIndex((line) => line.includes('if (-not $Apply)'));
    expect(dryRunIndex).toBeGreaterThan(-1);
    expect(outsideFunction[0]!.i).toBeGreaterThan(dryRunIndex);

    // The deferred exit must sit inside the $dockerFailure guard and after the
    // certificate-cleanup finally block whose failure it exists to surface. That
    // block is identified structurally from the catch that records $dockerFailure
    // and from the cleanup call INSIDE it -- never from "the first } finally {",
    // which in this file closes the SHA-256 disposal and proves nothing about
    // certificate cleanup.
    const placement = analyseDeferredExit(text);
    expect(placement.violations, JSON.stringify(placement)).toEqual([]);
    expect(placement.dockerCatch).toBeGreaterThan(-1);
    expect(placement.certFinally).toBeGreaterThan(placement.dockerCatch);
    expect(placement.certCleanup).toBeGreaterThan(placement.certFinally);
    expect(placement.certCleanup).toBeLessThan(placement.certFinallyEnd);
    expect(placement.deferredGuard).toBeGreaterThan(placement.certFinallyEnd);
    expect(placement.deferredExit).toBeGreaterThan(placement.deferredGuard);
    expect(outsideFunction[1]!.i).toBe(placement.deferredExit);
    // Guard against regressing to the SHA-256 disposal block, which was the bug.
    const shaDisposal = lines.findIndex((line) => line.includes('ComputeHash'));
    expect(shaDisposal).toBeGreaterThan(-1);
    expect(placement.certFinally).toBeGreaterThan(shaDisposal);

    // Inside Set-ProbeFailure, the primary status is preserved for failures.
    const functionBody = lines.slice(fnStart, fnEnd + 1).join('\n');
    expect(functionBody).toContain('exit $PrimaryStatus');
    expect(functionBody).not.toContain('exit 4');
  });

  run('the deferred-exit placement check rejects an exit moved above certificate cleanup', () => {
    const text = readFileSync(scriptPath, 'utf8');
    const mutated = moveDeferredExitBeforeCertificateCleanup(text);
    // The mutation must change the text, or the check below proves nothing.
    expect(mutated).not.toBe(text);
    const placement = analyseDeferredExit(mutated);
    expect(placement.violations.join(' | '), JSON.stringify(placement)).toContain(
      'before the certificate-cleanup finally block completes',
    );
  });
});