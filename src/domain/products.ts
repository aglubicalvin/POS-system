import { all, get, nowIso, run, transaction, type Db } from '../db/db.ts';
import { eventIdempotencyKey, newUuid } from './ids.ts';
import { enqueue } from './outbox.ts';

export interface ProductRow {
  product_id: string;
  device_id: string;
  sku: string;
  name: string;
  unit_price: number;
  cost_price: number;
  tax_rate_bp: number;
  low_stock_threshold: number;
  is_active: number;
  version: number;
  created_at: string;
  updated_at: string;
}

export interface CreateProductInput {
  deviceId: string;
  sku: string;
  name: string;
  unitPrice: number;
  costPrice?: number;
  taxRateBp?: number;
  lowStockThreshold?: number;
}

export class ProductError extends Error {
  override name = 'ProductError';
}

/**
 * Catalog edits are sync events too.
 *
 * Producing `product.upserted` from the real create/update writes means every
 * till's catalog converges without a dedicated "admin copied the catalog to
 * eleven devices" ritual: the server relays the full row verbatim to the store's
 * other devices and they upsert it exactly like a pull batch.
 */function emitProductUpsert(db: Db, deviceId: string, row: ProductRow): void {
  // Each CREATE/UPDATE call is a distinct edit, not a retry of an earlier one,
  // so the (device, type, aggregate) key gets a per-call discriminator; the
  // default would collapse a second edit of the same product into the first and
  // silently drop it. The row write and the event enqueue commit together
  // (nested SAVEPOINT), so no edit can lose its event either way.
  enqueue(db, {
    deviceId,
    eventType: 'product.upserted',
    aggregateType: 'product',
    aggregateId: row.product_id,
    version: row.version ?? 1,payload: row as unknown as Record<string, unknown>,
    createdAt: row.updated_at,
    idempotencyKey: `${eventIdempotencyKey(deviceId, 'product.upserted', row.product_id)}:${newUuid()}`,

  });
}


export function createProduct(db: Db, input: CreateProductInput, productId: string = newUuid()): ProductRow {
  if (!input.sku.trim()) throw new ProductError('sku is required');
  if (!Number.isInteger(input.unitPrice) || input.unitPrice < 0) {
    throw new ProductError('unitPrice must be a non-negative integer in minor units');
  }
  if (input.costPrice !== undefined && (!Number.isInteger(input.costPrice) || input.costPrice < 0)) {
    throw new ProductError('costPrice must be a non-negative integer in minor units');
  }
  if (
    input.taxRateBp !== undefined &&
    (!Number.isInteger(input.taxRateBp) || input.taxRateBp < 0 || input.taxRateBp > 100_000)
  ) {
    throw new ProductError('taxRateBp must be between 0 and 10000 basis points');
  }
  return transaction(db, () => {
    const at = nowIso();
    run(
      db,
      `INSERT INTO products
         (product_id, device_id, sku, name, unit_price, cost_price, tax_rate_bp,
          low_stock_threshold, is_active, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)`,
      productId,
      input.deviceId,
      input.sku.trim(),
      input.name,
      input.unitPrice,
      input.costPrice ?? 0,
      input.taxRateBp ?? 0,
      input.lowStockThreshold ?? 0,
      at,
      at,
    );
    const row = getProduct(db, productId)!;
    emitProductUpsert(db, input.deviceId, row);
    return row;
  });
}

export function getProduct(db: Db, productId: string): ProductRow | undefined {
  return get<ProductRow>(db, 'SELECT * FROM products WHERE product_id = ?', productId);
}

export function getProductBySku(db: Db, sku: string): ProductRow | undefined {
  return get<ProductRow>(db, 'SELECT * FROM products WHERE sku = ?', sku);
}

export function listProducts(db: Db, includeInactive = false): ProductRow[] {
  return all<ProductRow>(
    db,
    includeInactive ? 'SELECT * FROM products ORDER BY name' : 'SELECT * FROM products WHERE is_active = 1 ORDER BY name',
  );
}

export function requireActiveProduct(db: Db, productId: string): ProductRow {
  const product = getProduct(db, productId);
  if (!product) throw new ProductError(`product ${productId} not found`);
  if (!product.is_active) throw new ProductError(`product ${product.sku} is not active`);
  return product;
}

