/**
 * Staging target identity and connection contract.
 *
 * The staging project reference has no default and is never inferred from a
 * hostname, a documented project or an existing username suffix. The project
 * owner must set `POS_STAGING_PROJECT_REF`; until then every caller gets a
 * BLOCKED result and must not open a connection.
 *
 * Why this exists: the Supabase pooler hostname is shared by every project in a
 * region, so `aws-0-eu-west-2.pooler.supabase.com` identifies nothing. The only
 * per-project signal in a libpq/JDBC configuration is the administrator
 * username `postgres.<project-ref>`, and that is only trustworthy when it is
 * compared against an independently supplied reference.
 *
 * The same contract is enforced by the JDBC identity probe (server/tools/
 * ProbeState.java) and the PowerShell role-creation path (server/tools/
 * probe-readonly.ps1). Both take the pooler hostname from
 * `POS_STAGING_POOLER_HOST` and nothing else, and both require `verify-full`
 * TLS with a staged root certificate whose SHA-256 fingerprint is supplied
 * separately.
 */

export const STAGING_REF_ENV = 'POS_STAGING_PROJECT_REF';
export const STAGING_ADMIN_USER_ENV = 'POS_STAGING_ADMIN_USER';
/** The only source of the staging pooler hostname. Never inferred, never defaulted. */
export const STAGING_POOLER_HOST_ENV = 'POS_STAGING_POOLER_HOST';
/** Alias kept so callers can name the variable after the environment key. */
export const POS_STAGING_POOLER_HOST = STAGING_POOLER_HOST_ENV;
/** Staged root certificate file (PEM) for the staging connection. */
export const STAGING_DB_ROOT_CERT_ENV = 'POS_STAGING_DB_ROOT_CERT';
/** SHA-256 of the staged certificate's DER encoding, supplied independently. */
export const STAGING_DB_ROOT_CERT_SHA256_ENV = 'POS_STAGING_DB_ROOT_CERT_SHA256';
/**
 * References that are known NOT to be staging. Defaults to the project this
 * repository documents as development; override with a comma-separated list.
 * This is a deny-list, never a designation: it cannot make anything staging.
 */
export const NON_STAGING_REFS_ENV = 'POS_NON_STAGING_REFS';
export const KNOWN_NON_STAGING_REFS: readonly string[] = [
  // development
  'uemdrtrzlvhdtonccczt',
  // former verification project (paused under the three-environment plan)
  'numwzjogaanfhlrfgrqk',
];

/** Supabase project references are 20 lowercase alphanumerics. */
export const PROJECT_REF_PATTERN = /^[a-z0-9]{20}$/;
/** The pooler administrator login for a project. */
export const ADMIN_USER_PATTERN = /^postgres\.([a-z0-9]{20})$/;
/**
 * The exact shape of a Supabase pooler endpoint: `aws-<n>-<region>` under
 * `pooler.supabase.com`. Anchored, so IP literals, loopback names, arbitrary
 * DNS suffixes, URIs, paths, credentials, query strings and ports cannot pass.
 * The region is part of the configured value; it is never derived.
 */
export const SUPABASE_POOLER_HOST_PATTERN =
  /^aws-(?<n>\d{1,3})-(?<region>[a-z]{2,}(?:-[a-z0-9]+)*)\.pooler\.supabase\.com$/;
/** 64 hex characters: a SHA-256 digest. */
export const STAGING_FINGERPRINT_HEX_PATTERN = /^[a-f0-9]{64}$/;

export const STAGING_DATABASE = 'postgres';
/** The only accepted TLS mode for staging. There is no fallback. */
export const STAGING_SSL_MODE = 'verify-full';

export interface StagingTarget {
  projectRef: string;
  adminUser: string;
  database: string;
}

export interface StagingPoolerHost {
  /** Shard number from `aws-<n>-...`. */
  readonly n: string;
  /** Region as configured, e.g. `eu-west-2`. */
  readonly region: string;
}

