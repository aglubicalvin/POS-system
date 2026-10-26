import { get, nowIso, run, scalar, transaction, type Db } from '../db/db.ts';
import { appendAudit } from './audit.ts';
import { newUuid } from './ids.ts';
import { enqueue } from './outbox.ts';
import { can, PermissionError, type Role } from './permissions.ts';
import { requireUser } from './users.ts';

/**
 * Shifts.
 *
 * Opening a shift records the starting float; closing it records the counted
 * cash and computes the variance against expected takings, so a drawer that
 * does not match the ledger is visible immediately rather than discovered at
 * month end. Closing with a material variance requires a manager to approve.
 */

export interface ShiftRow {
  shift_id: string;
  device_id: string;
  user_id: string;
  opened_at: string;
  closed_at: string | null;
  opening_float: number;
  cash_count: number | null;
  expected_cash: number | null;
  variance: number | null;
  status: 'open' | 'closed';
  approved_by: string | null;
}

/** Variance beyond this (minor units) needs an approving manager on close. */
export const VARIANCE_APPROVAL_THRESHOLD = 500;

export class ShiftError extends Error {
  override name = 'ShiftError';
}

export function openShift(
  db: Db,
  input: { deviceId: string; userId: string; openingFloat: number; at?: string },
): ShiftRow {
  if (!Number.isInteger(input.openingFloat) || input.openingFloat < 0) {
    throw new ShiftError('opening float must be a non-negative integer in minor units');
  }
  return transaction(db, () => {
    const user = requireUser(db, input.userId);
    if (!can(user.role as Role, 'shift.open')) throw new PermissionError(`role '${user.role}' cannot open a shift`);

    const open = get<{ shift_id: string }>(
      db,
      `SELECT shift_id FROM shifts WHERE device_id = ? AND status = 'open'`,
      input.deviceId,
    );
    if (open) throw new ShiftError(`device already has an open shift (${open.shift_id})`);

    const at = input.at ?? nowIso();
    const shiftId = newUuid();
    run(
      db,
      `INSERT INTO shifts (shift_id, device_id, user_id, opened_at, closed_at, opening_float,
                           cash_count, expected_cash, variance, status, approved_by)
       VALUES (?, ?, ?, ?, NULL, ?, NULL, NULL, NULL, 'open', NULL)`,
      shiftId,
      input.deviceId,
      input.userId,
      at,
      input.openingFloat,
    );

    appendAudit(db, {
      deviceId: input.deviceId,
      actorId: input.userId,
      action: 'shift.opened',
      entityType: 'shift',
      entityId: shiftId,
      occurredAt: at,
      details: { openingFloat: input.openingFloat },
    });
    enqueue(db, {
      deviceId: input.deviceId,
      eventType: 'shift.opened',
      aggregateType: 'shift',
      aggregateId: shiftId,
      payload: { shiftId, userId: input.userId, openingFloat: input.openingFloat, openedAt: at },
    });

    return getShift(db, shiftId)!;
  });
}

/** Cash expected in the drawer: opening float plus confirmed cash takings. */
export function expectedCash(db: Db, shiftId: string): number {
  const shift = getShift(db, shiftId);
  if (!shift) throw new ShiftError(`shift ${shiftId} not found`);
  const cash = Number(
    scalar(
      db,
      `SELECT COALESCE(SUM(p.amount), 0)
         FROM payments p
         JOIN sales s ON s.sale_id = p.sale_id
        WHERE s.shift_id = ? AND p.method = 'cash' AND p.status = 'confirmed'`,
      shiftId,
    ) ?? 0,
  );
  return shift.opening_float + cash;
}

export interface CloseShiftResult {
  shift: ShiftRow;
  expectedCash: number;
  variance: number;
  needsApproval: boolean;
}

export function closeShift(
  db: Db,
  input: { shiftId: string; userId: string; cashCount: number; approverId?: string; at?: string },
): CloseShiftResult {
  if (!Number.isInteger(input.cashCount) || input.cashCount < 0) {
    throw new ShiftError('cash count must be a non-negative integer in minor units');
  }
  return transaction(db, () => {
    const user = requireUser(db, input.userId);
    const shift = getShift(db, input.shiftId);
    if (!shift) throw new ShiftError(`shift ${input.shiftId} not found`);
    if (shift.status !== 'open') throw new ShiftError('shift is already closed');

    const expected = expectedCash(db, input.shiftId);
    const variance = input.cashCount - expected;
    const needsApproval = Math.abs(variance) > VARIANCE_APPROVAL_THRESHOLD;

    let approverId: string | null = null;
    if (needsApproval) {
      if (!input.approverId) throw new PermissionError('cash variance needs manager approval to close the shift');
      const approver = requireUser(db, input.approverId);
      if (!can(approver.role as Role, 'shift.approve')) {
        throw new PermissionError(`role '${approver.role}' cannot approve a shift close`);
      }
      approverId = approver.user_id;
    }
    void user;

    const at = input.at ?? nowIso();
    run(
      db,
      `UPDATE shifts SET closed_at = ?, cash_count = ?, expected_cash = ?, variance = ?, status = 'closed', approved_by = ?
        WHERE shift_id = ?`,
      at,
      input.cashCount,
      expected,
      variance,
      approverId,
      input.shiftId,
    );

    appendAudit(db, {
      deviceId: shift.device_id,
      actorId: input.userId,
      action: 'shift.closed',
      entityType: 'shift',
      entityId: input.shiftId,
      occurredAt: at,
      details: { cashCount: input.cashCount, expected, variance, approverId },
    });
    enqueue(db, {
      deviceId: shift.device_id,
      eventType: 'shift.closed',
      aggregateType: 'shift',
      aggregateId: input.shiftId,
      payload: { shiftId: input.shiftId, userId: input.userId, cashCount: input.cashCount, expected, variance, closedAt: at },
    });

    return { shift: getShift(db, input.shiftId)!, expectedCash: expected, variance, needsApproval };
  });
}

export function getShift(db: Db, shiftId: string): ShiftRow | undefined {
  return get<ShiftRow>(db, 'SELECT * FROM shifts WHERE shift_id = ?', shiftId);
}

export function openShiftForDevice(db: Db, deviceId: string): ShiftRow | undefined {
  return get<ShiftRow>(db, `SELECT * FROM shifts WHERE device_id = ? AND status = 'open'`, deviceId);
}
