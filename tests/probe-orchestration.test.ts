import { spawnSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createHash, generateKeyPairSync } from 'node:crypto';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';

/**
 * Production-orchestration tests for F-02.
 *
 * These run the REAL server/tools/probe-readonly.ps1 with -Apply, so the actual
 * Docker call, try/catch/finally, evidence reduction, transcript cleanup,
 * recursive certificate-directory cleanup and final exit-status handling all
 * execute. A `docker` stub earlier on PATH produces synthetic psql output; no
 * container starts and no database is contacted.
 */

const repoRoot = resolve(__dirname, '..');
const scriptPath = join(repoRoot, 'server', 'tools', 'probe-readonly.ps1');

const STAGING_REF = 'stagingref0000000001';
const SENTINEL_PW = 'synthetic-probe-pw-not-a-credential';
const SENTINEL_ADMIN = 'synthetic-admin-pass-not-a-credential';

/** Lines accepted by the sanitizer's allowlist. */
const OK_TRANSCRIPT = [
  'project_identity=confirmed',
  'auth_schema_usage=true',
  'public_schema_usage=true',
  'auth_users_select=false',
  'auth_identities_select=false',
  'profiles_select=true',
  'customers_select=true',
  'sales_select=true',
  'payments_select=true',
  'migration_statements_select=true',
  'public_create=false',
  'elevated_attribute=false',
  'can_login=false',
  'membership_count=0',
  'table_grant_count=8',
  'NOTICE:  pos_probe_readonly preflight passed: 3 reviewed SECURITY DEFINER function(s) executable',
  'NOTICE:  pos_probe_readonly assertions passed',
];

/**
 * Absolute path to a resolved executable. A PATH override is part of the test,
 * so the host binary must not depend on PATH to be found.
 */
function resolveExecutable(name: string): string {
  const finder = process.platform === 'win32' ? 'where' : 'which';
  const found = spawnSync(finder, [name], { encoding: 'utf8' });
  const first = (found.stdout ?? '')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)[0];
  return first ?? name;
}

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

/** Resolved once: a test may replace PATH entirely. */
const powershellExe = resolveExecutable(powershell as string);

// The two "docker throws" scenarios need the stub to raise a PowerShell
// EXCEPTION inside probe-readonly.ps1's own try block. That is only possible
// when the stub runs in-process, which requires the .ps1 written by
// buildHarness on Windows. A POSIX launcher is always a child process, where
// `throw` degrades to a non-zero exit and can never propagate, so those two
// scenarios are SKIPPED there instead of being converted into hollow passes.
const runDockerThrow = process.platform === 'win32' ? it : it.skip;

