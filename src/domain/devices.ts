import { createHash, randomBytes } from 'node:crypto';
import { get, nowIso, run, transaction, type Db } from '../db/db.ts';
import { newDeviceId } from './ids.ts';

/**
 * Device registration and short-lived tokens.
 *
 * A till is registered once and receives an opaque token with a short expiry.
 * Only the SHA-256 hash of the token is persisted, so a database dump does not
 * hand over live credentials. `deviceIsAuthorised` additionally requires that a
 * live, unrevoked token exists — expiry alone is not the whole check.
 *
 * The Kotlin server keeps the same model in `DeviceService.kt` against the same
 * `device_tokens` table.
 */

export const TOKEN_TTL_MS = 15 * 60 * 1000;

export interface DeviceRow {
  device_id: string;
  store_id: string;
  name: string;
  model: string | null;
  registered_at: string;
  last_seen_at: string | null;
  token_expires_at: string | null;
  is_active: number;
}

export interface RegisteredDevice {
  device: DeviceRow;
  /** Returned exactly once, at registration/rotation. Store in the OS keystore. */
  token: string;
}

export class DeviceError extends Error {
  override name = 'DeviceError';
}

export function hashToken(token: string): string {
  return createHash('sha256').update(token).digest('hex');
}

function newToken(): string {
  return randomBytes(32).toString('base64url');
}

function persistToken(db: Db, deviceId: string, token: string, issuedAt: Date, expiresAt: Date): void {
  run(
    db,
    `INSERT INTO device_tokens (token_hash, device_id, issued_at, expires_at, revoked_at)
     VALUES (?, ?, ?, ?, NULL)`,
    hashToken(token),
    deviceId,
    issuedAt.toISOString(),
    expiresAt.toISOString(),
  );
  run(
    db,
    'UPDATE devices SET last_seen_at = ?, token_expires_at = ? WHERE device_id = ?',
    issuedAt.toISOString(),
    expiresAt.toISOString(),
    deviceId,
  );
}

export function registerDevice(
  db: Db,
  input: { storeId: string; name: string; model?: string; deviceId?: string },
): RegisteredDevice {
  return transaction(db, () => {
    const deviceId = input.deviceId ?? newDeviceId();
    const token = newToken();
    const issuedAt = new Date();
    const expiresAt = new Date(issuedAt.getTime() + TOKEN_TTL_MS);

    run(
      db,
      `INSERT INTO devices (device_id, store_id, name, model, registered_at, last_seen_at, token_expires_at, is_active)
       VALUES (?, ?, ?, ?, ?, NULL, NULL, 1)`,
      deviceId,
      input.storeId,
      input.name,
      input.model ?? null,
      issuedAt.toISOString(),
    );
    persistToken(db, deviceId, token, issuedAt, expiresAt);

    return { device: getDevice(db, deviceId)!, token };
  });
}

export function getDevice(db: Db, deviceId: string): DeviceRow | undefined {
  return get<DeviceRow>(db, 'SELECT * FROM devices WHERE device_id = ?', deviceId);
}

/** Issue a fresh token and revoke any previous ones for this device. */
export function rotateToken(db: Db, deviceId: string): string {
  return transaction(db, () => {
    const token = newToken();
    const issuedAt = new Date();
    const expiresAt = new Date(issuedAt.getTime() + TOKEN_TTL_MS);
    run(
      db,
      'UPDATE device_tokens SET revoked_at = ? WHERE device_id = ? AND revoked_at IS NULL',
      issuedAt.toISOString(),
      deviceId,
    );
    persistToken(db, deviceId, token, issuedAt, expiresAt);
    return token;
  });
}

/** Resolve a presented token to its device, or undefined if not usable. */
export function verifyDeviceToken(db: Db, token: string, at: Date = new Date()): DeviceRow | undefined {
  const row = get<{ device_id: string }>(
    db,
    `SELECT device_id FROM device_tokens
      WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?`,
    hashToken(token),
    at.toISOString(),
  );
  if (!row) return undefined;
  const device = getDevice(db, row.device_id);
  if (!device || !device.is_active) return undefined;
  return device;
}

/** True while the device is active and holds an unrevoked, unexpired token. */
export function deviceIsAuthorised(db: Db, deviceId: string, at: Date = new Date()): boolean {
  const device = getDevice(db, deviceId);
  if (!device || !device.is_active) return false;
  if (!device.token_expires_at) return false;
  if (new Date(device.token_expires_at).getTime() <= at.getTime()) return false;
  const live = get<{ n: number }>(
    db,
    `SELECT COUNT(*) AS n FROM device_tokens
      WHERE device_id = ? AND revoked_at IS NULL AND expires_at > ?`,
    deviceId,
    at.toISOString(),
  );
  return (live?.n ?? 0) > 0;
}

/** Password/secret-strength helper used by tests to assert hashing happened. */
export function looksHashed(value: string): boolean {
  return /^scrypt\$/.test(value) || /^\$argon2id\$v=\d+\$/.test(value);
}

export { nowIso };
