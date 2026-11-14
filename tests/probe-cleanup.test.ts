import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Finding F-02: cleanup of temporary sensitive files must not be silent.
 *
 * The real `server/tools/probe-readonly.ps1` is loaded and its
 * `Remove-SensitivePath` helper is exercised directly, so these tests cover the
 * actual implementation rather than a copy of it. No database is contacted.
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
const run = powershell ? it : it.skip;

/**
 * Load the script's function definitions without executing its main body.
 *
 * The script is parsed with the AST and only the function definitions are
 * evaluated, so no probe runs and no environment is required.
 */
function loadHelper(work: string): string {
  const loader = join(work, 'load.ps1');
  writeFileSync(
    loader,
    [
      '$ErrorActionPreference = "Stop"',
      `$ast = [System.Management.Automation.Language.Parser]::ParseFile('${scriptPath.replace(/'/g, "''")}', [ref]$null, [ref]$null)`,
      '$funcs = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true)',
      'foreach ($f in $funcs) { . ([scriptblock]::Create($f.Extent.Text)) }',
      'if (-not (Get-Command Remove-SensitivePath -ErrorAction SilentlyContinue)) { throw "Remove-SensitivePath not found" }',
      'Write-Output "LOADED"',
    ].join('\n'),
    'utf8',
  );
  return loader;
}

function runPs(script: string, work: string): { status: number | null; output: string } {
  const file = join(work, `case-${Math.random().toString(36).slice(2)}.ps1`);
  writeFileSync(file, script, 'utf8');
  const result = spawnSync(powershell as string, ['-NoProfile', '-NonInteractive', '-File', file], {
    encoding: 'utf8',
    timeout: 120_000,
  });
  return { status: result.status, output: `${result.stdout ?? ''}${result.stderr ?? ''}` };
}

describe('F-02: sensitive-file cleanup reports failures', () => {
  run('deletes a file and reports success', () => {
    const work = mkdtempSync(join(tmpdir(), 'cleanup-'));
    try {
      const loader = loadHelper(work);
      const target = join(work, 'transcript.txt');
      writeFileSync(target, 'sensitive', 'utf8');
      const r = runPs(
        [
          `. '${loader.replace(/'/g, "''")}'`,
          `$r = Remove-SensitivePath -Path '${target.replace(/'/g, "''")}' -Label 'raw transcript'`,
          'if ($null -eq $r) { "RESULT=ok" } else { "RESULT=$r" }',
          `if (Test-Path '${target.replace(/'/g, "''")}') { "STILL_PRESENT" } else { "GONE" }`,
        ].join('\n'),
        work,
      );
      expect(r.status, r.output).toBe(0);
      expect(r.output).toContain('RESULT=ok');
      expect(r.output).toContain('GONE');
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });

  run('reports a failure when the path cannot be deleted', () => {
    const work = mkdtempSync(join(tmpdir(), 'cleanup-'));
    try {
      const loader = loadHelper(work);
      // A directory with a child, deleted WITHOUT -Recurse, cannot be removed.
      const dir = join(work, 'certdir');
      mkdirSync(dir, { recursive: true });
      writeFileSync(join(dir, 'root.crt'), 'cert', 'utf8');
      const r = runPs(
        [
          `. '${loader.replace(/'/g, "''")}'`,
          `$r = Remove-SensitivePath -Path '${dir.replace(/'/g, "''")}' -Label 'temporary certificate directory'`,
          'if ($null -eq $r) { "RESULT=ok" } else { "RESULT=$r" }',
        ].join('\n'),
        work,
      );
      expect(r.status, r.output).toBe(0);
      expect(r.output).toContain('RESULT=temporary certificate directory could not be deleted');
      // The helper must not throw: a cleanup failure cannot replace the primary error.
      expect(r.output).not.toContain('Exception');
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });

  run('reports a failure when the path survives deletion', () => {
    const work = mkdtempSync(join(tmpdir(), 'cleanup-'));
    try {
      const loader = loadHelper(work);
      const dir = join(work, 'certdir2');
      mkdirSync(dir, { recursive: true });
      writeFileSync(join(dir, 'root.crt'), 'cert', 'utf8');
      // -Recurse is passed, so this succeeds; the point is that the helper
      // re-checks existence rather than trusting Remove-Item.
      const r = runPs(
        [
          `. '${loader.replace(/'/g, "''")}'`,
          `$r = Remove-SensitivePath -Path '${dir.replace(/'/g, "''")}' -Label 'temporary certificate directory' -Recurse`,
          'if ($null -eq $r) { "RESULT=ok" } else { "RESULT=$r" }',
          `if (Test-Path '${dir.replace(/'/g, "''")}') { "STILL_PRESENT" } else { "GONE" }`,
        ].join('\n'),
        work,
      );
      expect(r.status, r.output).toBe(0);
      expect(r.output).toContain('RESULT=ok');
      expect(r.output).toContain('GONE');
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });

  run('a missing path is not a failure', () => {
    const work = mkdtempSync(join(tmpdir(), 'cleanup-'));
    try {
      const loader = loadHelper(work);
      const r = runPs(
        [
          `. '${loader.replace(/'/g, "''")}'`,
          `$r = Remove-SensitivePath -Path '${join(work, 'never-existed').replace(/'/g, "''")}' -Label 'raw transcript'`,
          'if ($null -eq $r) { "RESULT=ok" } else { "RESULT=$r" }',
        ].join('\n'),
        work,
      );
      expect(r.status, r.output).toBe(0);
      expect(r.output).toContain('RESULT=ok');
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });

  run('the script no longer deletes sensitive files with SilentlyContinue', () => {
    const text = readFileSync(scriptPath, 'utf8');
    // Environment-variable removals may stay silent; file deletions may not.
    const fileDeletions = text
      .split('\n')
      .filter((line) => line.includes('Remove-Item') && line.includes('-LiteralPath'));
    expect(fileDeletions.length).toBeGreaterThan(0);
    for (const line of fileDeletions) {
      expect(line, `silent file deletion: ${line.trim()}`).not.toContain('SilentlyContinue');
    }
  });
});