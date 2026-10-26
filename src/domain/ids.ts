import { randomUUID } from 'node:crypto';

/**
 * Identity rules for offline-first operation.
 *
 * Every record carries a client-generated UUID and a device_id *before* it ever
 * reaches the server, so a device can create a sale with no network and the
 * server can deduplicate purely by identity. The server never mints an id for
 * an offline-originated row.
 */

export type DeviceId = string & { readonly __brand: 'DeviceId' };
export type Uuid = string & { readonly __brand: 'Uuid' };

export function newUuid(): Uuid {
  return randomUUID() as Uuid;
}

/** A device id is a UUID minted once at device registration and persisted. */
export function newDeviceId(): DeviceId {
  return randomUUID() as DeviceId;
}

export function asDeviceId(value: string): DeviceId {
  if (!isUuid(value)) throw new Error(`not a valid device id: ${value}`);
  return value as DeviceId;
}

export function asUuid(value: string): Uuid {
  if (!isUuid(value)) throw new Error(`not a valid uuid: ${value}`);
  return value as Uuid;
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function isUuid(value: string): boolean {
  return UUID_RE.test(value);
}

/**
 * Deterministic event identity: the same logical operation on the same device
 * always produces the same key, so a retried upload is recognised as a repeat
 * rather than a new movement.
 */
export function eventIdempotencyKey(deviceId: string, kind: string, aggregateId: string): string {
  return `${deviceId}:${kind}:${aggregateId}`;
}