export interface UpdateProductInput {
  /** The device performing the edit; the event is emitted under this device. */
  deviceId: string;
  productId: string;
  /** Optional version from the caller's cached product row; compared against the DB version to reject stale edits. */
  version?: number;
  sku?: string;
  name?: string;
  unitPrice?: number;
  costPrice?: number;
  taxRateBp?: number;
  lowStockThreshold?: number;
  isActive?: boolean;
}

/**
 * Edit an existing catalog row and emit `product.upserted` for it. The product's
 * `device_id` (where the row was first created) is kept; the edit event itself is
 * attributed to the editing device, which is the identity the server will see on
 * upload.
 */
export function updateProduct(db: Db, input: UpdateProductInput): ProductRow {
  if (input.unitPrice !== undefined && (!Number.isInteger(input.unitPrice) || input.unitPrice < 0)) {
    throw new ProductError('unitPrice must be a non-negative integer in minor units');
  }
  if (input.costPrice !== undefined && (!Number.isInteger(input.costPrice) || input.costPrice < 0)) {
    throw new ProductError('costPrice must be a non-negative integer in minor units');
  }
  if (
    input.taxRateBp !== undefined &&
    (!Number.isInteger(input.taxRateBp) || input.taxRateBp < 0 || input.taxRateBp > 100_000)
  ) {
    throw new ProductError('taxRateBp must be between 0 and 10000 basis points');
  }
  if (input.lowStockThreshold !== undefined && (!Number.isInteger(input.lowStockThreshold) || input.lowStockThreshold < 0)) {
    throw new ProductError('lowStockThreshold must be a non-negative integer');
  }
  if (input.sku !== undefined && !input.sku.trim()) throw new ProductError('sku is required');

  return transaction(db, () => {
    const existing = getProduct(db, input.productId);
    if (!existing) throw new ProductError(`product ${input.productId} not found`);
    // Stale-edit guard: if the client's cached version != DB version, reject.
    // The UI should always read the latest version before editing; this prevents
    // two managers from overwriting each other's changes.
    if (input.version !== undefined && input.version !== existing.version) {
      throw new ProductError(`stale edit: product version is now ${existing.version}; please reload and resubmit`);
    }
    const at = nowIso();
    // Increment version on every successful edit.
    const nextVersion = (existing.version ?? 0) + 1;
    run(
      db,
      `UPDATE products SET
         sku = ?, name = ?, unit_price = ?, cost_price = ?, tax_rate_bp = ?,
         low_stock_threshold = ?, is_active = ?, version = ?, updated_at = ?
       WHERE product_id = ?`,
      input.sku?.trim() ?? existing.sku,
      input.name ?? existing.name,
      input.unitPrice ?? existing.unit_price,
      input.costPrice ?? existing.cost_price,
      input.taxRateBp ?? existing.tax_rate_bp,
      input.lowStockThreshold ?? existing.low_stock_threshold,
      existing.is_active,
      nextVersion,
      at,
      input.productId,
    );
    const row = getProduct(db, input.productId)!;
    // Include the new version in the emitted row so the outbox reflects it.
    row.version = nextVersion;
    emitProductUpsert(db, input.deviceId, row);
    return row;
  });
}

/** Upsert used by sync when the server pushes catalog changes down to a device. */
export function upsertProduct(db: Db, product: ProductRow): void {
  run(
    db,
    `INSERT INTO products
       (product_id, device_id, sku, name, unit_price, cost_price, tax_rate_bp,
        low_stock_threshold, is_active, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (product_id) DO UPDATE SET
       sku = excluded.sku, name = excluded.name, unit_price = excluded.unit_price,
       cost_price = excluded.cost_price, tax_rate_bp = excluded.tax_rate_bp,
       low_stock_threshold = excluded.low_stock_threshold, is_active = excluded.is_active,
       updated_at = excluded.updated_at`,
    product.product_id,
    product.device_id,
    product.sku,
    product.name,
    product.unit_price,
    product.cost_price,
    product.tax_rate_bp,
    product.low_stock_threshold,
    product.is_active,
    product.created_at,
    product.updated_at,
  );
}
