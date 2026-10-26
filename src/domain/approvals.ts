import { get, nowIso, run, transaction, type Db } from '../db/db.ts';
import { newUuid } from './ids.ts';
import { can, PermissionError, type Capability, type Role } from './permissions.ts';

/**
 * Manager approval trail.
 *
 * Sensitive actions (voids, price overrides, large adjustments, discounts past
 * the cashier limit) require a second, more privileged user. We persist the
 * approval rather than trusting a boolean, so the audit log can show who
 * authorised what, and the approver's own role is re-checked at use time.
 */

export interface ApprovalRow {
  approval_id: string;
  device_id: string;
  requested_by: string;
  approved_by: string;
  action: string;
  entity_type: string | null;
  entity_id: string | null;
  reason: string | null;
  created_at: string;
}

export interface CreateApprovalInput {
  deviceId: string;
  requestedBy: string;
  approverId: string;
  approverRole: Role;
  action: string;
  entityType?: string;
  entityId?: string;
  reason?: string;
}

/** Which capability an approver must hold to authorise a given action. */
const APPROVAL_CAPABILITY: Record<string, Capability> = {
  'sale.void': 'sale.void',
  'sale.refund': 'sale.refund',
  'price.override': 'price.override',
  'stock.adjust.large': 'stock.adjust.large',
  // A discount past the cashier limit is signed off by a manager, who may always discount.
  'sale.discount.approval': 'sale.discount.any',
};

export function createApproval(db: Db, input: CreateApprovalInput): ApprovalRow {
  const capability = APPROVAL_CAPABILITY[input.action];
  if (!capability) throw new PermissionError(`unknown approval action '${input.action}'`);
  if (!can(input.approverRole, capability)) {
    throw new PermissionError(`role '${input.approverRole}' cannot approve '${input.action}'`);
  }
  return transaction(db, () => {
    const row: ApprovalRow = {
      approval_id: newUuid(),
      device_id: input.deviceId,
      requested_by: input.requestedBy,
      approved_by: input.approverId,
      action: input.action,
      entity_type: input.entityType ?? null,
      entity_id: input.entityId ?? null,
      reason: input.reason ?? null,
      created_at: nowIso(),
    };
    run(
      db,
      `INSERT INTO approvals
         (approval_id, device_id, requested_by, approved_by, action, entity_type, entity_id, reason, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      row.approval_id,
      row.device_id,
      row.requested_by,
      row.approved_by,
      row.action,
      row.entity_type,
      row.entity_id,
      row.reason,
      row.created_at,
    );
    return row;
  });
}

export function findApproval(db: Db, approvalId: string): ApprovalRow | undefined {
  return get<ApprovalRow>(db, 'SELECT * FROM approvals WHERE approval_id = ?', approvalId);
}

/** Throw unless `approvalId` exists, matches the action, and belongs to the device. */
export function assertApproval(
  db: Db,
  approvalId: string | null | undefined,
  action: string,
  deviceId: string,
): ApprovalRow {
  if (!approvalId) throw new PermissionError(`approval required for '${action}'`);
  const approval = findApproval(db, approvalId);
  if (!approval) throw new PermissionError(`approval ${approvalId} not found`);
  if (approval.device_id !== deviceId) throw new PermissionError('approval belongs to a different device');
  if (approval.action !== action) {
    throw new PermissionError(`approval is for '${approval.action}', not '${action}'`);
  }
  return approval;
}
