/**
 * Role model and the controls the spec calls out: who may void, discount,
 * override a price, and adjust stock — and which of those need a second pair of
 * eyes (manager approval).
 */

export type Role = 'cashier' | 'manager' | 'admin';

export type Capability =
  | 'sale.create'
  | 'sale.void'
  | 'sale.refund'
  | 'sale.discount'
  | 'sale.discount.any'
  | 'price.override'
  | 'stock.adjust'
  | 'stock.adjust.large'
  | 'stock.disburse'
  | 'grn.receive'
  | 'shift.open'
  | 'shift.close'
  | 'shift.approve'
  | 'payment.reconcile'
  | 'report.view'
  | 'user.manage'
  | 'device.register';

const MATRIX: Record<Role, readonly Capability[]> = {
  cashier: ['sale.create', 'sale.discount', 'shift.open', 'shift.close', 'grn.receive', 'report.view'],
  manager: [
    'sale.create', 'sale.void', 'sale.refund', 'sale.discount', 'sale.discount.any',
    'price.override', 'stock.adjust', 'stock.disburse', 'grn.receive',
    'shift.open', 'shift.close', 'shift.approve', 'payment.reconcile',
    'report.view', 'device.register',
  ],
  admin: [
    'sale.create', 'sale.void', 'sale.refund', 'sale.discount', 'sale.discount.any',
    'price.override', 'stock.adjust', 'stock.adjust.large', 'stock.disburse', 'grn.receive',
    'shift.open', 'shift.close', 'shift.approve', 'payment.reconcile',
    'report.view', 'user.manage', 'device.register',
  ],
};

/** A cashier may discount unaided up to this share of the line (5%). */
export const CASHIER_MAX_DISCOUNT_BP = 500;

/** Above this quantity, an adjustment needs `stock.adjust.large` + approval. */
export const LARGE_ADJUSTMENT_QTY = 10;

/** A single discount beyond this share of the line always needs a manager. */
export const MANAGER_APPROVAL_DISCOUNT_BP = 2000;

export function capabilitiesFor(role: Role): readonly Capability[] {
  return MATRIX[role];
}

export function can(role: Role, capability: Capability): boolean {
  return MATRIX[role].includes(capability);
}

export function assertCan(role: Role, capability: Capability): void {
  if (!can(role, capability)) {
    throw new PermissionError(`role '${role}' is not permitted to '${capability}'`);
  }
}

export class PermissionError extends Error {
  override name = 'PermissionError';
}

export interface DiscountCheck {
  allowed: boolean;
  /** True when the action is permitted only with a manager approval on record. */
  needsApproval: boolean;
  reason?: string;
}

/**
 * Decide whether a role may apply a discount of `discountBp` basis points on a
 * line. Returns needsApproval rather than throwing so the UI can request a PIN.
 */
export function checkDiscount(role: Role, discountBp: number): DiscountCheck {
  if (discountBp < 0 || discountBp > 10000) {
    return { allowed: false, needsApproval: false, reason: 'discount rate must be between 0% and 100%' };
  }
  if (discountBp === 0) return { allowed: true, needsApproval: false };
  if (can(role, 'sale.discount.any')) return { allowed: true, needsApproval: false };
  if (!can(role, 'sale.discount')) {
    return { allowed: false, needsApproval: false, reason: `role '${role}' cannot discount` };
  }
  if (discountBp > MANAGER_APPROVAL_DISCOUNT_BP) {
    return { allowed: true, needsApproval: true, reason: 'discount exceeds the cashier limit' };
  }
  return { allowed: true, needsApproval: false };
}

export interface AdjustmentCheck {
  allowed: boolean;
  needsApproval: boolean;
  reason?: string;
}

/** Stock adjustments always need a reason code; large ones need approval. */
export function checkAdjustment(role: Role, quantityDelta: number): AdjustmentCheck {
  if (!can(role, 'stock.adjust')) {
    return { allowed: false, needsApproval: false, reason: `role '${role}' cannot adjust stock` };
  }
  const magnitude = Math.abs(quantityDelta);
  if (magnitude > LARGE_ADJUSTMENT_QTY && !can(role, 'stock.adjust.large')) {
    return { allowed: true, needsApproval: true, reason: 'large adjustment requires manager approval' };
  }
  if (magnitude > LARGE_ADJUSTMENT_QTY) {
    return { allowed: true, needsApproval: false, reason: 'admin standing authority' };
  }
  return { allowed: true, needsApproval: false };
}
