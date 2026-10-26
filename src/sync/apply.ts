import { all, nowIso, type Db } from '../db/db.ts';
import { upsertProduct, type ProductRow } from '../domain/products.ts';
import { upsertUser, type UserRow } from '../domain/users.ts';
import {
  applyBatchOrdered,
  EventRejected,
  type ApplyEvent,
  type DeviceResult,
  type OrderedOptions,
  type SyncEvent,
} from './ordering.ts';

/**
 * Device-side inbound sync.
 *
 * `client.ts` drains the outbox UP (device → server); this module is the DOWN
 * half (server → device). The server relays events raised on other tills — a
 * manager re-pricing the catalog on TILL-1 must reach TILL-2 — and each device
 * applies them to its local SQLite store through `applyBatchOrdered`, so event
 * order is strict per source device, duplicates are skipped, a gap is buffered
 * instead of applied, and a permanently bad event is logged in
 * `rejected_events` (with its full payload, so an operator can replay it) while
 * the stream keeps moving.
 *
 * The transport is injected exactly like `SyncTransport` in client.ts, running
 * under our device token — the server knows who is asking, so it must not
 * deliver our own events back; a defensive filter in `pullAndApply` skips them
 * regardless. The real pull endpoint (`GET /sync/pull` on the Kotlin server)
 * does not exist yet, so tests supply a scripted transport; a device later
 * swaps in the real one without changing this code.
 *
 * The server→device payload contract for `product.upserted` is the JSON of a
 * full product row (the same snake_case fields `products` stores); there is no
 * cross-language contract fixture for the pull direction yet, which is the one
 * gap to close before the real endpoint ships.
 */

export interface SyncPullTransport {
  /**
   * Fetch events newer than the per-source resume points given here
   * (`resumeSeqs(db)`), newest first is fine but order does not matter.
   * Return an empty array when there is nothing to do.
   */
  pull(since: Record<string, number>): Promise<SyncEvent[]>;
}

export interface PullResult {
  fetched: number;
  applied: number;
  duplicates: number;
  buffered: number;
  rejected: number;
  dropped: number;
  /** Per device with a gap still open: resume from this seq. */
  needFrom: Record<string, number>;
  /** Devices that stopped because their hash chain broke; investigate before re-pulling. */
  chainBroken: string[];
  results: DeviceResult[];
}

/** Last applied seq per source device, as the cumulative applier has it. */
export function resumeSeqs(db: Db): Record<string, number> {
  const rows = all<{ device_id: string; last_applied_seq: number }>(
    db,
    'SELECT device_id, last_applied_seq FROM device_sync_state',
  );
  return Object.fromEntries(rows.map((row) => [row.device_id, Number(row.last_applied_seq)]));
}

function reject(reason: string): never {
  throw new EventRejected(reason);
}

const USER_ROLES = ['cashier', 'manager', 'admin'] as const;

/**
 * A relayed user must be store-GLOBAL: no device binding. This till does not
 * know the sending till's device, and a cashier PIN is only ever valid on the
 * device it was enrolled on — so a bound row would be both unjoinable (FK) and
 * unusable. Secrets must arrive already hashed; a plaintext secret is refused.
 */
function userPayload(raw: unknown): Omit<UserRow, 'device_id'> & { device_id: null } {
  if (typeof raw !== 'object' || raw === null) reject('user.upserted payload is not an object');
  const p = raw as Record<string, unknown>;
  if (typeof p.user_id !== 'string' || p.user_id === '') reject('user.upserted payload is missing user_id');
  if (typeof p.full_name !== 'string' || p.full_name === '') reject('user.upserted payload is missing full_name');
  if (typeof p.role !== 'string' || !(USER_ROLES as readonly string[]).includes(p.role)) {
    reject(`user.upserted payload has an invalid role: ${String(p.role)}`);
  }
  if (p.device_id !== null && p.device_id !== undefined && p.device_id !== '') {
    reject('user.upserted payload binds the user to a foreign device; relayed users must be store-global');
  }
  const secret = (value: unknown, field: string): string | null => {
    if (value === null || value === undefined) return null;
    if (typeof value !== 'string' || !/^(scrypt|argon2)/.test(value)) {
      reject(`user.upserted payload does not carry a hashed ${field}`);
    }
    return value as string;
  };
  if (!Number.isInteger(p.mfa_enabled) && p.mfa_enabled !== true && p.mfa_enabled !== false) {
    reject('user.upserted payload has an invalid mfa_enabled');
  }
  const timestamp = (value: unknown): string | null =>
    typeof value === 'string' && value !== '' ? value : null;
  return {
    user_id: p.user_id as string,
    device_id: null,
    full_name: p.full_name as string,
    role: p.role as UserRow['role'],
    pin_hash: secret(p.pin_hash, 'pin_hash'),
    password_hash: secret(p.password_hash, 'password_hash'),
    mfa_enabled: p.mfa_enabled === 1 || p.mfa_enabled === true ? 1 : 0,
    failed_attempts: nonNegativeInt(p.failed_attempts, 0, 'failed_attempts'),
    locked_until: timestamp(p.locked_until),
    is_active: p.is_active === 0 || p.is_active === false ? 0 : 1,
    created_at: timestamp(p.created_at) ?? nowIso(),
    updated_at: timestamp(p.updated_at) ?? nowIso(),
  };
}