export type StagingBlockCode =
  | 'missing-ref'
  | 'malformed-ref'
  | 'non-staging-ref'
  | 'missing-admin-user'
  | 'malformed-admin-user'
  | 'ref-mismatch'
  | 'missing-staging-pooler-host'
  | 'malformed-staging-pooler-host'
  | 'missing-staging-root-certificate'
  | 'missing-staging-root-certificate-fingerprint'
  | 'malformed-staging-root-certificate-fingerprint';

export type StagingResolution =
  | { ok: true; target: StagingTarget }
  | { ok: false; code: StagingBlockCode; message: string };

export type StagingPoolerHostResolution =
  | { ok: true; host: StagingPoolerHost }
  | {
      ok: false;
      code: 'missing-staging-pooler-host' | 'malformed-staging-pooler-host';
      message: string;
    };

export type StagingTlsResolution =
  | { ok: true; certPath: string; fingerprint: string }
  | {
      ok: false;
      code:
        | 'missing-staging-root-certificate'
        | 'missing-staging-root-certificate-fingerprint'
        | 'malformed-staging-root-certificate-fingerprint';
      message: string;
    };

/**
 * Everything a staging connection needs, validated together. Used by the JDBC
 * identity-probe path and the PowerShell role-creation path so there is one
 * contract rather than two parallel copies. Every field is required; none has
 * a default.
 */
export interface StagingConnectionConfig {
  readonly projectRef: string;
  readonly adminUser: string;
  readonly database: string;
  readonly poolerHost: StagingPoolerHost;
  readonly rootCertPath: string;
  readonly rootCertFingerprint: string;
  readonly sslMode: typeof STAGING_SSL_MODE;
}

export type StagingConnectionResolution =
  | { ok: true; config: StagingConnectionConfig }
  | { ok: false; code: StagingBlockCode; message: string };

export class StagingTargetError extends Error {
  override name = 'StagingTargetError';
  /** Assigned explicitly rather than as a TypeScript parameter property:
   *  strip-only Node (>=23) cannot parse parameter properties, and this module
   *  is run directly by `npm run staging:check`. */
  readonly code: StagingBlockCode | 'unexpected';
  constructor(code: StagingBlockCode | 'unexpected', message: string) {
    super(message);
    this.code = code;
  }
}

type Env = Record<string, string | undefined>;

function blocked(code: StagingBlockCode, message: string): StagingResolution {
  return { ok: false, code, message };
}

function nonStagingRefs(env: Env): string[] {
  const raw = env[NON_STAGING_REFS_ENV];
  const list = raw === undefined ? KNOWN_NON_STAGING_REFS : raw.split(',');
  return list.map((value) => value.trim().toLowerCase()).filter((value) => value !== '');
}

/**
 * Resolve the staging target, or explain why it cannot be resolved.
 *
 * Every failure is BLOCKED: the caller must not attempt a connection, because a
 * wrong project is indistinguishable from a right one once a connection is up.
 */
export function resolveStagingTarget(env: Env): StagingResolution {
  const rawRef = env[STAGING_REF_ENV];
  if (rawRef === undefined || rawRef.trim() === '') {
    return blocked(
      'missing-ref',
      `BLOCKED: ${STAGING_REF_ENV} has not been explicitly configured. ` +
        'The staging project must be designated by the project owner before any identity check or deployment.',
    );
  }

  const projectRef = rawRef.trim();
  if (!PROJECT_REF_PATTERN.test(projectRef)) {
    return blocked(
      'malformed-ref',
      `BLOCKED: ${STAGING_REF_ENV} is not a valid Supabase project reference ` +
        '(expected 20 lowercase letters or digits).',
    );
  }

  if (nonStagingRefs(env).includes(projectRef)) {
    return blocked(
      'non-staging-ref',
      `BLOCKED: ${projectRef} is configured as a known non-staging project. ` +
        'Refusing to treat a development database as staging; set a dedicated staging reference.',
    );
  }

  const rawUser = env[STAGING_ADMIN_USER_ENV];
  if (rawUser === undefined || rawUser.trim() === '') {
    return blocked(
      'missing-admin-user',
      `BLOCKED: ${STAGING_ADMIN_USER_ENV} has not been configured. ` +
        `Expected postgres.${projectRef}.`,
    );
  }

  const adminUser = rawUser.trim();
  const parsed = ADMIN_USER_PATTERN.exec(adminUser);
  if (!parsed) {
    return blocked(
      'malformed-admin-user',
      `BLOCKED: ${STAGING_ADMIN_USER_ENV} is malformed. ` +
        `Expected the pooler administrator form postgres.<project-ref>.`,
    );
  }

  const suffix = parsed[1]!;
  if (suffix !== projectRef) {
    return blocked(
      'ref-mismatch',
      `BLOCKED: administrator username belongs to project ${suffix} but ` +
        `${STAGING_REF_ENV} designates ${projectRef}. Refusing to continue.`,
    );
  }

  return {
    ok: true,
    target: { projectRef, adminUser, database: STAGING_DATABASE },
  };
}