/** A synthetic PEM whose body the script can hash. Not a trusted CA. */
function syntheticPem(): string {
  const { publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const der = publicKey.export({ type: 'spki', format: 'der' });
  const b64 = der.toString('base64').match(/.{1,64}/g)!.join('\n');
  return `-----BEGIN CERTIFICATE-----\n${b64}\n-----END CERTIFICATE-----\n`;
}

interface Harness {
  work: string;
  bin: string;
  certPath: string;
  transcriptPath: string;
}

const DOCKER_STUB = [
  // NO param() block. The production call is `& docker @dockerArgs` with
  // @("run","--rm","-i","postgres:17"). With a param() declaration PowerShell
  // binds "-i" as an (ambiguous) PARAMETER NAME and throws before the stub body
  // runs, so the stub never emits its transcript. $args captures the tokens
  // verbatim, dashes included, in order.
  '$Rest = $args',
  '$mode = if ($env:STUB_MODE) { $env:STUB_MODE } else { "ok" }',
  '$events = $env:STUB_EVENTS',
  'function Record([string] $what) { if ($events) { Add-Content -LiteralPath $events -Value $what } }',
  'Record "docker-invoked"',
  '',
  '# Record only fixed, non-sensitive FACTS about the arguments. A raw join of',
  '# $args would capture whatever a future refactor passes, including a',
  '# credential. Each recorded value is a boolean or a literal constant.',
  'Record ("has-i=" + ($args -contains "-i").ToString().ToLower())',
  'Record ("has-rm=" + ($args -contains "--rm").ToString().ToLower())',
  'Record ("first=" + $args[0])',
  '$joined = $args -join " "',
  'Record ("has-verify-full=" + ($joined.Contains("sslmode=verify-full")).ToString().ToLower())',
  'Record ("has-on-error-stop=" + ($joined.Contains("ON_ERROR_STOP=1")).ToString().ToLower())',
  'Record ("has-single-tx=" + ($joined.Contains("--single-transaction")).ToString().ToLower())',
  'Record ("has-pgpw-name=" + ($joined.Contains("-e PGPASSWORD")).ToString().ToLower())',
  'if ($env:PROBE_PW) { Record "LEAK_PROBE_PW" }',
  'if ($env:PROBE_ADMIN_PASS) { Record "LEAK_PROBE_ADMIN_PASS" }',
  'try { [void][Console]::OpenStandardInput().ReadByte() } catch { }',
  '# "throw" fails before returning a status. "throw-and-stick" additionally',
  '# plants a live handle on the certificate directory the script mounts, so the',
  '# the production cleanup cannot delete it and must surface that on exit.',
  'if ($mode -eq "throw" -or $mode -eq "throw-and-stick") {',
  '    if ($mode -eq "throw-and-stick") {',
  '        # Make the certificate directory undeletable by opening a live handle',
  '        # on a file inside it. The directory path is not predictable from the',
  '        # stub (the script generates a GUID), so it is located from the bind',
  '        # mount argument the script itself passes, which names that directory.',
  '        $mount = $args | Where-Object { $_ -like "*:/certs*" } | Select-Object -First 1',
  '        if ($mount) {',
  '            # Strip the container side only. ":.*$" would cut at the drive',
  '            # letter and leave "C", so the directory was never found and no',
  '            # lock was planted.',
  '            $dir = $mount -replace ":/certs.*$", ""',
  '            if (Test-Path -LiteralPath $dir) {',
  '                $target = Join-Path $dir "ca.pem"',
  '                if (-not (Test-Path -LiteralPath $target)) { Set-Content -LiteralPath $target -Value "x" }',
  '                $holder = Join-Path $env:TEMP ("hold-" + [guid]::NewGuid().ToString("N") + ".ps1")',
  '                $holderBody = "param([string]`$p,[int]`$s); `$f=[IO.File]::Open(`$p,\'Open\',\'Read\',\'None\'); Set-Content -LiteralPath `$env:STUB_LOCK_MARKER -Value (`$PID.ToString() + [char]124 + `$p + [char]124 + `$PSCommandPath) -Encoding ASCII; Start-Sleep -Seconds `$s; `$f.Dispose()"',
  '                Set-Content -LiteralPath $holder -Encoding ASCII -Value $holderBody',
  '                # The marker is written only AFTER the exclusive handle is',
  '                # open, so its presence proves the lock was actually held.',
  '                Start-Process powershell -ArgumentList @("-NoProfile","-ExecutionPolicy","Bypass","-File",$holder,"-p",$target,"-s","25") -WindowStyle Hidden -WorkingDirectory $env:TEMP | Out-Null',
  '                # Bounded polling instead of a fixed settle. The marker is',
  '                # written by the holder only AFTER its exclusive handle is',
  '                # open, so its presence IS the lock; a timeout is recorded so',
  '                # the scenario cannot pass without one.',
  '                $deadline = (Get-Date).AddSeconds(15)',
  '                while (-not (Test-Path -LiteralPath $env:STUB_LOCK_MARKER) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 50 }',
  '                if (-not (Test-Path -LiteralPath $env:STUB_LOCK_MARKER)) { Record "lock-marker-timeout" }',
  '                Record ("stuck-dir=" + $dir)',
  '        }',
  '    }',
  '            }',
  '    # A real throw, not `Write-Error` plus a non-zero exit: only a',
  '    # terminating error reaches probe-readonly.ps1 catch block. On Windows',
  '    # the stub runs in-process as docker.ps1, so this propagates there.',
  '    throw "stub: docker invocation failed"',
  '}',
  '$lines = Get-Content -LiteralPath $env:STUB_TRANSCRIPT -ErrorAction Stop',
  '$lines | ForEach-Object { Write-Output $_ }',
  'exit ([int]$env:STUB_EXIT)',
  '',
].join('\r\n');

/** The script writes evidence to server/build, regardless of cwd. */
function evidencePathForScript(): string {
  return join(repoRoot, 'server', 'build', 'probe-readonly-evidence.txt');
}

const evidencePath = evidencePathForScript();
const evidenceBeforeSuite = existsSync(evidencePath) ? readFileSync(evidencePath) : undefined;

afterAll(() => {
  // Harness runs write only the generated evidence path. Restore its exact
  // pre-suite bytes (or absence) so validation leaves no workspace residue.
  rmSync(evidencePath, { recursive: true, force: true });
  if (evidenceBeforeSuite !== undefined) {
    mkdirSync(dirname(evidencePath), { recursive: true });
    writeFileSync(evidencePath, evidenceBeforeSuite);
  }
});

function buildHarness(transcript: string[]): Harness {
  const work = mkdtempSync(join(tmpdir(), 'probe-orch-'));
  const bin = join(work, 'bin');
  mkdirSync(bin, { recursive: true });

  const certPath = join(work, 'root.crt');
  writeFileSync(certPath, syntheticPem(), 'utf8');

  const transcriptPath = join(work, 'stub-transcript.txt');
  writeFileSync(transcriptPath, `${transcript.join('\n')}\n`, 'utf8');

  // The stub is a plain .cmd on Windows: Powershell captures a native
  // process's stdout through `& cmd | Out-File`, but not the output produced by
  // a .ps1 invoked as a launcher. No docker.ps1 is placed on PATH, since
  // PowerShell resolves .ps1 before .cmd.
  const psBodyPath = join(bin, 'docker-stub-body.ps1');
  writeFileSync(psBodyPath, DOCKER_STUB, 'utf8');

  // Windows: expose the body itself as docker.ps1. PowerShell resolves an
  // ExternalScript (.ps1) ahead of docker.cmd/.bat and runs it IN-PROCESS, which
  // is the only way the stub's `throw` can reach probe-readonly.ps1's catch
  // block: a launcher that merely exits non-zero is a native failure, never a
  // PowerShell exception, so the deferred $dockerFailure path would be
  // unreachable and the "docker throws" scenarios could not test what they
  // assert. On POSIX no in-process form exists, so the sh launcher is used.
  if (process.platform === 'win32') {
    writeFileSync(join(bin, 'docker.ps1'), DOCKER_STUB, 'utf8');
  }

  writeFileSync(
    join(bin, 'docker.cmd'),
    `@echo off\r\npowershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0docker-stub-body.ps1" %*\r\nexit /b %ERRORLEVEL%\r\n`,
    'utf8',
  );
  // Also expose the same launcher as docker.bat: PowerShell's command
  // resolution differs between hosts and prefers .bat over .cmd in some.
  writeFileSync(
    join(bin, 'docker.bat'),
    `@echo off\r\npowershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0docker-stub-body.ps1" %*\r\nexit /b %ERRORLEVEL%\r\n`,
    'utf8',
  );
  writeFileSync(
    join(bin, 'docker'),
    `#!/bin/sh\nexec "${'$'}(command -v pwsh || command -v powershell)" -NoProfile -File "${'$'}(dirname "${'$'}0")/docker-stub-body.ps1" "${'$'}@"\n`,
    'utf8',
  );
  chmodSync(join(bin, 'docker'), 0o755);

  return { work, bin, certPath, transcriptPath };
}

/**
 * The fingerprint the script checks: SHA-256 over the base64-decoded PEM body.
 *
 * Mirrors lines 236-248 of probe-readonly.ps1 exactly -- split on CRLF or LF,
 * trim each line, drop the -----BEGIN/END----- lines, join, base64-decode. Any
 * extra whitespace changes the DER and the script refuses.
 */
function fingerprintOf(pemPath: string): string {
  const body = readFileSync(pemPath, 'utf8')
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('-----'))
    .join('');
  return createHash('sha256').update(Buffer.from(body, 'base64')).digest('hex');
}

