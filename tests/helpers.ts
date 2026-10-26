import { openDb, type Db } from '../src/db/db.ts';
import { registerDevice } from '../src/domain/devices.ts';
import { createProduct, type ProductRow } from '../src/domain/products.ts';
import { createSupplier, type SupplierRow } from '../src/domain/inventory.ts';
import { createUser, type UserRow } from '../src/domain/users.ts';
import type { ApplyResult, SyncEvent } from '../src/sync/client.ts';
import type { SyncPullTransport } from '../src/sync/apply.ts';
import type { SyncEvent as InboundSyncEvent } from '../src/sync/ordering.ts';

export const STORE_ID = 'store-accra-01';

export interface ProductSpec {
  productId?: string;
  sku: string;
  name: string;
  unitPrice: number;
  costPrice?: number;
  taxRateBp?: number;
  lowStockThreshold?: number;
}

export interface SeededStore {
  db: Db;
  deviceId: string;
  cashier: UserRow;
  manager: UserRow;
  admin: UserRow;
  supplier: SupplierRow;
  products: Record<string, ProductRow>;
}

export interface SeedOptions {
  deviceName?: string;
  cashierBoundToDevice?: boolean;
  catalog?: ProductSpec[];
}

export const DEFAULT_PRODUCTS: ProductSpec[] = [
  { sku: 'WATER-500', name: 'Sachet Water 500ml', unitPrice: 150, taxRateBp: 0, lowStockThreshold: 20 },
  { sku: 'SOAP-BAR', name: 'Key Soap Bar', unitPrice: 800, taxRateBp: 1250, lowStockThreshold: 5 },
  { sku: 'MILO-400', name: 'Milo Refill 400g', unitPrice: 4500, taxRateBp: 1250, lowStockThreshold: 3 },
];

/** Build an in-memory device with users, a supplier and a catalog. */
export function seedStore(options: SeedOptions = {}): SeededStore {
  const db = openDb(':memory:');
  const { device } = registerDevice(db, {
    storeId: STORE_ID,
    name: options.deviceName ?? 'TILL-1',
    model: 'Android-Tablet',
  });

  const cashier = createUser(db, {
    deviceId: options.cashierBoundToDevice === false ? null : device.device_id,
    fullName: 'Akosua Mensah',
    role: 'cashier',
    pin: '1234',
  });
  const manager = createUser(db, { fullName: 'Kwame Boateng', role: 'manager', password: 'manager-secret' });
  const admin = createUser(db, { fullName: 'Nana Owusu', role: 'admin', password: 'admin-secret', mfaEnabled: true });

  const supplier = createSupplier(db, { deviceId: device.device_id, name: 'Accra Wholesale Ltd', phone: '0244000000' });

  const products: Record<string, ProductRow> = {};
  for (const spec of options.catalog ?? DEFAULT_PRODUCTS) {
    products[spec.sku] = createProduct(
      db,
      {
        deviceId: device.device_id,
        sku: spec.sku,
        name: spec.name,
        unitPrice: spec.unitPrice,
        costPrice: spec.costPrice ?? Math.round(spec.unitPrice * 0.6),
        taxRateBp: spec.taxRateBp ?? 0,
        lowStockThreshold: spec.lowStockThreshold ?? 0,
      },
      spec.productId,
    );
  }

  // The seeding calls are setup, not till activity: the user/product upsert
  // events they produced would otherwise flood every outbox assertion in the
  // suites. Tests that need producer events create them AFTER seeding.
  db.exec('DELETE FROM outbox');

  return { db, deviceId: device.device_id, cashier, manager, admin, supplier, products };
}

/**
 * A transport that records what the client uploaded and acks it.
 *
 * The real apply logic lives in the Kotlin server, so the TypeScript suite tests
 * only what belongs to the CLIENT: sequencing, ack/fail bookkeeping, and retry.
 * Server behaviour is asserted in `server/src/test/.../EventApplierTest.kt`
 * against real PostgreSQL.
 */
export function recordingTransport(options: { failWith?: Error } = {}) {
  const received: SyncEvent[] = [];
  return {
    received,
    transport: {
      async send(events: SyncEvent[]): Promise<ApplyResult[]> {
        if (options.failWith) throw options.failWith;
        received.push(...events);
        return events.map((event) => ({ eventId: event.eventId, status: 'applied' as const }));
      },
    },
  };
}

/**
 * A transport that replays pre-scripted batches of server→device events, one
 * per `pull` call, then is empty. Records the resume points each call was
 * given, so a test can assert the cursor bookkeeping too.
 */
export function scriptedPullTransport(batches: InboundSyncEvent[][]): {
  transport: SyncPullTransport;
  seenSince: Record<string, number>[];
} {
  const seenSince: Record<string, number>[] = [];
  let call = 0;
  return {
    seenSince,
    transport: {
      async pull(since: Record<string, number>): Promise<InboundSyncEvent[]> {
        seenSince.push(since);
        const next = batches[call];
        call += 1;
        return next ? [...next] : [];
      },
    },
  };
}