/** Throwing form for code paths that must stop rather than branch. */
export function requireStagingTarget(env: Env = process.env): StagingTarget {
  const resolution = resolveStagingTarget(env);
  if (!resolution.ok) throw new StagingTargetError(resolution.code, resolution.message);
  return resolution.target;
}

/**
 * Resolve and validate the staging pooler hostname, or explain why it cannot be.
 *
 * `POS_STAGING_POOLER_HOST` is the single source of truth for the staging
 * pooler endpoint. The JDBC probe and the PowerShell path both consume this
 * same value, re-implemented identically, so the two paths cannot drift onto
 * different hosts.
 */
export function resolveStagingPoolerHost(env: Env): StagingPoolerHostResolution {
  const raw = env[STAGING_POOLER_HOST_ENV];
  if (raw === undefined || raw.trim() === '') {
    return {
      ok: false,
      code: 'missing-staging-pooler-host',
      message:
        `BLOCKED: ${STAGING_POOLER_HOST_ENV} has not been explicitly configured. ` +
        'The staging connection must use an approved Supabase pooler hostname. There is no default, ' +
        'no previously used region, and no fallback: a pooler hostname is shared by every project in ' +
        'a region, so it identifies the provider and never the project. Project identity is still ' +
        `established by ${STAGING_ADMIN_USER_ENV} being compared against ${STAGING_REF_ENV}.`,
    };
  }

  const normalised = raw.trim().toLowerCase();
  const match = SUPABASE_POOLER_HOST_PATTERN.exec(normalised);
  if (!match) {
    return {
      ok: false,
      code: 'malformed-staging-pooler-host',
      message:
        `BLOCKED: ${STAGING_POOLER_HOST_ENV} does not name an approved Supabase pooler endpoint. ` +
        'Expected exactly aws-<n>-<region>.pooler.supabase.com, with <n> one or more digits and ' +
        '<region> one or more lowercase letters, digits or hyphens. IP literals, loopback names, ' +
        'arbitrary DNS names, URIs, paths, credentials, query strings and ports are refused. The ' +
        'region must be confirmed by a human against the staging project dashboard; it is never ' +
        'inferred from the project reference or from a previously used endpoint.',
    };
  }

  const groups = match.groups;
  if (groups === undefined) {
    return {
      ok: false,
      code: 'malformed-staging-pooler-host',
      message: `BLOCKED: ${STAGING_POOLER_HOST_ENV} could not be parsed.`,
    };
  }

  return { ok: true, host: { n: groups.n!, region: groups.region! } };
}

/** Throwing form for code paths that must stop rather than branch. */
export function requireStagingPoolerHost(env: Env = process.env): StagingPoolerHost {
  const resolution = resolveStagingPoolerHost(env);
  if (!resolution.ok) throw new StagingTargetError(resolution.code, resolution.message);
  return resolution.host;
}

/**
 * Validate the staged TLS material: exactly one certificate file, plus the
 * SHA-256 of that certificate's DER encoding supplied from an independent
 * source. Checking only that a file exists would accept any CA a caller names.
 */