function runScript(
  h: Harness,
  mode: string,
  stubExit: number,
  opts: { path?: string; env?: NodeJS.ProcessEnv; runnerPath?: string; apply?: boolean } = {},
) {
  const eventsPath = join(h.work, 'events.txt');
  writeFileSync(eventsPath, '', 'utf8');

  const env: NodeJS.ProcessEnv = {
    ...process.env,
    // A PATH override is how the "docker cannot be resolved" scenario makes
    // command resolution fail inside the production script itself.
    PATH: opts.path ?? `${h.bin}${process.platform === 'win32' ? ';' : ':'}${process.env.PATH}`,
    POS_STAGING_PROJECT_REF: STAGING_REF,
    POS_STAGING_POOLER_HOST: 'aws-0-eu-west-2.pooler.supabase.com',
    POS_STAGING_DB_ROOT_CERT: h.certPath,
    POS_STAGING_DB_ROOT_CERT_SHA256: fingerprintOf(h.certPath),
    PROBE_ADMIN_USER: `postgres.${STAGING_REF}`,
    PROBE_ADMIN_PASS: SENTINEL_ADMIN,
    PROBE_PW: SENTINEL_PW,
    POS_REVIEWED_SECURITY_DEFINER_FUNCTIONS: 'a,b,c',
    STUB_MODE: mode,
    STUB_EXIT: String(stubExit),
    STUB_TRANSCRIPT: h.transcriptPath,
    STUB_EVENTS: eventsPath,
    // Written by the planted holder itself, once its exclusive handle is open.
    STUB_LOCK_MARKER: join(h.work, 'lock-marker.txt'),
    ...opts.env,
  };

  const args = [
    '-NoProfile',
    '-NonInteractive',
    '-ExecutionPolicy',
    'Bypass',
    '-File',
    opts.runnerPath ?? scriptPath,
  ];
  if (opts.apply !== false) args.push('-Apply');

  const r = spawnSync(powershellExe, args, {
    cwd: h.work,
    encoding: 'utf8',
    env,
    timeout: 180_000,
  });

  const events = existsSync(eventsPath)
    ? readFileSync(eventsPath, 'utf8').split(/\r?\n/).filter(Boolean)
    : [];

  return { status: r.status, output: `${r.stdout ?? ''}${r.stderr ?? ''}`, events };
}

