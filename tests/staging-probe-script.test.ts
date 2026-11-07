import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
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
    const bin = join(dir, 'bin');
    mkdirSync(bin, { recursive: true });
    if (process.platform === 'win32') {
      writeFileSync(
        join(bin, 'docker.cmd'),
        `@echo off\r\necho %* > "${argsFile}"\r\nexit /b 0\r\n`,
        'utf8',
      );
    } else {
      const shim = join(bin, 'docker');
      writeFileSync(shim, `#!/bin/sh\nprintf '%s\\n' "$*" > "${argsFile}"\nexit 0\n`, 'utf8');
      chmodSync(shim, 0o755);
    }
    return bin;
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
