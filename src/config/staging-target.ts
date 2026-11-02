/**
 * Staging target identity.
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
 */

export const STAGING_REF_ENV = 'POS_STAGING_PROJECT_REF';
export const STAGING_ADMIN_USER_ENV = 'POS_STAGING_ADMIN_USER';
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

export const STAGING_DATABASE = 'postgres';

export interface StagingTarget {
  projectRef: string;
  adminUser: string;
  database: string;
}

export type StagingBlockCode =
  | 'missing-ref'
  | 'malformed-ref'
  | 'non-staging-ref'
  | 'missing-admin-user'
  | 'malformed-admin-user'
  | 'ref-mismatch';

export type StagingResolution =
  | { ok: true; target: StagingTarget }
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

/** Redacted description of a target, safe for logs and reports. */
export function describeStagingTarget(target: StagingTarget): string {
  return `project=${target.projectRef} user=${target.adminUser} database=${target.database}`;
}
