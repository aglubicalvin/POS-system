import { get, nowIso, run, transaction, type Db } from '../db/db.ts';
import { appendAudit } from './audit.ts';
import { assertApproval } from './approvals.ts';
import { newUuid } from './ids.ts';
import { sum, type Minor } from './money.ts';
import { enqueue } from './outbox.ts';
import { checkAdjustment, PermissionError, type Role } from './permissions.ts';
import { requireActiveProduct } from './products.ts';
import { recordMovement, type ReasonCode } from './stock.ts';
import { requireUser } from './users.ts';

/**
 * Inventory in and out.
 *
 * Goods received is the supplier-facing inflow. Disbursements and adjustments
 * are human-caused outflows/inflows and MUST carry a reason code and free-text
 * reason; adjustments larger than the threshold require a manager approval.
 */

export class InventoryError extends Error {
  override name = 'InventoryError';
}

export interface SupplierRow {
  supplier_id: string;
  device_id: string;
  name: string;
  phone: string | null;
  email: string | null;
  is_active: number;
  created_at: string;
  updated_at: string;
}

export function createSupplier(
  db: Db,
  input: { deviceId: string; name: string; phone?: string; email?: string },
  supplierId: string = newUuid(),
): SupplierRow {
  return transaction(db, () => {
    const at = nowIso();
    run(
      db,
      `INSERT INTO suppliers (supplier_id, device_id, name, phone, email, is_active, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, 1, ?, ?)`,
      supplierId,
      input.deviceId,
      input.name,
      input.phone ?? null,
      input.email ?? null,
      at,
      at,
    );
    return get<SupplierRow>(db, 'SELECT * FROM suppliers WHERE supplier_id = ?', supplierId)!;
  });
}

export interface GoodsReceivedLine {
  productId: string;
  quantity: number;
  unitCost: number;
}

export interface GoodsReceivedInput {
  deviceId: string;
  supplierId: string;
  receivedBy: string;
  reference?: string;
  receivedAt?: string;
  lines: GoodsReceivedLine[];
}

export interface GoodsReceivedResult {
  grnId: string;
  totalCost: Minor;
}

/** Record a delivery: header, lines, inbound movements, audit, outbox — atomically. */
export function receiveGoods(db: Db, input: GoodsReceivedInput): GoodsReceivedResult {
  if (input.lines.length === 0) throw new InventoryError('a delivery must contain at least one line');
  return transaction(db, () => {
    requireUser(db, input.receivedBy);
    const supplier = get<SupplierRow>(db, 'SELECT * FROM suppliers WHERE supplier_id = ?', input.supplierId);
    if (!supplier) throw new InventoryError(`supplier ${input.supplierId} not found`);

    const at = input.receivedAt ?? nowIso();
    const grnId = newUuid();

    const lines = input.lines.map((line) => {
      const product = requireActiveProduct(db, line.productId);
      if (!Number.isInteger(line.quantity) || line.quantity <= 0) {
        throw new InventoryError(`invalid received quantity for ${product.sku}`);
      }
      if (!Number.isInteger(line.unitCost) || line.unitCost < 0) {
        throw new InventoryError(`invalid unit cost for ${product.sku}`);
      }
      return { product, ...line, lineCost: line.unitCost * line.quantity };
    });
    const totalCost = sum(lines.map((l) => l.lineCost));

    run(
      db,
      `INSERT INTO goods_received
         (grn_id, device_id, supplier_id, reference, total_cost, status, received_by, received_at, created_at)
       VALUES (?, ?, ?, ?, ?, 'received', ?, ?, ?)`,
      grnId,
      input.deviceId,
      input.supplierId,
      input.reference ?? null,
      totalCost,
      input.receivedBy,
      at,
      at,
    );

    for (const line of lines) {
      run(
        db,
        `INSERT INTO goods_received_items (item_id, grn_id, product_id, quantity, unit_cost)
         VALUES (?, ?, ?, ?, ?)`,
        newUuid(),
        grnId,
        line.product.product_id,
        line.quantity,
        line.unitCost,
      );
      recordMovement(db, {
        deviceId: input.deviceId,
        productId: line.product.product_id,
        quantityDelta: line.quantity,
        reasonCode: 'goods_received',
        refType: 'goods_received',
        refId: grnId,
        userId: input.receivedBy,
        occurredAt: at,
      });
    }

    appendAudit(db, {
      deviceId: input.deviceId,
      actorId: input.receivedBy,
      action: 'goods_received.created',
      entityType: 'goods_received',
      entityId: grnId,
      occurredAt: at,
      details: { supplierId: input.supplierId, totalCost, lines: lines.length, reference: input.reference ?? null },
    });

    enqueue(db, {
      deviceId: input.deviceId,
      eventType: 'goods_received.created',
      aggregateType: 'goods_received',
      aggregateId: grnId,
      createdAt: at,
      payload: {
        grnId,
        supplierId: input.supplierId,
        receivedBy: input.receivedBy,
        receivedAt: at,
        reference: input.reference ?? null,
        totalCost,
        lines: lines.map((l) => ({
          productId: l.product.product_id,
          quantity: l.quantity,
          unitCost: l.unitCost,
        })),
      },
    });

    return { grnId, totalCost };
  });
}

