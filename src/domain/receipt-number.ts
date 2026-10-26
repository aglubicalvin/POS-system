import { get, nowIso, run, transaction, type Db } from '../db/db.ts';

/**
 * Per-device receipt numbers in DEVICE-DATE-SEQ form, e.g. `1A2B3C4D-20261009-0007`.
 *
 * The sequence is allocated from a per-(device, day) counter *inside* the
 * checkout transaction, so:
 *   - two devices can never collide (device prefix is part of the key),
 *   - a device that is offline all day still produces a gapless sequence,
 *   - a rolled-back sale does not burn a number.
 */

export type ReceiptNumber = string;

/** Short, human-typable device prefix derived from the device UUID. */
export function devicePrefix(deviceId: string): string {
  const compact = deviceId.replace(/-/g, '').toUpperCase();
  return compact.slice(0, 8);
}

export function dayKey(at: Date | string = new Date()): string {
  const iso = typeof at === 'string' ? at : at.toISOString();
  return iso.slice(0, 10).replace(/-/g, '');
}

export function formatReceiptNumber(deviceId: string, day: string, seq: number): ReceiptNumber {
  if (!Number.isInteger(seq) || seq <= 0) throw new Error(`receipt sequence must be a positive integer, got ${seq}`);
  return `${devicePrefix(deviceId)}-${day}-${String(seq).padStart(4, '0')}`;
}

/** Parse a receipt number back into its parts; used by reports and reprints. */
export function parseReceiptNumber(receipt: string): { prefix: string; day: string; seq: number } {
  const match = /^([0-9A-F]{8})-(\d{8})-(\d{4,})$/.exec(receipt);
  if (!match) throw new Error(`malformed receipt number: ${receipt}`);
  const [, prefix, day, seq] = match as unknown as [string, string, string, string];
  return { prefix, day, seq: Number(seq) };
}

/**
 * Allocate the next receipt number for this device/day. MUST be called inside the
 * same transaction as the sale insert so the counter and the sale commit together.
 */
export function nextReceiptNumber(db: Db, deviceId: string, at: Date | string = new Date()): ReceiptNumber {
  const day = dayKey(at);
  return transaction(db, () => {
    const existing = get<{ last_seq: number }>(
      db,
      'SELECT last_seq FROM receipt_counters WHERE device_id = ? AND day = ?',
      deviceId,
      day,
    );
    const next = (existing?.last_seq ?? 0) + 1;
    if (existing) {
      run(db, 'UPDATE receipt_counters SET last_seq = ? WHERE device_id = ? AND day = ?', next, deviceId, day);
    } else {
      run(
        db,
        'INSERT INTO receipt_counters (device_id, day, last_seq) VALUES (?, ?, ?)',
        deviceId,
        day,
        next,
      );
    }
    return formatReceiptNumber(deviceId, day, next);
  });
}

/** Current high-water mark for a device/day, for shift-close reconciliation. */
export function currentReceiptSeq(db: Db, deviceId: string, at: Date | string = new Date()): number {
  const row = get<{ last_seq: number }>(
    db,
    'SELECT last_seq FROM receipt_counters WHERE device_id = ? AND day = ?',
    deviceId,
    dayKey(at),
  );
  return row?.last_seq ?? 0;
}

export { nowIso };