function samePath(a: string, b: string): boolean {
  const left = resolve(a);
  const right = resolve(b);
  return process.platform === 'win32' ? left.toLowerCase() === right.toLowerCase() : left === right;
}

/**
 * True only when the live process with this id is the holder this test planted.
 *
 * A pid read from a file is not an identity: operating systems recycle pids, so
 * terminating on the number alone could kill an unrelated process on a
 * developer machine.
 */
function isPlantedHolder(pid: number, holderPath: string): boolean {
  const probe =
    process.platform === 'win32'
      ? spawnSync(
          'powershell',
          [
            '-NoProfile',
            '-Command',
            `(Get-CimInstance Win32_Process -Filter "ProcessId=${pid}").CommandLine`,
          ],
          { encoding: 'utf8', timeout: 30_000 },
        )
      : spawnSync('ps', ['-p', String(pid), '-o', 'args='], { encoding: 'utf8', timeout: 30_000 });
  const commandLine = `${probe.stdout ?? ''}`.trim();
  return commandLine.length > 0 && commandLine.includes(holderPath);
}

/**
 * Undo the planted lock: terminate the holder, then remove the certificate
 * directory it kept undeletable and the holder script itself. The harness must
 * not leave either behind.
 */
function releasePlantedLock(h: Harness): void {
  const markerPath = join(h.work, 'lock-marker.txt');
  if (!existsSync(markerPath)) return;
  const [pidText, lockedPath, holderPath] = readFileSync(markerPath, 'utf8').trim().split('|');
  const pid = Number(pidText);
  if (Number.isInteger(pid) && pid > 0 && holderPath && isPlantedHolder(pid, holderPath)) {
    try {
      process.kill(pid);
    } catch {
      // Already gone; nothing to terminate.
    }
  }
  if (lockedPath) {
    // TerminateProcess releases the handle, but the filesystem may need a
    // moment before the directory can be removed.
    for (let attempt = 0; attempt < 20; attempt++) {
      try {
        rmSync(dirname(lockedPath), { recursive: true, force: true });
        break;
      } catch {
        Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 100);
      }
    }
  }
  // The marker is a file on disk, so its third field is checked before it is
  // used as a path: only the holder this harness wrote inside the OS temp dir
  // is ever deleted.
  if (holderPath && samePath(dirname(holderPath), tmpdir())) {
    rmSync(holderPath, { force: true });
  }
}

/** Certificate directories the production script creates in the OS temp dir. */
function tempCertDirs(): string[] {
  return readdirSync(tmpdir()).filter((name) => name.startsWith('pos-staging-ca-'));
}

/**
 * A PATH that cannot resolve docker, so `& docker` raises a
 * CommandNotFoundException -- a TERMINATING error -- inside probe-readonly.ps1
 * own try block. That is the same catch the Windows in-process throw exercises,
 * so the deferred exit can be covered on POSIX CI without pretending a launcher
 * exception is a PowerShell exception. On POSIX the script also resolves `chmod`
 * through PATH, so a no-op shim keeps the failure attributable to docker alone.
 */
function dockerlessPath(h: Harness): string {
  const dir = join(h.work, 'nodocker-bin');
  mkdirSync(dir, { recursive: true });
  if (process.platform !== 'win32') {
    const shim = join(dir, 'chmod');
    writeFileSync(shim, '#!/bin/sh\nexit 0\n', 'utf8');
    chmodSync(shim, 0o755);
  }
  return dir;
}

