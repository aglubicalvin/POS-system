import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  chmodSync,
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
import { afterAll, describe, expect, it } from 'vitest';
import { normalisePowerShellOutput } from './helpers/ps-output.ts';

/**
 * Behavioural tests for the PowerShell role-creation path.
 *
 * The real `server/tools/probe-readonly.ps1` is executed by the real PowerShell
 * host with synthetic material. Nothing connects to a database: the dry-run path
 * stops before Docker, and the apply path runs against a stub `docker` that only
 * records its arguments, so the CA mount and the libpq connection string can be
 * asserted without starting a container.
 */
const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..');
const scriptPath = resolve(repoRoot, 'server/tools/probe-readonly.ps1');
const vectorFile = resolve(repoRoot, 'server/tools/staging-pooler-host-vectors.txt');

const STAGING_KEYS = [
  'POS_STAGING_PROJECT_REF',
  'POS_STAGING_POOLER_HOST',
  'POS_STAGING_DB_ROOT_CERT',
  'POS_STAGING_DB_ROOT_CERT_SHA256',
  'POS_NON_STAGING_REFS',
  'PROBE_ADMIN_USER',
  'PROBE_ADMIN_PASS',
  'PROBE_PW',
];

const STAGING_REF = 'stagingref0000000001';
const POOLER_HOST = 'aws-0-eu-west-2.pooler.supabase.com';
const EVIDENCE_FILE = resolve(repoRoot, 'server/build/probe-readonly-evidence.txt');
const BUILD_DIR = resolve(repoRoot, 'server/build');

/**
 * The labelled lines `psql -q -A -t` prints for a clean, asserting run: the
 * project-identity check, the privilege evidence, the two \password prompts and
 * the notices. No headers, no table names, no function inventory.
 */
const CLEAN_TRANSCRIPT = `${[
  'project_identity=confirmed',
  'auth_schema_usage=false',
  'public_schema_usage=true',
  'auth_users_select=false',
  'auth_identities_select=false',
  'profiles_select=false',
  'customers_select=false',
  'sales_select=false',
  'payments_select=false',
  'migration_statements_select=false',
  'public_create=false',
  'elevated_attribute=false',
  'can_login=true',
  'membership_count=0',
  'table_grant_count=0',
  'NOTICE:  pos_probe_readonly preflight passed: 0 reviewed SECURITY DEFINER function(s) executable',
  'Enter new password for user "pos_probe_readonly": ',
  'Enter it again: ',
  'NOTICE:  SECURITY DEFINER functions reviewed for pos_probe_readonly: 0',
  'NOTICE:  pos_probe_readonly assertions passed',
].join('\n')}\n`;

/** Parse the sanitized evidence file into key/value pairs. */
function readEvidence(): Record<string, string> {
  const evidence: Record<string, string> = {};
  for (const line of readFileSync(EVIDENCE_FILE, 'utf8').split(/\r?\n/)) {
    const index = line.indexOf('=');
    if (index > 0) evidence[line.slice(0, index)] = line.slice(index + 1);
  }
  return evidence;
}

/**
 * Install a stub `docker` that records its argv and its stdin separately,
 * optionally prints a transcript, and exits with `exitCode`. No container, no
 * database, no network.
 *
 * stdin is captured to `docker-stdin.txt` verbatim (bytes, not lines) so the
 * real wrapper's password delivery can be asserted rather than assumed. On
 * Windows a .cmd shim cannot read stdin without mangling it (`more` paginates),
 * so the shim delegates to a tiny Node stub that drains the inherited pipe.
 */
