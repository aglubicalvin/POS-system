import * as nodeCrypto from "node:crypto";

// node:crypto gained argon2 in Node v24.7.0. Feature-detected so this file also loads on older Node;
// on Node >= 24.7 you can replace this with: import { argon2Sync } from "node:crypto";
type Argon2Fn = (
  algorithm: "argon2id",
  parameters: { message: string; nonce: Uint8Array; parallelism: number; tagLength: number; memory: number; passes: number },
) => Uint8Array;
const argon2Sync = (nodeCrypto as unknown as { argon2Sync?: Argon2Fn }).argon2Sync;

export interface ArgonParams {
  memoryKiB: number;
  passes: number;
  parallelism: number;
  tagLength: number;
}

/** An OWASP-listed combination (19 MiB, 2 passes, 1 lane). Tune on your slowest target device. */
export const DEFAULT_PARAMS: ArgonParams = { memoryKiB: 19456, passes: 2, parallelism: 1, tagLength: 32 };

/** Lets existing hashes (your current scrypt format) keep working until each user logs in again. */
export interface LegacyVerifier {
  matches(stored: string): boolean;
  verify(password: string, stored: string): boolean;
}

const PHC = /^\$argon2id\$v=19\$m=(\d+),t=(\d+),p=(\d+)\$([A-Za-z0-9+/]+)\$([A-Za-z0-9+/]+)$/;

const b64 = (buf: Uint8Array): string => Buffer.from(buf).toString("base64").replace(/=+$/, "");

export class PasswordHasher {
  private params: ArgonParams;
  private legacy: LegacyVerifier[];

  constructor(opts: { params?: ArgonParams; legacy?: LegacyVerifier[] } = {}) {
    this.params = opts.params ?? DEFAULT_PARAMS;
    this.legacy = opts.legacy ?? [];
  }

  private derive(password: string, salt: Uint8Array, p: ArgonParams): Uint8Array {
    if (!argon2Sync) throw new Error("crypto.argon2Sync is not available in this Node version (needs >= 24.7.0)");
    return argon2Sync("argon2id", {
      message: password,
      nonce: salt,
      parallelism: p.parallelism,
      tagLength: p.tagLength,
      memory: p.memoryKiB,
      passes: p.passes,
    });
  }

  /** Returns a PHC string: $argon2id$v=19$m=...,t=...,p=...$salt$hash (parameters travel with the hash). */
  hash(password: string): string {
    const salt = nodeCrypto.randomBytes(16);
    const tag = this.derive(password, salt, this.params);
    const { memoryKiB: m, passes: t, parallelism: p } = this.params;
    return `$argon2id$v=19$m=${m},t=${t},p=${p}$${b64(salt)}$${b64(tag)}`;
  }

  verify(password: string, stored: string): boolean {
    const match = PHC.exec(stored);
    if (match) {
      const params: ArgonParams = {
        memoryKiB: Number(match[1]),
        passes: Number(match[2]),
        parallelism: Number(match[3]),
        tagLength: Buffer.from(match[5]!, "base64").length,
      };
      const expected = Buffer.from(match[5]!, "base64");
      const actual = Buffer.from(this.derive(password, Buffer.from(match[4]!, "base64"), params));
      return actual.length === expected.length && nodeCrypto.timingSafeEqual(actual, expected);
    }
    const legacy = this.legacy.find((l) => l.matches(stored));
    return legacy ? legacy.verify(password, stored) : false;
  }

  /** True for legacy hashes and for argon2id hashes made with weaker parameters than the current ones. */
  needsRehash(stored: string): boolean {
    const match = PHC.exec(stored);
    if (!match) return true;
    return (
      Number(match[1]) < this.params.memoryKiB ||
      Number(match[2]) < this.params.passes ||
      Number(match[3]) !== this.params.parallelism
    );
  }
}