export function validateStagingTlsEnv(env: Env): StagingTlsResolution {
  const certPath = (env[STAGING_DB_ROOT_CERT_ENV] ?? '').trim();
  if (certPath === '') {
    return {
      ok: false,
      code: 'missing-staging-root-certificate',
      message:
        `BLOCKED: ${STAGING_DB_ROOT_CERT_ENV} has not been explicitly configured. ` +
        'The staging connection must pin the Supabase root certificate (Dashboard: Database ' +
        'Settings -> SSL Configuration). The certificate file and its independently supplied ' +
        'SHA-256 fingerprint are both required; there is no default and no unverified fallback.',
    };
  }

  const rawFingerprint = env[STAGING_DB_ROOT_CERT_SHA256_ENV];
  if (rawFingerprint === undefined || rawFingerprint.trim() === '') {
    return {
      ok: false,
      code: 'missing-staging-root-certificate-fingerprint',
      message:
        `BLOCKED: ${STAGING_DB_ROOT_CERT_SHA256_ENV} has not been explicitly configured. ` +
        'A staged certificate without an independently supplied fingerprint would accept any CA ' +
        'the caller names and would not establish who the server is.',
    };
  }

  const fingerprint = rawFingerprint.trim().replace(/:/g, '').toLowerCase();
  if (!STAGING_FINGERPRINT_HEX_PATTERN.test(fingerprint)) {
    return {
      ok: false,
      code: 'malformed-staging-root-certificate-fingerprint',
      message:
        `BLOCKED: ${STAGING_DB_ROOT_CERT_SHA256_ENV} is not a 64-character hex SHA-256 digest. ` +
        `Recompute it over the DER encoding of ${STAGING_DB_ROOT_CERT_ENV} from the published value.`,
    };
  }

  return { ok: true, certPath, fingerprint };
}

/** Throwing form for code paths that must stop rather than branch. */
export function requireStagingTlsEnv(
  env: Env = process.env,
): { certPath: string; fingerprint: string } {
  const resolution = validateStagingTlsEnv(env);
  if (!resolution.ok) throw new StagingTargetError(resolution.code, resolution.message);
  return { certPath: resolution.certPath, fingerprint: resolution.fingerprint };
}

/**
 * The whole staging connection contract, in one place. Fields are resolved in
 * a fixed order so the first missing or malformed value is the one reported.
 */
export function resolveStagingConnectionConfig(env: Env): StagingConnectionResolution {
  const target = resolveStagingTarget(env);
  if (!target.ok) return target;
  const host = resolveStagingPoolerHost(env);
  if (!host.ok) return host;
  const tls = validateStagingTlsEnv(env);
  if (!tls.ok) return tls;

  return {
    ok: true,
    config: {
      projectRef: target.target.projectRef,
      adminUser: target.target.adminUser,
      database: target.target.database,
      poolerHost: host.host,
      rootCertPath: tls.certPath,
      rootCertFingerprint: tls.fingerprint,
      sslMode: STAGING_SSL_MODE,
    },
  };
}

/** Throwing form for code paths that must stop rather than branch. */
export function requireStagingConnectionConfig(
  env: Env = process.env,
): StagingConnectionConfig {
  const resolution = resolveStagingConnectionConfig(env);
  if (!resolution.ok) throw new StagingTargetError(resolution.code, resolution.message);
  return resolution.config;
}

/** Redacted description of a target, safe for logs and reports. */
export function describeStagingTarget(target: StagingTarget): string {
  return `project=${target.projectRef} user=${target.adminUser} database=${target.database}`;
}

/** Redacted description of a pooler endpoint, reconstructed from validated parts. */
export function describePoolerHost(host: StagingPoolerHost): string {
  return `pooler=aws-${host.n}-${host.region}.pooler.supabase.com`;
}

/**
 * Redacted description of a connection, safe for logs and reports: no
 * certificate contents, no fingerprint value, no credentials.
 */
export function describeStagingConnection(config: StagingConnectionConfig): string {
  return (
    `${describeStagingTarget(config)} ${describePoolerHost(config.poolerHost)} ` +
    `sslmode=${config.sslMode} rootcert=staged fingerprint=verified`
  );
}