describe('production orchestration (real probe-readonly.ps1 -Apply)', () => {
  run('main succeeds and cleanup succeeds: exit 0', () => {
    const h = buildHarness(OK_TRANSCRIPT);
    try {
      const r = runScript(h, 'ok', 0);
      expect(r.events, r.output).toContain('docker-invoked');
      // The real production argument shape must reach the stub verbatim. A
      // param() declaration would bind "-i" as a parameter name and the stub
      // would never run -- this assertion is the regression guard for that.
      // The production argument SHAPE reaches the stub. Each fact is recorded
      // explicitly by the stub, so nothing sensitive is captured wholesale.
      expect(r.events, r.output).toContain('has-i=true');
      expect(r.events, r.output).toContain('has-rm=true');
      expect(r.events, r.output).toContain('first=run');
      expect(r.events, r.output).toContain('has-verify-full=true');
      expect(r.events, r.output).toContain('has-on-error-stop=true');
      expect(r.events, r.output).toContain('has-single-tx=true');
      expect(r.events, r.output).toContain('has-pgpw-name=true');
      // No sentinel may appear anywhere in what the stub recorded.
      expect(r.events.join('\n')).not.toContain(SENTINEL_PW);
      expect(r.events.join('\n')).not.toContain(SENTINEL_ADMIN);
      expect(r.status, r.output).toBe(0);
      expect(r.events).not.toContain('LEAK_PROBE_PW');
      expect(r.events).not.toContain('LEAK_PROBE_ADMIN_PASS');
      const evidence = readFileSync(evidencePathForScript(), 'utf8');
      expect(evidence).toContain('evidence_status=ok');
      expect(evidence).not.toContain(SENTINEL_PW);
    } finally {
      rmSync(h.work, { recursive: true, force: true });
    }
  });

  run('psql ON_ERROR_STOP 3 with clean cleanup keeps status 3', () => {
    const h = buildHarness(['ERROR:  pos_probe_readonly assertions failed: stale grant']);
    const githubOutput = join(h.work, 'github-output');
    writeFileSync(githubOutput, '', 'utf8');
    try {
      const r = runScript(h, 'ok', 3, { env: { GITHUB_OUTPUT: githubOutput } });
      // 3 is psql's script-error status and must not be reinterpreted.
      expect(r.status, r.output).toBe(3);
      // ...and it came from the captured $LASTEXITCODE of a real docker
      // invocation, not from a failure before the database step.
      expect(r.events, r.output).toContain('docker-invoked');
      const outputBytes = readFileSync(githubOutput);
      expect(outputBytes.subarray(0, 3)).not.toEqual(Buffer.from([0xef, 0xbb, 0xbf]));
      expect(outputBytes.toString('utf8')).toBe('evidence_ready=true\n');
      const evidence = readFileSync(evidencePathForScript(), 'utf8');
      expect(evidence).toContain('evidence_status=incomplete');
      expect(evidence).not.toContain('stale grant');
      expect(evidence).not.toContain(SENTINEL_PW);
      expect(evidence).not.toContain(SENTINEL_ADMIN);
    } finally {
      rmSync(h.work, { recursive: true, force: true });
    }
  });

  // REGRESSION (reviewer BLOCKER): the Docker catch block used to call `throw`,
  // which propagated past every Set-ProbeFailure call. The process then exited 1
  // regardless of whether cleanup succeeded, so a failed certificate-directory
  // deletion was reported as a warning and never reached the exit status.
  // Asserting "non-zero" hid this: 1 satisfied it. The exact status is required.
  runDockerThrow('docker throws AND certificate cleanup fails: exit 4 (cleanup surfaced)', () => {
    const h = buildHarness(OK_TRANSCRIPT);
    try {
      const r = runScript(h, 'throw-and-stick', 0);
      // The Docker failure is primary but was never assigned a psql status, so
      // the cleanup failure is the distinguishing signal: 4.
      expect(r.status, `${r.output}\nEVENTS: ${r.events.join(' | ')}`).toBe(4);
      // Exit 4 alone cannot tell this apart from a docker throw with CLEAN
      // cleanup, which also exits 4, and the label alone proves nothing: it is
      // also the CertDir label of a successful cleanup. Both facts are asserted
      // with their exact production wording.
      expect(r.output, r.output).toContain('the database step failed before returning an exit status');
      expect(r.output, r.output).toContain(
        'sensitive-file cleanup also failed: temporary certificate directory could not be deleted',
      );
      // That wording comes from Remove-SensitivePath's catch, i.e. Remove-Item
      // THREW. The other failure branch ("still exists after deletion") is a
      // residue check and would prove nothing about the lock.
      expect(r.output, r.output).not.toContain('still exists after deletion');
      // Bounded polling, not a fixed sleep: a timeout would mean the scenario
      // never planted a lock and must not be reported as a pass.
      expect(r.events).not.toContain('lock-marker-timeout');
      // Lock evidence, three independent facts: the stub located the mounted
      // certificate directory, the holder opened an EXCLUSIVE handle on the file
      // inside it (it writes the marker only after [IO.File]::Open returns), and
      // that holder was still alive once the run had finished.
      const stuck = r.events.find((event) => event.startsWith('stuck-dir='));
      expect(stuck, `EVENTS: ${r.events.join(' | ')}`).toBeDefined();
      const certDir = stuck!.slice('stuck-dir='.length);
      const markerPath = join(h.work, 'lock-marker.txt');
      expect(existsSync(markerPath), 'the holder never opened the target file').toBe(true);
      const [holderText, lockedPath] = readFileSync(markerPath, 'utf8').trim().split('|');
      expect(lockedPath, 'the handle is not on the mounted certificate file').toBe(join(certDir, 'ca.pem'));
      const holderPid = Number(holderText);
      expect(Number.isInteger(holderPid) && holderPid > 0, `holder pid: ${holderText}`).toBe(true);
      expect(() => process.kill(holderPid, 0), 'the holder released the lock before the run ended').not.toThrow();
      // The failure must be reported, and it names no credential.
      expect(r.output).not.toContain(SENTINEL_PW);
      expect(r.output).not.toContain(SENTINEL_ADMIN);
    } finally {
      releasePlantedLock(h);
      rmSync(h.work, { recursive: true, force: true });
    }
  });

  runDockerThrow('docker throws before returning a status: exit 4', () => {
    const h = buildHarness(OK_TRANSCRIPT);
    try {
      const r = runScript(h, 'throw', 0);
      // psql never ran, so there is no status to preserve; 4 is the dedicated
      // "probe did not complete" status, distinct from psql 1-3.
      expect(r.status, r.output).toBe(4);
      // The stub ran and THEN threw, so docker was genuinely invoked and 4 came
      // from the catch branch rather than from a psql status.
      expect(r.events, r.output).toContain('docker-invoked');
      expect(r.output, r.output).toContain('the database step failed before returning an exit status');
      expect(r.output, r.output).not.toContain('Completed. Sanitized evidence');
      // Unlike Scenario 3 the cleanup succeeds here, so the leak surface is the
      // failure path itself: no password, no admin credential, no connection
      // string, and nothing that would carry a password in argument form.
      expect(r.output).not.toContain(SENTINEL_PW);
      expect(r.output).not.toContain(SENTINEL_ADMIN);
      expect(r.output).not.toMatch(/postgres(ql)?:\/\//);
      expect(r.output).not.toMatch(/PGPASSWORD\s*=/);
      expect(r.events).not.toContain('LEAK_PROBE_PW');
      expect(r.events).not.toContain('LEAK_PROBE_ADMIN_PASS');
    } finally {
      rmSync(h.work, { recursive: true, force: true });
    }
  });

  for (const psqlStatus of [0, 1, 2, 3]) {
    run(`evidence reduction failure preserves psql status ${psqlStatus || 'as status 1 when psql succeeds'}`, () => {
      const h = buildHarness(['project_identity=confirmed', 'RAW TRANSCRIPT LINE NOT ALLOWLISTED']);
      try {
        const r = runScript(h, 'ok', psqlStatus);
        const expectedStatus = psqlStatus === 0 ? 1 : psqlStatus;
        expect(r.events, r.output).toContain('docker-invoked');
        expect(r.status, r.output).toBe(expectedStatus);
        expect(r.output).toContain('evidence reduction failed');
        expect(r.output).not.toContain('RAW TRANSCRIPT LINE NOT ALLOWLISTED');
        expect(r.output).not.toContain(SENTINEL_PW);
        expect(r.output).not.toContain(SENTINEL_ADMIN);

        const evidencePath = evidencePathForScript();
        expect(existsSync(evidencePath), `no evidence artifact at ${evidencePath}`).toBe(true);
        const evidence = readFileSync(evidencePath, 'utf8');
        expect(evidence).toContain('evidence_status=rejected');
        expect(evidence).toContain(`psql_exit=${psqlStatus}`);
        expect(evidence).not.toContain('RAW TRANSCRIPT LINE NOT ALLOWLISTED');
        expect(evidence).not.toContain(SENTINEL_PW);
        expect(evidence).not.toContain(SENTINEL_ADMIN);
      } finally {
        rmSync(h.work, { recursive: true, force: true });
      }
    });
  }

  run('a failed psql run never yields evidence_status=ok', () => {
    const h = buildHarness(['ERROR:  pos_probe_readonly preflight refused: unreviewed function']);
    try {
      const r = runScript(h, 'ok', 3);
      expect(r.status, r.output).toBe(3);
      const evidencePath = evidencePathForScript();
      // The write is unconditional on this path, so a missing artifact is a
      // failure rather than a reason to skip the assertion.
      expect(existsSync(evidencePath), `no evidence artifact at ${evidencePath}`).toBe(true);
      const evidence = readFileSync(evidencePath, 'utf8');
      expect(evidence).not.toContain('evidence_status=ok');
      expect(evidence).toContain('evidence_status=incomplete');
    } finally {
      rmSync(h.work, { recursive: true, force: true });
    }
  });

  run('credentials never appear in output or evidence', () => {
    const h = buildHarness(OK_TRANSCRIPT);
    try {
      const r = runScript(h, 'ok', 0);
      expect(r.output).not.toContain(SENTINEL_PW);
      expect(r.output).not.toContain(SENTINEL_ADMIN);
      const evidencePath = evidencePathForScript();
      const evidence = existsSync(evidencePathForScript()) ? readFileSync(evidencePathForScript(), 'utf8') : '';
      expect(evidence).not.toContain(SENTINEL_PW);
      expect(evidence).not.toContain(SENTINEL_ADMIN);
    } finally {
      rmSync(h.work, { recursive: true, force: true });
    }
  });

  run('the production certificate cleanup call passes Recurse', () => {
    const text = readFileSync(scriptPath, 'utf8');
    const certCalls = text.split('\n').filter((line) => line.includes('temporary certificate directory'));
    expect(certCalls.length).toBeGreaterThan(0);
    const labelled = certCalls.filter((line) => line.includes('Label'));
    expect(labelled.length).toBeGreaterThan(0);
    for (const line of labelled) {
      expect(line, `certificate cleanup without Recurse: ${line.trim()}`).toContain('Recurse');
    }
  });

  run('the cleanup-only exit code is 4, distinct from psql 0-3', () => {
    const text = readFileSync(scriptPath, 'utf8');
    expect(text).toContain('CleanupOnlyExitCode = 4');
    expect(text).not.toContain('exit 3');
  });

  // H3: the deferred failure branch must be reachable on POSIX CI too. With no
  // docker on PATH, command resolution fails inside the production try block and
  // the stub never runs, so nothing below can be satisfied by the stub.
  run('docker cannot be resolved: deferred exit 4 and certificate cleanup still runs', () => {
    const h = buildHarness(OK_TRANSCRIPT);
    const strayBefore = tempCertDirs();
    try {
      const r = runScript(h, 'ok', 0, { path: dockerlessPath(h) });
      expect(r.status, `${r.output}\nEVENTS: ${r.events.join(' | ')}`).toBe(4);
      expect(r.output, r.output).toContain(
        'the database step failed before returning an exit status',
      );
      // The stub never ran: 4 comes from the deferred branch, not a stub exit and
      // not a psql status.
      expect(r.events).not.toContain('docker-invoked');
      expect(r.output).not.toContain('Completed. Sanitized evidence');
      expect(r.output).not.toContain(SENTINEL_PW);
      expect(r.output).not.toContain(SENTINEL_ADMIN);
      expect(r.output).not.toMatch(/PGPASSWORD\s*=/);
      expect(r.output).not.toMatch(/postgres(ql)?:\/\//);
      // Cleanup is mandatory on this path: the directory the run created is gone.
      expect(tempCertDirs().filter((dir) => !strayBefore.includes(dir))).toEqual([]);
    } finally {
      rmSync(h.work, { recursive: true, force: true });
    }
  });

  // H2: a previous successful artifact must not survive a failing run.
  run('a failing probe replaces a previous evidence_status=ok', () => {
    const h = buildHarness(OK_TRANSCRIPT);
    const githubOutput = join(h.work, 'github-output');
    const evidencePath = evidencePathForScript();
    mkdirSync(dirname(evidencePath), { recursive: true });
    writeFileSync(githubOutput, '', 'utf8');
    writeFileSync(
      evidencePath,
      'evidence_schema=probe-role/v1\nevidence_status=ok\npsql_exit=0\n',
      'utf8',
    );
    try {
      const r = runScript(h, 'ok', 0, { path: dockerlessPath(h), env: { GITHUB_OUTPUT: githubOutput } });
      expect(r.status, r.output).toBe(4);
      const evidence = readFileSync(evidencePath, 'utf8');
      expect(evidence).not.toContain('evidence_status=ok');
      expect(evidence).toContain('evidence_status=not_run');
      expect(evidence).not.toContain(SENTINEL_PW);
      expect(evidence).not.toContain(SENTINEL_ADMIN);
      // Nothing from a transcript this run never produced may appear.
      expect(evidence).not.toContain('project_identity=confirmed');
      expect(readFileSync(githubOutput, 'utf8')).toBe('evidence_ready=true\n');
    } finally {
      rmSync(h.work, { recursive: true, force: true });
    }
  });

  run('readiness output follows stale deletion and successful not_run initialization', () => {
    const source = readFileSync(scriptPath, 'utf8');
    const staleRemoval = source.indexOf('Remove-Item -LiteralPath $EvidenceFile');
    const initialization = source.indexOf("'evidence_status=not_run'");
    const initializationFailure = source.indexOf('the sanitized evidence artifact could not be set to a not-run state');
    const readinessAppend = source.indexOf('[System.IO.File]::AppendAllText');
    expect(staleRemoval).toBeGreaterThan(-1);
    expect(initialization).toBeGreaterThan(staleRemoval);
    expect(initializationFailure).toBeGreaterThan(initialization);
    expect(readinessAppend).toBeGreaterThan(initializationFailure);
    expect(source).toContain('[System.Text.UTF8Encoding]::new($false)');
  });

  run('stale-evidence deletion failure emits no readiness output', () => {
    const h = buildHarness(OK_TRANSCRIPT);
    const outputPath = join(h.work, 'github-output');
    const evidencePath = evidencePathForScript();
    writeFileSync(outputPath, '', 'utf8');
    rmSync(evidencePath, { recursive: true, force: true });
    mkdirSync(evidencePath, { recursive: true });
    const staleMarker = join(evidencePath, 'old-success-marker.txt');
    writeFileSync(staleMarker, 'evidence_status=ok\n', 'utf8');
    try {
      const r = runScript(h, 'ok', 0, { env: { GITHUB_OUTPUT: outputPath } });
      expect(r.status, r.output).not.toBe(0);
      expect(readFileSync(outputPath, 'utf8')).toBe('');
      expect(existsSync(staleMarker)).toBe(true);
      expect(r.events).not.toContain('docker-invoked');
      expect(r.output).not.toContain(SENTINEL_PW);
      expect(r.output).not.toContain(SENTINEL_ADMIN);
    } finally {
      rmSync(evidencePath, { recursive: true, force: true });
      rmSync(h.work, { recursive: true, force: true });
    }
  });

  run('evidence initialization failure emits no readiness output', () => {
    const h = buildHarness(OK_TRANSCRIPT);
    const outputPath = join(h.work, 'github-output');
    const runnerPath = join(h.work, 'init-failure-runner.ps1');
    const evidencePath = evidencePathForScript();
    writeFileSync(outputPath, '', 'utf8');
    rmSync(evidencePath, { recursive: true, force: true });
    writeFileSync(
      runnerPath,
      [
        'function global:Set-Content { throw "synthetic evidence initialization failure" }',
        `try { & '${scriptPath.replace(/'/g, "''")}' -Apply; exit 0 } catch { exit 71 }`,
        '',
      ].join('\n'),
      'utf8',
    );
    try {
      const r = runScript(h, 'ok', 0, { runnerPath, env: { GITHUB_OUTPUT: outputPath } });
      expect(r.status, r.output).toBe(71);
      expect(readFileSync(outputPath, 'utf8')).toBe('');
      expect(existsSync(evidencePath)).toBe(false);
      expect(r.events).not.toContain('docker-invoked');
      expect(r.output).not.toContain('synthetic evidence initialization failure');
      expect(r.output).not.toContain(SENTINEL_PW);
      expect(r.output).not.toContain(SENTINEL_ADMIN);
    } finally {
      rmSync(evidencePath, { recursive: true, force: true });
      rmSync(h.work, { recursive: true, force: true });
    }
  });

  run('a present but unwritable GITHUB_OUTPUT fails closed without readiness', () => {
    const h = buildHarness(OK_TRANSCRIPT);
    const outputPath = join(h.work, 'github-output-directory');
    const evidencePath = evidencePathForScript();
    mkdirSync(outputPath);
    rmSync(evidencePath, { recursive: true, force: true });
    try {
      const r = runScript(h, 'ok', 0, { env: { GITHUB_OUTPUT: outputPath } });
      expect(r.status, r.output).not.toBe(0);
      expect(readdirSync(outputPath)).toEqual([]);
      expect(readFileSync(evidencePath, 'utf8')).toContain('evidence_status=not_run');
      expect(r.events).not.toContain('docker-invoked');
      expect(r.output).not.toContain(SENTINEL_PW);
      expect(r.output).not.toContain(SENTINEL_ADMIN);
    } finally {
      rmSync(evidencePath, { recursive: true, force: true });
      rmSync(h.work, { recursive: true, force: true });
    }
  });

  run('dry run emits no readiness output and preserves stale evidence', () => {
    const h = buildHarness(OK_TRANSCRIPT);
    const outputPath = join(h.work, 'github-output');
    const evidencePath = evidencePathForScript();
    const staleEvidence = 'evidence_schema=probe-role/v1\nevidence_status=ok\npsql_exit=0\n';
    writeFileSync(outputPath, '', 'utf8');
    mkdirSync(dirname(evidencePath), { recursive: true });
    writeFileSync(evidencePath, staleEvidence, 'utf8');
    try {
      const r = runScript(h, 'ok', 0, { apply: false, env: { GITHUB_OUTPUT: outputPath } });
      expect(r.status, r.output).toBe(0);
      expect(readFileSync(outputPath, 'utf8')).toBe('');
      expect(readFileSync(evidencePath, 'utf8')).toBe(staleEvidence);
      expect(r.events).not.toContain('docker-invoked');
    } finally {
      rmSync(h.work, { recursive: true, force: true });
    }
  });

  // H2: a refusal before the database step must also clear a previous success.
  run('a refused run cannot leave a previous evidence_status=ok', () => {
    const h = buildHarness(OK_TRANSCRIPT);
    const evidencePath = evidencePathForScript();
    mkdirSync(dirname(evidencePath), { recursive: true });
    writeFileSync(
      evidencePath,
      'evidence_schema=probe-role/v1\nevidence_status=ok\npsql_exit=0\n',
      'utf8',
    );
    try {
      const r = runScript(h, 'ok', 0, { env: { POS_STAGING_DB_ROOT_CERT_SHA256: 'a'.repeat(64) } });
      expect(r.status, r.output).not.toBe(0);
      expect(r.output, r.output).toContain('does not match POS_STAGING_DB_ROOT_CERT_SHA256');
      expect(r.events).not.toContain('docker-invoked');
      // A precondition refusal removes the previous artifact rather than
      // rewriting it: there is no psql result to report, and nothing at all is
      // uploaded for a run that never reached the database step.
      expect(existsSync(evidencePath), 'a stale artifact survived the refusal').toBe(false);
    } finally {
      rmSync(h.work, { recursive: true, force: true });
    }
  });
});
