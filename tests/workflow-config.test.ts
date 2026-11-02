import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * Workflow configuration guard.
 *
 * These assertions protect the separation between the development deployment
 * environment and the staging one, and the invariants that make the probe safe
 * (manual-only, dry-run by default, no hard-coded project references, no
 * credentials on the command line). They read the workflow files as text — no
 * workflow is dispatched and no network is used.
 */
const here = dirname(fileURLToPath(import.meta.url));
const workflowsDir = resolve(here, '../.github/workflows');
const read = (name: string) => readFileSync(resolve(workflowsDir, name), 'utf8');

const DEV_WORKFLOW = 'probe-readonly.yml';
const STAGING_WORKFLOW = 'probe-staging.yml';

describe('deployment workflow separation', () => {
  it('keeps the development workflow on the development environment', () => {
    const yml = read(DEV_WORKFLOW);
    expect(yml).toContain('environment: pos-dev-admin');
    expect(yml).not.toContain('environment: pos-staging-admin');
  });

  it('keeps the staging workflow on the staging environment', () => {
    const yml = read(STAGING_WORKFLOW);
    expect(yml).toContain('environment: pos-staging-admin');
    expect(yml).not.toContain('environment: pos-dev-admin');
  });

  it('never runs on push or pull_request (manual dispatch only)', () => {
    for (const name of [DEV_WORKFLOW, STAGING_WORKFLOW]) {
      const yml = read(name);
      expect(yml, name).toMatch(/^on:\s*\n\s*workflow_dispatch:/m);
      expect(yml, name).not.toMatch(/^\s+(push|pull_request):/m);
    }
  });

  it('defaults apply to false in both workflows', () => {
    for (const name of [DEV_WORKFLOW, STAGING_WORKFLOW]) {
      const yml = read(name);
      const block = yml.split(/^\s+apply:/m)[1] ?? '';
      expect(block.slice(0, 200), name).toMatch(/type:\s*boolean/);
      expect(block.slice(0, 200), name).toMatch(/default:\s*false/);
    }
  });

  it('contains no hard-coded Supabase project references', () => {
    const known = ['uemdrtrzlvhdtonccczt', 'numwzjogaanfhlrfgrqk', 'mjqoydqcmecjvzbckgk'];
    for (const file of readdirSync(workflowsDir).filter((f) => f.endsWith('.yml'))) {
      const yml = read(file);
      for (const ref of known) {
        expect(yml, `${file} must not hard-code ${ref}`).not.toContain(ref);
      }
      // no generic 20-char lowercase project-ref literal in a workflow file
      expect(yml, `${file} must not contain a project-ref literal`).not.toMatch(
        /\b[a-z0-9]{20}\b/,
      );
    }
  });

  it('keeps checkout credential-less and read-only permissions', () => {
    for (const name of [DEV_WORKFLOW, STAGING_WORKFLOW]) {
      const yml = read(name);
      expect(yml, name).toContain('persist-credentials: false');
      expect(yml, name).toContain('permissions:');
      expect(yml, name).toContain('contents: read');
    }
  });

  it('passes credentials only through the environment, never as arguments', () => {
    for (const name of [DEV_WORKFLOW, STAGING_WORKFLOW]) {
      const yml = read(name);
      // secrets appear only as env bindings
      expect(yml, name).toMatch(/PROBE_ADMIN_PASS:\s*\$\{\{\s*secrets\.PROBE_ADMIN_PASS\s*\}\}/);
      expect(yml, name).toMatch(/PROBE_PW:\s*\$\{\{\s*secrets\.PROBE_PW\s*\}\}/);
      // never interpolated directly into a run command
      expect(yml, name).not.toMatch(/secrets\.[A-Z_]+\s*\}\}\s*-/);
    }
  });
});
