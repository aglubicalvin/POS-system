import { randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';
import { get, nowIso, run, transaction, type Db } from '../db/db.ts';
import { eventIdempotencyKey, newUuid } from './ids.ts';
import { enqueue } from './outbox.ts';
import type { Role } from './permissions.ts';
import { PasswordHasher as Argon2Hasher, type LegacyVerifier } from '../auth/password-hasher.ts';

/**
 * Users, credentials, and the cashier-PIN controls the spec requires: lockout
 * after repeated failures and binding a cashier to the device they were enrolled
 * on, so a stolen PIN is useless on another till.
 *
 * Hashing defaults to Argon2id via `node:crypto` (Node >= 24.7, still
 * dependency-free); the stored format is prefixed so previously created scrypt
 * hashes keep verifying and are upgraded lazily on the next successful login.
 * `setPasswordHasher` still allows a custom implementation, e.g. different
 * cost parameters for very slow devices.
 */

export interface UserRow {
  user_id: string;
  device_id: string | null;
  full_name: string;
  role: Role;
  pin_hash: string | null;
  password_hash: string | null;
  mfa_enabled: number;
  failed_attempts: number;
  locked_until: string | null;
  is_active: number;
  created_at: string;
  updated_at: string;
}

export const MAX_FAILED_ATTEMPTS = 5;
export const LOCKOUT_MS = 15 * 60 * 1000;

export interface PasswordHasher {
  id: string;
  hash(secret: string): string;
  verify(secret: string, stored: string): boolean;
  /** True when the stored hash should be replaced on the next successful verify. */
  needsRehash?(stored: string): boolean;
}

const scryptHasher: PasswordHasher = {
  id: 'scrypt',
  hash(secret) {
    const salt = randomBytes(16);
    const derived = scryptSync(secret, salt, 64);
    return `scrypt$16384$8$1$${salt.toString('base64')}$${derived.toString('base64')}`;
  },
  verify(secret, stored) {
    const parts = stored.split('$');
    if (parts.length !== 6 || parts[0] !== 'scrypt') return false;
    const [, n, r, p, salt, expected] = parts as [string, string, string, string, string, string];
    const saltBuf = Buffer.from(salt, 'base64');
    const expectedBuf = Buffer.from(expected, 'base64');
    const actual = scryptSync(secret, saltBuf, expectedBuf.length, {
      N: Number(n),
      r: Number(r),
      p: Number(p),
    });
    return actual.length === expectedBuf.length && timingSafeEqual(actual, expectedBuf);
  },
};

/** The pre-argon2 format of this codebase; keeps old hashes working at login. */
const scryptLegacy: LegacyVerifier = {
  matches: (stored) => stored.startsWith('scrypt$'),
  verify: (secret, stored) => scryptHasher.verify(secret, stored),
};

const argon2Hasher = new Argon2Hasher({ legacy: [scryptLegacy] });

let hasher: PasswordHasher = {
  id: 'argon2id',
  hash: (secret) => argon2Hasher.hash(secret),
  verify: (secret, stored) => argon2Hasher.verify(secret, stored),
  needsRehash: (stored) => argon2Hasher.needsRehash(stored),
};

/** Swap in a custom hasher (e.g. different cost parameters) at start-up. */
export function setPasswordHasher(next: PasswordHasher): void {
  hasher = next;
}

export function hashSecret(secret: string): string {
  return hasher.hash(secret);
}

export function verifySecret(secret: string, stored: string): boolean {
  return hasher.verify(secret, stored);
}

export class AuthError extends Error {
  override name = 'AuthError';
}

export interface CreateUserInput {
  deviceId?: string | null;
  /** The device performing the enrollment; only needed for the sync event.
   * A bound cashier sets both to the same value; a store-global manager or
   * admin (null binding) still has a device that must CLAIM the upload. */
  emittedByDevice?: string;
  fullName: string;
  role: Role;
  pin?: string;
  password?: string;
  mfaEnabled?: boolean;
}

/**
 * User enrollment is a sync event too.
 *
 * The payload is the full row minus one deliberate omission: `device_id` is
 * nulled out. A relaying till cannot know the enrolling device, and a cashier's
 * PIN is only ever valid on the device it was enrolled on — so every OTHER
 * device must receive a store-global row (`apply.ts` refuses bound relayed
 * users). The creating device keeps the binding in its local `users` row; the
 * event just does not ship it.
 */
function emitUserUpsert(db: Db, emitterDeviceId: string | undefined, user: UserRow): void {
  const deviceId = emitterDeviceId ?? user.device_id;
  if (!deviceId) {
    // No device claims this enrollment — e.g. a headless bootstrap admin — so
    // there is no outbox it could drain from and no route the server would
    // accept (the server refuses events claiming another device). Nothing to
    // enqueue; the row is still local, which is what a device-less enrollment
    // means.
    return;
  }
  // Per-call key: enrollment is a distinct logical event, not a retry.
  enqueue(db, {
    deviceId,
    eventType: 'user.upserted',
    aggregateType: 'user',
    aggregateId: user.user_id,
    payload: { ...user, device_id: null },
    createdAt: user.created_at,
    idempotencyKey: `${eventIdempotencyKey(deviceId, 'user.upserted', user.user_id)}:${newUuid()}`,
  });
}

export function createUser(db: Db, input: CreateUserInput, userId: string = newUuid()): UserRow {
  return transaction(db, () => {
    const at = nowIso();
    run(
      db,
      `INSERT INTO users
         (user_id, device_id, full_name, role, pin_hash, password_hash, mfa_enabled,
          failed_attempts, locked_until, is_active, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, 0, NULL, 1, ?, ?)`,
      userId,
      input.deviceId ?? null,
      input.fullName,
      input.role,
      input.pin ? hashSecret(input.pin) : null,
      input.password ? hashSecret(input.password) : null,
      input.mfaEnabled ? 1 : 0,
      at,
      at,
    );
    const user = getUser(db, userId)!;
    emitUserUpsert(db, input.emittedByDevice, user);
    return user;
  });
}

export function getUser(db: Db, userId: string): UserRow | undefined {
  return get<UserRow>(db, 'SELECT * FROM users WHERE user_id = ?', userId);
}

export function requireUser(db: Db, userId: string): UserRow {
  const user = getUser(db, userId);
  if (!user) throw new AuthError(`user ${userId} not found`);
  if (!user.is_active) throw new AuthError(`user ${userId} is deactivated`);
  return user;
}

export interface PinResult {
  ok: boolean;
  reason?: 'unknown-user' | 'no-pin' | 'device-mismatch' | 'locked' | 'bad-pin' | 'inactive';
  user?: UserRow;
  attemptsRemaining?: number;
}

/**
 * Verify a cashier PIN with device binding and lockout. A wrong PIN increments
 * `failed_attempts`; at the threshold the account locks until `locked_until`.
 * A device mismatch is refused without revealing whether the PIN was correct.
 */
export function verifyPin(db: Db, userId: string, pin: string, deviceId: string, at: Date = new Date()): PinResult {
  return transaction(db, () => {
    const user = getUser(db, userId);
    if (!user) return { ok: false, reason: 'unknown-user' };
    if (!user.is_active) return { ok: false, reason: 'inactive' };
    if (!user.pin_hash) return { ok: false, reason: 'no-pin' };
    if (user.device_id && user.device_id !== deviceId) return { ok: false, reason: 'device-mismatch' };
    if (user.locked_until && new Date(user.locked_until).getTime() > at.getTime()) {
      return { ok: false, reason: 'locked', user };
    }

    const verified = verifySecret(pin, user.pin_hash);
    if (!verified) {
      const failed = user.failed_attempts + 1;
      const lockedUntil = failed >= MAX_FAILED_ATTEMPTS ? new Date(at.getTime() + LOCKOUT_MS).toISOString() : null;
      run(
        db,
        'UPDATE users SET failed_attempts = ?, locked_until = ?, updated_at = ? WHERE user_id = ?',
        failed,
        lockedUntil,
        nowIso(),
        userId,
      );
      return {
        ok: false,
        reason: 'bad-pin',
        attemptsRemaining: Math.max(0, MAX_FAILED_ATTEMPTS - failed),
      };
    }

    // Lazy migration: scrypt hashes (or argon2id made with weaker parameters)
    // are replaced at each successful login, per the OWASP-cut-down guidance.
    const upgradedHash = hasher.needsRehash?.(user.pin_hash) === true ? hashSecret(pin) : null;
    run(
      db,
      upgradedHash
        ? 'UPDATE users SET pin_hash = ?, failed_attempts = 0, locked_until = NULL, updated_at = ? WHERE user_id = ?'
        : 'UPDATE users SET failed_attempts = 0, locked_until = NULL, updated_at = ? WHERE user_id = ?',
      ...(upgradedHash ? [upgradedHash] : []),
      nowIso(),
      userId,
    );
    return { ok: true, user: { ...user, failed_attempts: 0, locked_until: null, ...(upgradedHash ? { pin_hash: upgradedHash } : {}) } };
  });
}

/** Manager/admin override: clear a lockout after identity is confirmed out-of-band. */
export function unlockUser(db: Db, userId: string): void {
  run(
    db,
    'UPDATE users SET failed_attempts = 0, locked_until = NULL, updated_at = ? WHERE user_id = ?',
    nowIso(),
    userId,
  );
}

/** Bind (or rebind) a cashier to a device. Admin action. */
export function bindUserToDevice(db: Db, userId: string, deviceId: string | null): void {
  run(db, 'UPDATE users SET device_id = ?, updated_at = ? WHERE user_id = ?', deviceId, nowIso(), userId);
}

/**
 * Upsert used by sync when the server relays a store-global user (a manager or
 * admin enrolled on another till) down to this one. The caller has already
 * validated the row and ensured the secrets are hashes, not plaintext.
 * A device-bound user (`device_id`) cannot be relayed: this till does not know
 * the other till's device, and a cashier PIN is only ever valid on the device
 * it was enrolled on (`verifyPin` refuses `device-mismatch` anyway).
 */
export function upsertUser(db: Db, user: Omit<UserRow, 'device_id'> & { device_id: null }): void {
  run(
    db,
    `INSERT INTO users
       (user_id, device_id, full_name, role, pin_hash, password_hash, mfa_enabled,
        failed_attempts, locked_until, is_active, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (user_id) DO UPDATE SET
       device_id = NULL, full_name = excluded.full_name, role = excluded.role,
       pin_hash = excluded.pin_hash, password_hash = excluded.password_hash,
       mfa_enabled = excluded.mfa_enabled, failed_attempts = excluded.failed_attempts,
       locked_until = excluded.locked_until, is_active = excluded.is_active,
       updated_at = excluded.updated_at`,
    user.user_id,
    null,
    user.full_name,
    user.role,
    user.pin_hash,
    user.password_hash,
    user.mfa_enabled,
    user.failed_attempts,
    user.locked_until,
    user.is_active,
    user.created_at,
    user.updated_at,
  );
}
