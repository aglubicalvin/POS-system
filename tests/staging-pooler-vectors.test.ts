import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { resolveStagingPoolerHost } from '../src/config/staging-target.ts';

/**
 * Cross-language hostname validation vectors.
 *
 * The table below lives in the repository so that the TypeScript reference
 * implementation, the Kotlin module, the PowerShell script and the real
 * ProbeState binary are all asserted against the SAME inputs. A grammar change
 * in one runtime therefore fails that runtime's suite rather than drifting
 * silently until a workflow runs.
 */
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const VECTOR_FILE = resolve(repoRoot, 'server/tools/staging-pooler-host-vectors.txt');

export interface HostVector {
  /** True when the value must be accepted by every implementation. */
  accept: boolean;
  /** The raw configured value, whitespace preserved. */
  value: string;
  /** 1-based line number in the vector file, for failure messages. */
  line: number;
}

export function loadHostVectors(file = VECTOR_FILE): HostVector[] {
  return readFileSync(file, 'utf8')
    .split(/\r?\n/)
    .map((text, index) => ({ text, line: index + 1 }))
    .filter(({ text }) => text.trim() !== '' && !text.startsWith('#'))
    .map(({ text, line }) => {
      const separator = text.indexOf('\t');
      const decision = (separator < 0 ? text : text.slice(0, separator)).trim();
      const value = separator < 0 ? '' : text.slice(separator + 1);
      if (decision !== 'accept' && decision !== 'reject') {
        throw new Error(`vector line ${line}: unknown decision "${decision}"`);
      }
      return { accept: decision === 'accept', value, line };
    });
}

const vectors = loadHostVectors();

describe('shared staging pooler hostname vectors', () => {
  it('loads a meaningful number of vectors in both directions', () => {
    expect(vectors.length).toBeGreaterThanOrEqual(25);
    expect(vectors.filter((v) => v.accept).length).toBeGreaterThanOrEqual(3);
    expect(vectors.filter((v) => !v.accept).length).toBeGreaterThanOrEqual(20);
  });

  it.each(vectors.map((v) => [`${v.accept ? 'accepts' : 'rejects'} ${JSON.stringify(v.value)}`, v] as const))(
    'TypeScript %s',
    (_label, vector) => {
      const result = resolveStagingPoolerHost({ POS_STAGING_POOLER_HOST: vector.value });
      expect(result.ok, `vector line ${vector.line}: ${JSON.stringify(vector.value)}`).toBe(
        vector.accept,
      );
      if (result.ok) {
        // An accepted value is normalised to the canonical lower-case hostname:
        // trimming and lower-casing happen before matching, in every runtime.
        expect(result.host.n).toBe(result.host.n.toLowerCase());
        expect(result.host.region).toBe(result.host.region.toLowerCase());
      } else {
        expect(result.code).toBe(
          vector.value.trim() === '' ? 'missing-staging-pooler-host' : 'malformed-staging-pooler-host',
        );
      }
    },
  );

  it('never accepts a value that contains a scheme, port, path or query', () => {
    for (const vector of vectors.filter((v) => v.accept)) {
      expect(vector.value.trim()).not.toMatch(/[/:@?\\\s]/);
    }
  });
});
