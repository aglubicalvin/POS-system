import { beforeEach, describe, expect, it } from 'vitest';
import type { Db } from '../src/db/db.ts';
import { deviceIsAuthorised, looksHashed, registerDevice, rotateToken } from '../src/domain/devices.ts';
import { LOCKOUT_MS, verifyPin } from '../src/domain/users.ts';
import { seedStore, STORE_ID, type SeededStore } from './helpers.ts';

describe('security controls', () => {
  let store: SeededStore;
  let db: Db;

  beforeEach(() => {
    store = seedStore();
    db = store.db;
  });

  it('never stores a PIN or password in the clear', () => {
    const row = db.prepare('SELECT pin_hash FROM users WHERE user_id = ?').get(store.cashier.user_id) as { pin_hash: string };
    expect(row.pin_hash).not.toContain('1234');
    expect(looksHashed(row.pin_hash)).toBe(true);
  });

  it('accepts the right PIN and resets the failure counter', () => {
    expect(verifyPin(db, store.cashier.user_id, '1234', store.deviceId).ok).toBe(true);
    expect(verifyPin(db, store.cashier.user_id, '0000', store.deviceId).ok).toBe(false);
    const ok = verifyPin(db, store.cashier.user_id, '1234', store.deviceId);
    expect(ok.ok).toBe(true);

    const after = db.prepare('SELECT failed_attempts FROM users WHERE user_id = ?').get(store.cashier.user_id) as {
      failed_attempts: number;
    };
    expect(after.failed_attempts).toBe(0);
  });

  it('locks the account after the maximum number of wrong PINs', () => {
    for (let attempt = 0; attempt < 5; attempt += 1) {
      const result = verifyPin(db, store.cashier.user_id, '9999', store.deviceId);
      expect(result.ok).toBe(false);
    }
    const locked = verifyPin(db, store.cashier.user_id, '1234', store.deviceId);
    expect(locked.ok).toBe(false);
    expect(locked.reason).toBe('locked');

    // Still locked before the window elapses...
    expect(verifyPin(db, store.cashier.user_id, '1234', store.deviceId).reason).toBe('locked');
    // ...and usable again once it has.
    const later = new Date(Date.now() + LOCKOUT_MS + 1000);
    expect(verifyPin(db, store.cashier.user_id, '1234', store.deviceId, later).ok).toBe(true);
  });

  it('refuses a PIN presented on a device the cashier is not bound to', () => {
    const other = registerDevice(db, { storeId: STORE_ID, name: 'TILL-9' });
    const result = verifyPin(db, store.cashier.user_id, '1234', other.device.device_id);
    expect(result.ok).toBe(false);
    expect(result.reason).toBe('device-mismatch');
  });

  it('issues short-lived device tokens that stop authorising after expiry', () => {
    const { device } = registerDevice(db, { storeId: STORE_ID, name: 'TILL-2' });
    const now = new Date();
    expect(deviceIsAuthorised(db, device.device_id, now)).toBe(true);

    const later = new Date(now.getTime() + 16 * 60 * 1000);
    expect(deviceIsAuthorised(db, device.device_id, later)).toBe(false);

    rotateToken(db, device.device_id);
    expect(deviceIsAuthorised(db, device.device_id, now)).toBe(true);
  });

  it('refuses to authorise an inactive device', () => {
    const { device } = registerDevice(db, { storeId: STORE_ID, name: 'TILL-3' });
    db.prepare('UPDATE devices SET is_active = 0 WHERE device_id = ?').run(device.device_id);
    expect(deviceIsAuthorised(db, device.device_id)).toBe(false);
  });
});
