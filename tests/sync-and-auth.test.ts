import { DatabaseSync } from "node:sqlite";
import * as nodeCrypto from "node:crypto";
import { beforeEach, describe, expect, it } from "vitest";
import { applyBatchOrdered, EventRejected, SYNC_ORDERING_SCHEMA, type SyncEvent } from "../src/sync/ordering.ts";
import { PasswordHasher, type LegacyVerifier } from "../src/auth/password-hasher.ts";

let db: DatabaseSync;
let calls: string[];

const ev = (deviceId: string, seq: number, extra: Record<string, unknown> = {}): SyncEvent => ({ deviceId, seq, ...extra });

// Stand-in for your real applyEvent: writes a row, can reject, can fail transiently.
let failOnce: string | null;
function applyEvent(d: DatabaseSync, e: SyncEvent): void {
  d.prepare("INSERT INTO applied (device_id, seq) VALUES (?, ?)").run(e.deviceId, e.seq);
  calls.push(`${e.deviceId}:${e.seq}`);
  if (e.bad) throw new EventRejected("bad sku");
  if (failOnce === `${e.deviceId}:${e.seq}`) {
    failOnce = null;
    throw new Error("transient");
  }
}
const appliedRows = (): string[] =>
  (db.prepare("SELECT device_id, seq FROM applied ORDER BY rowid").all() as { device_id: string; seq: number }[]).map(
    (r) => `${r.device_id}:${r.seq}`,
  );

beforeEach(() => {
  db = new DatabaseSync(":memory:");
  db.exec(SYNC_ORDERING_SCHEMA);
  db.exec("CREATE TABLE applied (device_id TEXT, seq INTEGER)");
  calls = [];
  failOnce = null;
});

describe("applyBatchOrdered", () => {
  it("applies in order even when the batch is shuffled", () => {
    const [r] = applyBatchOrdered(db, [ev("A", 3), ev("A", 1), ev("A", 2)], applyEvent);
    expect(appliedRows()).toEqual(["A:1", "A:2", "A:3"]);
    expect(r).toMatchObject({ ackedThrough: 3, applied: 3, buffered: 0 });
    expect(r!.needFrom).toBeUndefined();
  });

  it("skips duplicates on resend", () => {
    applyBatchOrdered(db, [ev("A", 1), ev("A", 2)], applyEvent);
    const [r] = applyBatchOrdered(db, [ev("A", 1), ev("A", 2), ev("A", 3)], applyEvent);
    expect(appliedRows()).toEqual(["A:1", "A:2", "A:3"]);
    expect(r).toMatchObject({ duplicates: 2, applied: 1, ackedThrough: 3 });
  });

  it("buffers a gap, reports needFrom, and drains when the gap fills", () => {
    const [r1] = applyBatchOrdered(db, [ev("A", 1), ev("A", 3), ev("A", 4)], applyEvent);
    expect(appliedRows()).toEqual(["A:1"]);
    expect(r1).toMatchObject({ ackedThrough: 1, buffered: 2, needFrom: 2 });

    const [r2] = applyBatchOrdered(db, [ev("A", 2)], applyEvent);
    expect(appliedRows()).toEqual(["A:1", "A:2", "A:3", "A:4"]);
    expect(r2).toMatchObject({ ackedThrough: 4, applied: 3 });
    expect(r2!.needFrom).toBeUndefined();
    expect(db.prepare("SELECT COUNT(*) AS n FROM pending_events").get()).toEqual({ n: 0 });
  });

  it("a gap on one device does not block another", () => {
    const res = applyBatchOrdered(db, [ev("A", 2), ev("B", 1), ev("B", 2)], applyEvent);
    expect(appliedRows()).toEqual(["B:1", "B:2"]);
    expect(res.find((x) => x.deviceId === "A")).toMatchObject({ ackedThrough: 0, needFrom: 1 });
    expect(res.find((x) => x.deviceId === "B")).toMatchObject({ ackedThrough: 2 });
  });

  it("logs a rejected event, rolls back its partial writes, and moves on", () => {
    const [r] = applyBatchOrdered(db, [ev("A", 1), ev("A", 2, { bad: true }), ev("A", 3)], applyEvent);
    expect(appliedRows()).toEqual(["A:1", "A:3"]); // A:2's insert was rolled back
    expect(r).toMatchObject({ applied: 2, rejected: 1, ackedThrough: 3 });
    expect(db.prepare("SELECT reason FROM rejected_events WHERE seq = 2").get()).toEqual({ reason: "bad sku" });
  });

  it("a transient error rolls the event back and a retry succeeds", () => {
    failOnce = "A:2";
    expect(() => applyBatchOrdered(db, [ev("A", 1), ev("A", 2), ev("A", 3)], applyEvent)).toThrow("transient");
    expect(appliedRows()).toEqual(["A:1"]);
    const [r] = applyBatchOrdered(db, [ev("A", 1), ev("A", 2), ev("A", 3)], applyEvent);
    expect(appliedRows()).toEqual(["A:1", "A:2", "A:3"]);
    expect(r).toMatchObject({ duplicates: 1, applied: 2, ackedThrough: 3 });
  });

  it("caps the pending buffer", () => {
    const [r] = applyBatchOrdered(db, [ev("A", 5), ev("A", 6), ev("A", 7)], applyEvent, { maxPending: 2 });
    expect(r).toMatchObject({ buffered: 2, dropped: 1, needFrom: 1 });
  });

  it("verifyChain stops the device at a broken link and applies nothing past it", () => {
    const [r] = applyBatchOrdered(
      db,
      [
        ev("A", 1, { hash: "h1", prevHash: null }),
        ev("A", 2, { hash: "h2", prevHash: "h1" }),
        ev("A", 3, { hash: "h3", prevHash: "WRONG" }),
        ev("A", 4, { hash: "h4", prevHash: "h3" }),
      ],
      applyEvent,
      { verifyChain: true },
    );
    expect(appliedRows()).toEqual(["A:1", "A:2"]);
    expect(r).toMatchObject({ error: "chain_broken", ackedThrough: 2 });
  });

  it("rejects malformed events before applying anything", () => {
    expect(() => applyBatchOrdered(db, [ev("A", 1), ev("A", 0)], applyEvent)).toThrow(TypeError);
    expect(appliedRows()).toEqual([]);
  });
});