function nonNegativeInt(value: unknown, fallback: number, field: string): number {
  if (value === undefined || value === null) return fallback;
  if (!Number.isInteger(value) || (value as number) < 0) reject(`payload has an invalid ${field}`);
  return value as number;
}

/** The server relays product events as a full row; validate before upserting. */
function productPayload(raw: unknown): ProductRow {
  if (typeof raw !== 'object' || raw === null) reject('product.upserted payload is not an object');
  const p = raw as Record<string, unknown>;
  if (typeof p.product_id !== 'string' || p.product_id === '') {
    reject('product.upserted payload is missing product_id');
  }
  if (typeof p.sku !== 'string' || p.sku.trim() === '') reject('product.upserted payload is missing sku');
  if (!Number.isInteger(p.unit_price) || (p.unit_price as number) < 0) {
    reject('product.upserted payload has an invalid unit_price');
  }
  const timestamp = (value: unknown): string => (typeof value === 'string' && value !== '' ? value : nowIso());
  return {
    product_id: p.product_id as string,
    device_id: typeof p.device_id === 'string' ? p.device_id : '',
    sku: p.sku as string,
    name: typeof p.name === 'string' && p.name !== '' ? p.name : (p.sku as string),
    unit_price: p.unit_price as number,
    cost_price: nonNegativeInt(p.cost_price, 0, 'cost_price'),
    tax_rate_bp: nonNegativeInt(p.tax_rate_bp, 0, 'tax_rate_bp'),
    low_stock_threshold: nonNegativeInt(p.low_stock_threshold, 0, 'low_stock_threshold'),
    is_active: p.is_active === 0 || p.is_active === false ? 0 : 1,
    created_at: timestamp(p.created_at),
    updated_at: timestamp(p.updated_at),
    version: typeof p.version === 'number' ? p.version : 1,
  };
}

/**
 * The dispatch from a wire event to the local write. Unknown types are
 * `EventRejected`, not a crash: the applier logs them (seq advances) and the
 * stream keeps moving, so new event types cannot wedge a whole pull.
 */
const applyInboundEvent: ApplyEvent = (db, event) => {
  const { eventType, payload } = event as SyncEvent & { eventType?: unknown; payload?: unknown };
  switch (eventType) {
    case 'product.upserted':
      upsertProduct(db, productPayload(payload));
      return;
    case 'user.upserted':
      upsertUser(db, userPayload(payload));
      return;
    default:
      reject(`unsupported inbound event type: ${String(eventType)}`);
  }
};

/**
 * Pull one batch from the server and apply it, ordered per source device.
 * Events that originated on `ownDeviceId` are skipped before the applier sees
 * them: their effects are already local, and a relayed sale of ours must not be
 * applied twice. Idempotent as a whole — if the transport re-sends events that
 * are already in `device_sync_state` they count as duplicates and nothing is
 * re-executed.
 */
export async function pullAndApply(
  db: Db,
  ownDeviceId: string,
  transport: SyncPullTransport,
  opts: OrderedOptions = {},
): Promise<PullResult> {
  const fetched = await transport.pull(resumeSeqs(db));
  const events = fetched.filter((event) => event.deviceId !== ownDeviceId);
  const results = applyBatchOrdered(db, events, applyInboundEvent, opts);

  const result: PullResult = {
    fetched: fetched.length,
    applied: 0,
    duplicates: 0,
    buffered: 0,
    rejected: 0,
    dropped: 0,
    needFrom: {},
    chainBroken: [],
    results,
  };
  for (const perDevice of results) {
    result.applied += perDevice.applied;
    result.duplicates += perDevice.duplicates;
    result.buffered += perDevice.buffered;
    result.rejected += perDevice.rejected;
    result.dropped += perDevice.dropped;
    if (perDevice.needFrom !== undefined) result.needFrom[perDevice.deviceId] = perDevice.needFrom;
    if (perDevice.error === 'chain_broken') result.chainBroken.push(perDevice.deviceId);
  }
  return result;
}