export interface ManualMovementInput {
  deviceId: string;
  productId: string;
  /** Positive to add stock, negative to remove it. */
  quantityDelta: number;
  userId: string;
  reasonCode: ReasonCode;
  reason: string;
  approvalId?: string;
  at?: string;
}

const MANUAL_CODES: readonly ReasonCode[] = [
  'adjustment',
  'disbursement',
  'transfer_in',
  'transfer_out',
  'stocktake',
  'refund',
  'void',
];

/**
 * Disbursement / adjustment. Validates the reason code, enforces the approval
 * rule for large magnitudes, then writes the movement, audit row and outbox
 * event together.
 */
export function recordManualMovement(db: Db, input: ManualMovementInput): { movementId: string; resultingQty: number } {
  if (!input.reason.trim()) throw new InventoryError('a reason code alone is not enough; a written reason is required');
  if (!MANUAL_CODES.includes(input.reasonCode)) {
    throw new InventoryError(`reason code '${input.reasonCode}' is not a manual movement code`);
  }

  return transaction(db, () => {
    const user = requireUser(db, input.userId);
    const role: Role = user.role;
    requireActiveProduct(db, input.productId);

    const decision = checkAdjustment(role, input.quantityDelta);
    if (!decision.allowed) throw new PermissionError(decision.reason ?? 'adjustment not allowed');
    if (decision.needsApproval) {
      assertApproval(db, input.approvalId, 'stock.adjust.large', input.deviceId);
    }

    const at = input.at ?? nowIso();
    const movement = recordMovement(db, {
      deviceId: input.deviceId,
      productId: input.productId,
      quantityDelta: input.quantityDelta,
      reasonCode: input.reasonCode,
      refType: 'manual',
      refId: null,
      userId: input.userId,
      approvalId: input.approvalId ?? null,
      occurredAt: at,
    });

    appendAudit(db, {
      deviceId: input.deviceId,
      actorId: input.userId,
      action: `stock.${input.reasonCode}`,
      entityType: 'stock_movement',
      entityId: movement.movement_id,
      occurredAt: at,
      details: {
        productId: input.productId,
        quantityDelta: input.quantityDelta,
        reason: input.reason,
        approvalId: input.approvalId ?? null,
      },
    });

    enqueue(db, {
      deviceId: input.deviceId,
      eventType: 'stock.movement',
      aggregateType: 'stock_movement',
      aggregateId: movement.movement_id,
      createdAt: at,
      payload: {
        movementId: movement.movement_id,
        productId: input.productId,
        quantityDelta: input.quantityDelta,
        reasonCode: input.reasonCode,
        reason: input.reason,
        userId: input.userId,
        approvalId: input.approvalId ?? null,
        occurredAt: at,
      },
    });

    const resulting = get<{ quantity: number }>(
      db,
      'SELECT quantity FROM stock_on_hand WHERE product_id = ?',
      input.productId,
    )?.quantity ?? 0;
    return { movementId: movement.movement_id, resultingQty: resulting };
  });
}

/** Convenience wrapper for the common "disburse N units" case. */
export function disburseStock(
  db: Db,
  input: Omit<ManualMovementInput, 'quantityDelta' | 'reasonCode'> & { quantity: number },
): { movementId: string; resultingQty: number } {
  if (input.quantity <= 0) throw new InventoryError('disbursement quantity must be positive');
  return recordManualMovement(db, {
    ...input,
    quantityDelta: -input.quantity,
    reasonCode: 'disbursement',
  });
}