// A stand-in for "your existing scrypt format".
const legacy: LegacyVerifier = {
  matches: (s) => s.startsWith("scrypt$"),
  verify: (pw, s) => {
    const [, salt, hash] = s.split("$");
    const got = nodeCrypto.scryptSync(pw, Buffer.from(salt!, "hex"), 32);
    return nodeCrypto.timingSafeEqual(got, Buffer.from(hash!, "hex"));
  },
};
const makeLegacy = (pw: string): string => {
  const salt = nodeCrypto.randomBytes(16);
  return `scrypt$${salt.toString("hex")}$${nodeCrypto.scryptSync(pw, salt, 32).toString("hex")}`;
};

const hasArgon2 = typeof (nodeCrypto as unknown as { argon2Sync?: unknown }).argon2Sync === "function";

describe("PasswordHasher", () => {
  const hasher = new PasswordHasher({ legacy: [legacy] });

  it("verifies legacy hashes, flags them for rehash, and rejects wrong passwords", () => {
    const stored = makeLegacy("hunter2");
    expect(hasher.verify("hunter2", stored)).toBe(true);
    expect(hasher.verify("wrong", stored)).toBe(false);
    expect(hasher.needsRehash(stored)).toBe(true);
  });

  it("rejects unknown formats", () => {
    expect(hasher.verify("x", "plaintext-or-garbage")).toBe(false);
  });

  it.skipIf(!hasArgon2)("argon2id: hash, verify, wrong password, unique salts", () => {
    const h1 = hasher.hash("hunter2");
    expect(h1.startsWith("$argon2id$v=19$m=19456,t=2,p=1$")).toBe(true);
    expect(hasher.verify("hunter2", h1)).toBe(true);
    expect(hasher.verify("hunter3", h1)).toBe(false);
    expect(hasher.hash("hunter2")).not.toBe(h1);
    expect(hasher.needsRehash(h1)).toBe(false);
  });

  it.skipIf(!hasArgon2)("argon2id: weaker stored parameters trigger a rehash", () => {
    const weak = new PasswordHasher({ params: { memoryKiB: 8192, passes: 1, parallelism: 1, tagLength: 32 } }).hash("pw");
    expect(hasher.needsRehash(weak)).toBe(true);
    expect(hasher.verify("pw", weak)).toBe(true); // parameters are read from the hash itself
  });

  it.skipIf(hasArgon2)("without argon2 support, hashing fails loudly instead of silently downgrading", () => {
    expect(() => hasher.hash("pw")).toThrow(/argon2Sync is not available/);
  });
});