function installStubDocker(
  dir: string,
  argsFile: string,
  transcriptFile?: string,
  exitCode = 0,
): string {
  const bin = join(dir, 'bin');
  mkdirSync(bin, { recursive: true });
  const stdinFile = join(dir, 'docker-stdin.txt');
  if (process.platform === 'win32') {
    const stub = join(bin, 'docker-stub.mjs');
    writeFileSync(
      stub,
      [
        "import { readFileSync, writeFileSync } from 'node:fs';",
        'const [stdinFile, argsFile, transcriptFile, code, ...dockerArgs] = process.argv.slice(2);',
        "let input = '';",
        'try { input = readFileSync(0, "utf8"); } catch {}',
        'writeFileSync(stdinFile, input, "utf8");',
        'writeFileSync(argsFile, dockerArgs.join(" ") + "\\n", "utf8");',
        'if (transcriptFile) { try { process.stdout.write(readFileSync(transcriptFile, "utf8")); } catch {} }',
        'process.exit(Number(code));',
        '',
      ].join('\n'),
      'utf8',
    );
    writeFileSync(
      join(bin, 'docker.cmd'),
      `@echo off\r\nnode "%~dp0docker-stub.mjs" "${stdinFile}" "${argsFile}" "${transcriptFile ?? ''}" ${exitCode} %*\r\n`,
      'utf8',
    );
  } else {
    const shim = join(bin, 'docker');
    writeFileSync(
      shim,
      `#!/bin/sh\ncat > "${stdinFile}"\nprintf '%s\\n' "$*" > "${argsFile}"\n` +
        (transcriptFile ? `cat "${transcriptFile}"\n` : '') +
        `exit ${exitCode}\n`,
      'utf8',
    );
    chmodSync(shim, 0o755);
  }
  return bin;
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

interface ScriptRun {
  status: number | null;
  output: string;
}

/**
 * PowerShell renders a thrown exception with ANSI colour and wraps long messages
 * at the host's width, so the same refusal is one line locally and several on a
 * CI runner. See ./helpers/ps-output.ts for the normalisation and its regression
 * test below.
 */
const normaliseOutput = normalisePowerShellOutput;

/** Run the script with an environment that contains no inherited staging config. */
function runScript(env: Record<string, string>, args: string[] = []): ScriptRun {
  const base: Record<string, string> = {};
  for (const [key, value] of Object.entries(process.env)) {
    if (value !== undefined) base[key] = value;
  }
  for (const key of STAGING_KEYS) delete base[key];
  const result = spawnSync(
    powershell as string,
    ['-NoProfile', '-NonInteractive', '-File', scriptPath, ...args],
    { env: { ...base, ...env }, encoding: 'utf8', timeout: 120_000 },
  );
  return {
    status: result.status,
    output: normaliseOutput(`${result.stdout ?? ''}${result.stderr ?? ''}`),
  };
}

/** A synthetic PEM certificate plus the SHA-256 of its DER encoding. */
function certificate(dir: string, seed = 1) {
  const der = Buffer.alloc(48);
  for (let i = 0; i < der.length; i++) der[i] = (i + seed) & 0xff;
  const pem = `-----BEGIN CERTIFICATE-----\n${der.toString('base64')}\n-----END CERTIFICATE-----\n`;
  const path = join(dir, 'ca.pem');
  writeFileSync(path, pem, 'utf8');
  return { path, pem, fingerprint: createHash('sha256').update(der).digest('hex') };
}

interface HostVector {
  accept: boolean;
  value: string;
  line: number;
}

function loadHostVectors(file = vectorFile): HostVector[] {
  return readFileSync(file, 'utf8')
    .split(/\r?\n/)
    .map((text, index) => ({ text, line: index + 1 }))
    .filter(({ text }) => text.trim() !== '' && !text.startsWith('#'))
    .map(({ text, line }) => {
      const separator = text.indexOf('\t');
      const decision = (separator < 0 ? text : text.slice(0, separator)).trim();
      const value = separator < 0 ? '' : text.slice(separator + 1);
      return { accept: decision === 'accept', value, line };
    });
}

const dirs: string[] = [];
function scratchDir(prefix: string): string {
  const dir = mkdtempSync(join(tmpdir(), prefix));
  dirs.push(dir);
  return dir;
}

afterAll(() => {
  for (const dir of dirs) rmSync(dir, { recursive: true, force: true });
});

const itPS = powershell ? it : it.skip;

describe('PowerShell output normalisation', () => {
  it('rejoins a refusal wrapped by the pwsh host, so Linux and Windows agree', () => {
    // Captured verbatim from a failing CI run: SGR sequences, pwsh's ` | `
    // continuation marker, and a message split across two host lines.
    const captured =
      "\u001b[31;1mException: \u001b[0m/home/runner/work/POS-system/POS-system/server/tools/probe-readonly.ps1:104\n" +
      "\u001b[36;1mLine |\u001b[0m\n" +
      "\u001b[36;1m\u001b[36;1m 104 | \u001b[0m     \u001b[36;1mthrow 'BLOCKED: PROBE_ADMIN_USER does not belong to the designate\u001b[0m\n" +
      "\u001b[36;1m     | \u001b[0m     \u001b[31;1mBLOCKED: PROBE_ADMIN_USER \u001b[27mdoes not belong to the designated staging\u001b[7m | \u001b[27m project. Expected postgres.<POS_STAGING_PROJECT_REF>.\u001b[0m";

    const normalised = normalisePowerShellOutput(captured);
    expect(normalised).toContain('does not belong to the designated staging project');
    expect(normalised).not.toContain('\u001b');
  });
});

describe('probe-readonly.ps1 hostname validation', () => {
  itPS('matches every shared hostname vector', () => {
    const dir = scratchDir('ps-vectors-');
    const cert = certificate(dir);
    const vectors = loadHostVectors();

    for (const vector of vectors) {
      const result = runScript({
        POS_STAGING_PROJECT_REF: STAGING_REF,
        POS_STAGING_POOLER_HOST: vector.value,
        POS_STAGING_DB_ROOT_CERT: cert.path,
        POS_STAGING_DB_ROOT_CERT_SHA256: cert.fingerprint,
        PROBE_ADMIN_USER: `postgres.${STAGING_REF}`,
        PROBE_ADMIN_PASS: 'admin-pass',
        PROBE_PW: 'probe-pass',
      });

      if (vector.accept) {
        expect(result.status, `line ${vector.line} <<${vector.value}>>: ${result.output}`).toBe(0);
        // An accepted value reaches the dry-run gate and stops there.
        expect(result.output).toContain('DRY RUN');
      } else {
        expect(result.status, `line ${vector.line} <<${vector.value}>> must be refused`).not.toBe(0);
        expect(result.output).toContain('BLOCKED');
        // No connection string is ever built for a refused host.
        expect(result.output).not.toContain('sslmode=');
      }
    }
    // One PowerShell process per vector: startup alone is ~1.5s each.
  }, 300_000);

  itPS('refuses a missing project reference before looking at anything else', () => {
    const result = runScript({ POS_STAGING_POOLER_HOST: POOLER_HOST });
    expect(result.status).not.toBe(0);
    expect(result.output).toContain('POS_STAGING_PROJECT_REF');
  });

  itPS('refuses a known non-staging project reference', () => {
    const result = runScript({
      POS_STAGING_PROJECT_REF: 'uemdrtrzlvhdtonccczt',
      POS_STAGING_POOLER_HOST: POOLER_HOST,
      PROBE_ADMIN_USER: 'postgres.uemdrtrzlvhdtonccczt',
    });
    expect(result.status).not.toBe(0);
    expect(result.output).toContain('known non-staging project');
  });

  itPS('refuses an administrator login that belongs to another project', () => {
    const dir = scratchDir('ps-mismatch-');
    const cert = certificate(dir);
    const result = runScript({
      POS_STAGING_PROJECT_REF: STAGING_REF,
      POS_STAGING_POOLER_HOST: POOLER_HOST,
      POS_STAGING_DB_ROOT_CERT: cert.path,
      POS_STAGING_DB_ROOT_CERT_SHA256: cert.fingerprint,
      PROBE_ADMIN_USER: 'postgres.otherref00000000000z',
      PROBE_ADMIN_PASS: 'admin-pass',
      PROBE_PW: 'probe-pass',
    });
    expect(result.status).not.toBe(0);
    expect(result.output).toContain('does not belong to the designated staging project');
  });
});

describe('probe-readonly.ps1 deny-list is additive', () => {
  function runDeny(ref: string, custom: string): ScriptRun {
    const dir = scratchDir('ps-deny-');
    const cert = certificate(dir, 9);
    return runScript({
      POS_STAGING_PROJECT_REF: ref,
      POS_STAGING_POOLER_HOST: POOLER_HOST,
      POS_STAGING_DB_ROOT_CERT: cert.path,
      POS_STAGING_DB_ROOT_CERT_SHA256: cert.fingerprint,
      POS_NON_STAGING_REFS: custom,
      PROBE_ADMIN_USER: `postgres.${ref}`,
      PROBE_ADMIN_PASS: 'admin-pass',
      PROBE_PW: 'probe-pass',
    });
  }

  itPS('keeps the built-in development reference with a blank custom list', () => {
    const result = runDeny('uemdrtrzlvhdtonccczt', ' , ');
    expect(result.status).not.toBe(0);
    expect(result.output).toContain('known non-staging project');
  });

  itPS('keeps the built-in verification reference with an empty custom list', () => {
    const result = runDeny('numwzjogaanfhlrfgrqk', '');
    expect(result.status).not.toBe(0);
    expect(result.output).toContain('known non-staging project');
  });

  itPS('refuses a custom reference after trimming, lower-casing and de-duplication', () => {
    const ref = 'customref00000000001';
    const result = runDeny(ref, `  ${ref.toUpperCase()} , ${ref} ,, `);
    expect(result.status).not.toBe(0);
    expect(result.output).toContain('known non-staging project');
  });

  itPS('refuses a malformed custom entry instead of ignoring it', () => {
    const result = runDeny(STAGING_REF, 'not-a-ref');
    expect(result.status).not.toBe(0);
    expect(result.output).toContain('POS_NON_STAGING_REFS');
    expect(result.output).toContain('not a Supabase project reference');
  });
});

describe('probe-readonly.ps1 certificate contract', () => {
  const baseEnv = (certPath: string, fingerprint: string) => ({
    POS_STAGING_PROJECT_REF: STAGING_REF,
    POS_STAGING_POOLER_HOST: POOLER_HOST,
    POS_STAGING_DB_ROOT_CERT: certPath,
    POS_STAGING_DB_ROOT_CERT_SHA256: fingerprint,
    PROBE_ADMIN_USER: `postgres.${STAGING_REF}`,
    PROBE_ADMIN_PASS: 'admin-pass',
    PROBE_PW: 'probe-pass',
  });

  itPS('refuses a missing certificate file', () => {
    const dir = scratchDir('ps-nocert-');
    const result = runScript(baseEnv(join(dir, 'absent.pem'), 'a'.repeat(64)));
    expect(result.status).not.toBe(0);
    expect(result.output).toContain('BLOCKED');
  });

  itPS('refuses a fingerprint that does not match the certificate', () => {
    const dir = scratchDir('ps-badfp-');
    const cert = certificate(dir);
    const result = runScript(baseEnv(cert.path, 'b'.repeat(64)));
    expect(result.status).not.toBe(0);
    expect(result.output).toContain('does not match');
    // The refusal must not leak the certificate or the supplied fingerprint.
    expect(result.output).not.toContain('BEGIN CERTIFICATE');
    expect(result.output).not.toContain('b'.repeat(64));
  });

  itPS('refuses a malformed fingerprint without touching the certificate', () => {
    const dir = scratchDir('ps-fpshape-');
    const cert = certificate(dir);
    const result = runScript(baseEnv(cert.path, 'not-a-fingerprint'));
    expect(result.status).not.toBe(0);
    expect(result.output).toContain('64 hex characters');
  });

  itPS('refuses a file that is not a PEM certificate', () => {
    const dir = scratchDir('ps-badpem-');
    const path = join(dir, 'not-a-cert.pem');
    writeFileSync(path, 'this is not a certificate\n', 'utf8');
    const result = runScript(baseEnv(path, 'c'.repeat(64)));
    expect(result.status).not.toBe(0);
    expect(result.output).toContain('PEM');
  });
});

describe('probe-readonly.ps1 docker configuration', () => {
  /**
   * Install a stub `docker` that records its arguments. The real script then runs
   * end to end without starting a container or opening a connection.
   */
  function installDockerShim(dir: string, argsFile: string): string {
    // A clean transcript: the sanitized-evidence contract requires the run to
    // prove its assertions, not merely exit 0.
    const transcript = join(dir, 'transcript.txt');
    writeFileSync(transcript, CLEAN_TRANSCRIPT, 'utf8');
    return installStubDocker(dir, argsFile, transcript, 0);
  }

  itPS('mounts the staged CA read-only at a fixed container path', () => {
    const dir = scratchDir('ps-docker-');
    const cert = certificate(dir, 3);
    const argsFile = join(dir, 'docker-args.txt');
    const bin = installDockerShim(dir, argsFile);

    const env: Record<string, string> = {
      POS_STAGING_PROJECT_REF: STAGING_REF,
      POS_STAGING_POOLER_HOST: POOLER_HOST,
      POS_STAGING_DB_ROOT_CERT: cert.path,
      POS_STAGING_DB_ROOT_CERT_SHA256: cert.fingerprint,
      PROBE_ADMIN_USER: `postgres.${STAGING_REF}`,
      PROBE_ADMIN_PASS: 'admin-pass',
      PROBE_PW: 'probe-pass',
      PATH: `${bin}${delimiter}${process.env.PATH ?? ''}`,
    };

    const result = runScript(env, ['-Apply']);
    expect(result.status, result.output).toBe(0);
    expect(result.output).not.toContain(cert.fingerprint);

    expect(existsSync(argsFile), `docker was not invoked:\n${result.output}`).toBe(true);
    const args = readFileSync(argsFile, 'utf8');

    // libpq trusts the staged CA at a fixed path inside the container, fed by a
    // read-only mount of a private copy -- never a host path.
    expect(args).toContain('sslmode=verify-full');
    expect(args).toContain('sslrootcert=/certs/ca.pem');
    expect(args).toMatch(/-v\s+\S+:\/certs:ro/);
    expect(args).not.toMatch(/sslrootcert=[a-zA-Z]:\\/);
    expect(args).not.toMatch(/sslrootcert=\/[^c]/);

    // The mount source is a temp directory created for this run only, and it is
    // removed afterwards so the CA never outlives the invocation.
    const mounted = /-v\s+(\S+):\/certs:ro/.exec(args)?.[1];
    expect(mounted).toBeTruthy();
    expect(mounted).toContain('pos-staging-ca-');
    expect(existsSync(mounted as string)).toBe(false);

    // The connection is built from the validated host and the designated login.
    expect(args).toContain(`host=${POOLER_HOST}`);
    expect(args).toContain(`user=postgres.${STAGING_REF}`);
    expect(args).toContain('dbname=postgres');
  });

  itPS('never applies without the explicit -Apply gate', () => {
    const dir = scratchDir('ps-noapply-');
    const cert = certificate(dir, 5);
    const argsFile = join(dir, 'docker-args.txt');
    const bin = installDockerShim(dir, argsFile);

    const result = runScript(
      {
        POS_STAGING_PROJECT_REF: STAGING_REF,
        POS_STAGING_POOLER_HOST: POOLER_HOST,
        POS_STAGING_DB_ROOT_CERT: cert.path,
        POS_STAGING_DB_ROOT_CERT_SHA256: cert.fingerprint,
        PROBE_ADMIN_USER: `postgres.${STAGING_REF}`,
        PROBE_ADMIN_PASS: 'admin-pass',
        PROBE_PW: 'probe-pass',
        PATH: `${bin}${delimiter}${process.env.PATH ?? ''}`,
      },
      [],
    );

    expect(result.status).toBe(0);
    expect(result.output).toContain('DRY RUN');
    expect(existsSync(argsFile), 'docker must not run without -Apply').toBe(false);
  });
});

/**
 * Finding 1 (independent review): the administrator identity must be compared
 * exactly. PowerShell's `-ne` is case-insensitive, so `POSTGRES.<ref>` and an
 * upper-case reference suffix were accepted here while ProbeState.java and the
 * SQL identity check compare exactly. The check runs before any Docker or psql
 * invocation, so a refused login cannot open a connection.
 */
describe('probe-readonly.ps1 administrator identity is exact', () => {
  function envWith(user: string | null): Record<string, string> {
    const dir = scratchDir('ps-identity-');
    const cert = certificate(dir, 11);
    const env: Record<string, string> = {
      POS_STAGING_PROJECT_REF: STAGING_REF,
      POS_STAGING_POOLER_HOST: POOLER_HOST,
      POS_STAGING_DB_ROOT_CERT: cert.path,
      POS_STAGING_DB_ROOT_CERT_SHA256: cert.fingerprint,
      PROBE_ADMIN_PASS: 'admin-pass',
      PROBE_PW: 'probe-pass',
    };
    if (user !== null) env.PROBE_ADMIN_USER = user;
    return env;
  }

  itPS('accepts the exact postgres.<reference> login', () => {
    const result = runScript(envWith(`postgres.${STAGING_REF}`));
    expect(result.status, result.output).toBe(0);
    expect(result.output).toContain('DRY RUN');
  });

  const rejected: Array<[string, string | null]> = [
    ['an upper-case postgres prefix', `POSTGRES.${STAGING_REF}`],
    ['an upper-case reference suffix', `postgres.${STAGING_REF.toUpperCase()}`],
    ['a different project reference', 'postgres.otherref00000000000z'],
    ['a login without a project reference', 'postgres'],
    ['a missing login', null],
  ];

  for (const [name, user] of rejected) {
    itPS(`refuses ${name}`, () => {
      const result = runScript(envWith(user));
      expect(result.status, result.output).not.toBe(0);
      expect(result.output).toContain('does not belong to the designated staging project');
      // Nothing is built, so no connection string can exist for a refused login.
      expect(result.output).not.toContain('sslmode=');
    });
  }

  itPS('refuses the login before any connection attempt', () => {
    rmSync(EVIDENCE_FILE, { force: true });
    const dir = scratchDir('ps-identity-noconnect-');
    const cert = certificate(dir, 13);
    const argsFile = join(dir, 'docker-args.txt');
    const bin = installStubDocker(dir, argsFile);
    const result = runScript(
      {
        ...envWith(`POSTGRES.${STAGING_REF}`),
        POS_STAGING_DB_ROOT_CERT: cert.path,
        POS_STAGING_DB_ROOT_CERT_SHA256: cert.fingerprint,
        PATH: `${bin}${delimiter}${process.env.PATH ?? ''}`,
      },
      ['-Apply'],
    );
    expect(result.status).not.toBe(0);
    expect(result.output).toContain('does not belong to the designated staging project');
    expect(existsSync(argsFile), 'docker must not run before the identity check').toBe(false);
    expect(existsSync(EVIDENCE_FILE)).toBe(false);
  });
});

/**
 * Finding 3 (independent review): the role-creation path must not upload the
 * raw psql transcript. The wrapper captures it privately, reduces it to
 * allowlisted fields, deletes it on success and failure, and crashes the step
 * when anything unexpected, secret or metadata-like appears.
 */
describe('probe-readonly.ps1 sanitized evidence', () => {
  function runApply(
    lines: string[],
    exitCode: number,
    extraEnv: Record<string, string> = {},
  ) {
    const dir = scratchDir('ps-evidence-');
    const cert = certificate(dir, 17);
    rmSync(EVIDENCE_FILE, { force: true });
    const transcript = join(dir, 'transcript.txt');
    writeFileSync(transcript, `${lines.join('\n')}\n`, 'utf8');
    const argsFile = join(dir, 'docker-args.txt');
    const bin = installStubDocker(dir, argsFile, transcript, exitCode);
    const result = runScript(
      {
        POS_STAGING_PROJECT_REF: STAGING_REF,
        POS_STAGING_POOLER_HOST: POOLER_HOST,
        POS_STAGING_DB_ROOT_CERT: cert.path,
        POS_STAGING_DB_ROOT_CERT_SHA256: cert.fingerprint,
        PROBE_ADMIN_USER: `postgres.${STAGING_REF}`,
        PROBE_ADMIN_PASS: 'admin-pass',
        PROBE_PW: 'probe-pass',
        PATH: `${bin}${delimiter}${process.env.PATH ?? ''}`,
        ...extraEnv,
      },
      ['-Apply'],
    );
    return {
      result,
      argsFile,
      dir,
      // Exactly the bytes the wrapper sent down Docker's inherited stdin pipe.
      stdin: existsSync(join(dir, 'docker-stdin.txt'))
        ? readFileSync(join(dir, 'docker-stdin.txt'), 'utf8')
        : null,
      evidence: existsSync(EVIDENCE_FILE) ? readEvidence() : null,
    };
  }

  const cleanLines = CLEAN_TRANSCRIPT.trimEnd().split('\n');
  const rawLogs = () =>
    existsSync(BUILD_DIR)
      ? readdirSync(BUILD_DIR).filter(
          (name) => name.startsWith('probe-readonly-') && name.endsWith('.log'),
        )
      : [];

  itPS('uploads only allowlisted fields and deletes the raw transcript', () => {
    const { result, evidence } = runApply(cleanLines, 0);
    expect(result.status, result.output).toBe(0);
    expect(evidence).not.toBeNull();
    expect(evidence!).toMatchObject({
      evidence_schema: 'probe-role/v1',
      psql_exit: '0',
      project_identity: 'confirmed',
      public_schema_usage: 'true',
      elevated_attribute: 'false',
      can_login: 'true',
      membership_count: '0',
      assertions_passed: 'true',
      evidence_status: 'ok',
    });
    // No raw .log survives in the uploaded directory.
    expect(rawLogs()).toEqual([]);
    // The transcript is never echoed to the job log.
    expect(result.output).not.toContain('public_schema_usage');
    expect(result.output).not.toContain('NOTICE');
  });

  itPS('rejects unexpected transcript output instead of forwarding it', () => {
    const { result, evidence } = runApply(
      [...cleanLines, 'public | profiles | SELECT'],
      0,
    );
    expect(result.status, result.output).not.toBe(0);
    expect(evidence?.evidence_status).toBe('rejected');
    expect(result.output).not.toContain('profiles');
    expect(rawLogs()).toEqual([]);
  });

  itPS('never emits password-like or certificate material', () => {
    const { result, evidence } = runApply(
      ['project_identity=confirmed', 'password=sup3rs3cret', '-----BEGIN CERTIFICATE-----'],
      0,
    );
    expect(result.status).not.toBe(0);
    expect(evidence?.evidence_status).toBe('rejected');
    expect(result.output).not.toContain('sup3rs3cret');
    expect(result.output).not.toContain('BEGIN CERTIFICATE');
  });

  itPS('preserves a non-zero psql exit and still writes evidence', () => {
    const { result, evidence } = runApply(
      [
        'psql: error: connection to server at "db.internal:5432" failed: password authentication failed for user "postgres"',
      ],
      3,
    );
    expect(result.status, result.output).toBe(3);
    expect(evidence?.psql_exit).toBe('3');
    expect(evidence?.evidence_status).toBe('incomplete');
    expect(evidence?.assertions_passed).toBe('false');
    expect(result.output).not.toContain('db.internal');
    expect(result.output).not.toContain('password authentication failed');
    expect(rawLogs()).toEqual([]);
  });

  itPS('fails a zero psql exit whose transcript never asserted', () => {
    const withoutAssert = cleanLines.filter((line) => !line.includes('assertions passed'));
    const { result, evidence } = runApply(withoutAssert, 0);
    expect(result.status).toBe(1);
    expect(evidence?.evidence_status).toBe('incomplete');
    expect(result.output).toContain('::error::');
  });

  itPS('runs the preflight first and passes the reviewed allowlist', () => {
    const { result, argsFile } = runApply(cleanLines, 0, {
      POS_REVIEWED_SECURITY_DEFINER_FUNCTIONS: 'public.reader(integer)',
    });
    expect(result.status, result.output).toBe(0);
    const args = readFileSync(argsFile, 'utf8');
    expect(args).toContain('-v reviewed_security_definer=public.reader(integer)');
    const preflightIdx = args.indexOf('/work/pos_probe_readonly_preflight.sql');
    const roleIdx = args.indexOf('/work/pos_probe_readonly.sql');
    const assertIdx = args.indexOf('/work/pos_probe_readonly_assert.sql');
    expect(preflightIdx).toBeGreaterThan(-1);
    expect(preflightIdx).toBeLessThan(roleIdx);
    expect(roleIdx).toBeLessThan(assertIdx);
    expect(args).toContain('--single-transaction');
    expect(args).toContain('ON_ERROR_STOP=1');
  });

  itPS('refuses an allowlist value that is not one plain line', () => {
    const { result } = runApply(cleanLines, 0, {
      POS_REVIEWED_SECURITY_DEFINER_FUNCTIONS: "public.reader(); public.other('x')",
    });
    expect(result.status).not.toBe(0);
    expect(result.output).toContain('POS_REVIEWED_SECURITY_DEFINER_FUNCTIONS');
  });

  // ---- non-interactive password delivery (\password reads two lines) -------

  itPS('delivers exactly two password lines on stdin, never in argv', () => {
    const { result, argsFile, stdin } = runApply(cleanLines, 0);
    expect(result.status, result.output).toBe(0);
    // One line per \password prompt. The trailing newline terminates the second
    // line; a CR before it is the Windows host's line ending, not extra input.
    expect(stdin?.replace(/\r\n/g, '\n')).toBe('probe-pass\nprobe-pass\n');
    // The value is not in the Docker argument vector...
    expect(readFileSync(argsFile, 'utf8')).not.toContain('probe-pass');
    // ...and not in the job log, the sanitized summary, or anything left behind.
    expect(result.output).not.toContain('probe-pass');
    expect(JSON.stringify(readEvidence())).not.toContain('probe-pass');
    expect(rawLogs()).toEqual([]);
  });

  itPS('delivers the password twice on the failure path too', () => {
    const { result, stdin } = runApply(
      ['psql: error: connection to server at "db.internal:5432" failed'],
      3,
    );
    expect(result.status, result.output).toBe(3);
    expect(stdin?.replace(/\r\n/g, '\n')).toBe('probe-pass\nprobe-pass\n');
  });

  itPS('refuses a password with an embedded line break before Docker is invoked', () => {
    for (const password of ['probe-pass\nsecond-line', 'probe-pass\rsecond-line']) {
      const { result, argsFile, stdin } = runApply(cleanLines, 0, { PROBE_PW: password });
      expect(result.status, result.output).not.toBe(0);
      expect(result.output).toContain('PROBE_PW must not contain line breaks or NUL');
      // No process was started: no input lines were written and no args exist.
      expect(stdin).toBeNull();
      expect(existsSync(argsFile)).toBe(false);
    }
  });

  itPS('refuses a missing password before Docker is invoked', () => {
    const { result, argsFile, stdin } = runApply(cleanLines, 0, { PROBE_PW: '' });
    expect(result.status).not.toBe(0);
    expect(result.output).toContain('PROBE_ADMIN_PASS and PROBE_PW must be set');
    expect(stdin).toBeNull();
    expect(existsSync(argsFile)).toBe(false);
  });

  itPS('rejects a transcript that carries the password instead of uploading it', () => {
    const { result, evidence } = runApply([...cleanLines, 'password=probe-pass'], 0);
    expect(result.status, result.output).not.toBe(0);
    expect(evidence?.evidence_status).toBe('rejected');
    expect(JSON.stringify(evidence)).not.toContain('probe-pass');
    expect(result.output).not.toContain('probe-pass');
    expect(rawLogs()).toEqual([]);
  });
});
