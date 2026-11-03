import { readFileSync } from 'node:fs';

/**
 * Test-only reader for the evidence-extraction logic of the "Run read-only
 * staging identity probe" step in .github/workflows/probe-staging-identity.yml.
 *
 * The workflow is executed by bash under `-eo pipefail`. These tests do not
 * re-implement that logic: they lift the step's own shell body out of the YAML
 * and run it against a stub `gradlew`, so the assertions cover exactly what
 * Actions would run. Non-bash expressions (`${{ … }}`) are blanked out first,
 * and the artifact-upload step is never touched.
 */

export interface ProbeStepHarness {
  /** The step's `run:` body with `${{ … }}` expressions removed. */
  script: string;
  /** Name of the scratch file the step writes and must delete: `probe-raw.txt`. */
  rawFile: string;
  /** Name of the allowlisted summary it writes: `probe-evidence.txt`. */
  evidenceFile: string;
}

/** Extract the probe step's shell body from the identity workflow's text. */
export function extractProbeStep(yml: string): ProbeStepHarness {
  const withoutExpressions = yml.replace(/\$\{\{[^}]*\}\}/g, '');
  const stepIdx = withoutExpressions.indexOf('Run read-only staging identity probe');
  if (stepIdx < 0) throw new Error('probe step not found in the workflow');

  const afterRun = withoutExpressions.slice(stepIdx).split(/^\s+run:\s*\|\s*$/m)[1];
  if (afterRun === undefined) throw new Error('probe step has no `run: |` block');

  const lines = afterRun.split('\n');
  const body: string[] = [];
  const indent = /^\s+/.exec(lines.find((line) => line.trim() !== '') ?? '')?.[0] ?? '';
  for (const line of lines) {
    if (line.trim() === '') {
      body.push('');
      continue;
    }
    if (!line.startsWith(indent)) break; // dedented back into YAML: the step ended
    body.push(line.slice(indent.length));
  }

  const script = body.join('\n').trimEnd();
  if (!script.includes('probe-raw.txt') || !script.includes('probe-evidence.txt')) {
    throw new Error('the extracted probe step does not look like the evidence step');
  }
  return { script, rawFile: 'probe-raw.txt', evidenceFile: 'probe-evidence.txt' };
}

/** The workflow text, read once; tests never write to it. */
export function identityWorkflowText(workflowsDir: string): string {
  return readFileSync(`${workflowsDir}/probe-staging-identity.yml`, 'utf8');
}

/**
 * Parse a sanitized evidence file into key/value pairs.
 *
 * The evidence is a flat allowlist, so a bare `field=value` line repeated twice
 * is a defect: the parser rejects duplicates rather than silently keeping the
 * last one, and it also rejects anything that is not `key=value`.
 */
export function parseEvidence(text: string): Record<string, string> {
  const fields: Record<string, string> = {};
  for (const line of text.split('\n')) {
    if (line.trim() === '') continue;
    const eq = line.indexOf('=');
    if (eq <= 0) throw new Error(`evidence line is not key=value: ${line}`);
    const key = line.slice(0, eq);
    if (key in fields) throw new Error(`duplicate evidence field: ${key}`);
    fields[key] = line.slice(eq + 1);
  }
  return fields;
}
