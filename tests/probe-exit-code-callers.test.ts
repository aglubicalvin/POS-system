import { spawnSync } from 'node:child_process';
import { generateKeyPairSync, createHash } from 'node:crypto';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Exit-code compatibility for server/tools/probe-readonly.ps1.
 *
 * The cleanup-only status is 4. It was previously 3, which collides with psql's
 * documented "script error with ON_ERROR_STOP" status -- and this script runs
 * psql with ON_ERROR_STOP=1. These assertions pin the contract and confirm that
 * every workflow caller treats any non-zero status as failure.
 */

const repoRoot = resolve(__dirname, '..');

const script = readFileSync(resolve(repoRoot, 'server', 'tools', 'probe-readonly.ps1'), 'utf8');
const workflow = (name: string) => readFileSync(resolve(repoRoot, '.github', 'workflows', name), 'utf8').replace(/\r\n/g, '\n');

interface WorkflowStep {
  lines: string[];
  sequenceIndent: number;
}

interface WorkflowJob {
  name: string;
  indent: number;
  lines: string[];
}

function indentation(line: string): number {
  return line.match(/^\s*/)?.[0].length ?? 0;
}

function stripYamlComment(line: string): string {
  let quote: 'single' | 'double' | undefined;
  for (let i = 0; i < line.length; i++) {
    const char = line[i]!;
    if (quote === 'single') {
      if (char === "'" && line[i + 1] === "'") i++;
      else if (char === "'") quote = undefined;
      continue;
    }
    if (quote === 'double') {
      if (char === '\\') i++;
      else if (char === '"') quote = undefined;
      continue;
    }
    if (char === "'") quote = 'single';
    else if (char === '"') quote = 'double';
    else if (char === '#' && (i === 0 || /\s/.test(line[i - 1]!))) return line.slice(0, i);
  }
  return line;
}

/** Replace quoted scalar contents so token scans only inspect YAML syntax. */
function blankQuotedScalars(line: string): string {
  let result = '';
  let quote: 'single' | 'double' | undefined;
  for (let i = 0; i < line.length; i++) {
    const char = line[i]!;
    if (quote === 'single') {
      result += ' ';
      if (char === "'" && line[i + 1] === "'") {
        result += ' ';
        i++;
      } else if (char === "'") quote = undefined;
      continue;
    }
    if (quote === 'double') {
      result += ' ';
      if (char === '\\') {
        if (i + 1 < line.length) {
          result += ' ';
          i++;
        }
      } else if (char === '"') quote = undefined;
      continue;
    }
    if (char === "'") {
      quote = 'single';
      result += ' ';
    } else if (char === '"') {
      quote = 'double';
      result += ' ';
    } else {
      result += char;
    }
  }
  return result;
}

/**
 * GitHub Actions supports aliases, but this bounded parser does not expand
 * them. Reject YAML composition tokens outside comments, quoted scalars, and
 * block scalar bodies so inherited settings cannot be silently overlooked.
 */
function hasUnsupportedYamlComposition(yml: string): boolean {
  let blockScalarIndent: number | undefined;
  for (const line of yml.split(/\r?\n/)) {
    const indent = indentation(line);
    if (blockScalarIndent !== undefined) {
      if (!line.trim() || indent > blockScalarIndent) continue;
      blockScalarIndent = undefined;
    }

    if (/^\t/.test(line)) return true;
    const uncommented = stripYamlComment(line);
    const unquoted = blankQuotedScalars(uncommented);
    if (/^\s*\?\s/.test(unquoted)) return true;
    if (/(?:^|[\s,{])<<\s*:/.test(unquoted)) return true;
    if (/^(?:[^:]+:\s*|\s*-\s*)?[\[{]/.test(unquoted.trim())) return true;
    if (hasStructuralAnchorOrAlias(unquoted)) return true;

    // Block scalar contents are executable shell text, not YAML properties.
    if (/(?:^|:\s*|\s)[|>](?:[+-][1-9]?|[1-9][+-]?)?\s*$/.test(uncommented)) {
      blockScalarIndent = indent;
    }
  }
  return false;
}

/**
 * Reject anchor/alias indicators only when they begin a YAML node. We do not
 * parse anchor names: after a structural `&` or `*`, every non-space,
 * non-flow-indicator character belongs to the YAML 1.2 anchor name. Tags are
 * rejected at node starts too, since a tagged mapping key can also evade the
 * bounded direct-key extractor. This avoids an incomplete name character class
 * silently accepting valid names such as `co.flag` or `co+flag`.
 */
function hasStructuralAnchorOrAlias(line: string): boolean {
  const tokenAt = (index: number): boolean => {
    const indicator = line[index]!;
    if (indicator === '!') return true;
    if (indicator !== '&' && indicator !== '*') return false;
    const next = line[index + 1];
    return next !== undefined && !/\s|[,\[\]{}]/.test(next);
  };

  const trimmed = line.trimStart();
  const leadingOffset = line.length - trimmed.length;
  if (tokenAt(leadingOffset)) return true;
  const listItem = /^-\s+/.exec(trimmed);
  if (listItem && tokenAt(leadingOffset + listItem[0].length)) return true;

  // A mapping value can start with an anchor or alias after its colon. This
  // exact-node-start check does not match ordinary values such as `echo &&` or
  // scalar text containing `fish & chips`.
  const mappingValue = /:\s*([&*!])/g;
  for (const match of line.matchAll(mappingValue)) {
    const indicatorIndex = match.index + match[0].length - 1;
    if (tokenAt(indicatorIndex)) return true;
  }

  return false;
}

function mappingValue(line: string, property: string): string | undefined {
  const escaped = property.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const direct = line.match(new RegExp(`^(?:${escaped}|"${escaped}"|'${escaped}')\\s*:\\s*(.*?)\\s*$`));
  if (direct) return direct[1] ?? '';

  // Escaped double-quoted keys can decode to a protected key. Treat any such
  // direct mapping key as ambiguous instead of assuming it is unrelated.
  if (/^"(?:[^"\\]|\\.)+"\s*:/.test(line) && /^".*\\.*"\s*:/.test(line)) {
    return '__ambiguous_quoted_yaml_key__';
  }
  return undefined;
}

/** Extract direct job mappings under the root `jobs:` mapping. */
function extractWorkflowJobs(yml: string): WorkflowJob[] {
  const lines = yml.split(/\r?\n/);
  const jobsIndex = lines.findIndex((line) => indentation(line) === 0 && /^jobs\s*:\s*(?:#.*)?$/.test(line.trim()));
  if (jobsIndex < 0) return [];

  let jobIndent: number | undefined;
  const jobs: WorkflowJob[] = [];
  let current: WorkflowJob | undefined;

  for (let i = jobsIndex + 1; i < lines.length; i++) {
    const line = lines[i]!;
    if (!line.trim() || line.trimStart().startsWith('#')) {
      if (current) current.lines.push(line);
      continue;
    }

    const indent = indentation(line);
    if (indent === 0) break;
    const jobMatch = line.match(/^\s*([A-Za-z0-9_-]+)\s*:\s*(?:#.*)?$/);
    if (jobIndent === undefined && jobMatch) jobIndent = indent;
    if (jobIndent === undefined) continue;
    if (indent < jobIndent) break;

    if (indent === jobIndent && jobMatch) {
      if (current) jobs.push(current);
      current = { name: jobMatch[1]!, indent, lines: [line] };
    } else if (current) {
      current.lines.push(line);
    }
  }

  if (current) jobs.push(current);
  return jobs;
}

function hasAmbiguousWorkflowStructure(yml: string, jobs: WorkflowJob[]): boolean {
  const rootJobs = yml.split(/\r?\n/).filter(
    (line) => indentation(line) === 0 && mappingValue(stripYamlComment(line.trim()), 'jobs') !== undefined,
  );
  if (rootJobs.length !== 1) return true;

  return jobs.some((job) => {
    const stepsIndent = job.indent + 2;
    const directSteps = job.lines.filter(
      (line) => indentation(line) === stepsIndent && mappingValue(stripYamlComment(line.trim()), 'steps') !== undefined,
    );
    return directSteps.length > 1;
  });
}

/**
 * Extract only direct sequence items under a `steps:` key. GitHub workflow
 * steps are mappings represented by a dash at one shared indentation level;
 * nested `with:` and block `run:` content therefore stays inside its parent
 * step instead of being mistaken for a sibling.
 */
function extractWorkflowSteps(yml: string, jobIndent: number): WorkflowStep[] {
  const lines = yml.split(/\r?\n/);
  const stepsIndent = jobIndent + 2;
  const stepsIndex = lines.findIndex(
    (line) => indentation(line) === stepsIndent && /^steps\s*:\s*(?:#.*)?$/.test(line.trim()),
  );
  if (stepsIndex < 0) return [];

  let sequenceIndent: number | undefined;
  const steps: WorkflowStep[] = [];
  let current: string[] | undefined;

  for (let i = stepsIndex + 1; i < lines.length; i++) {
    const line = lines[i]!;
    if (!line.trim() || line.trimStart().startsWith('#')) {
      if (current) current.push(line);
      continue;
    }

    const indent = indentation(line);
    if (indent <= stepsIndent) break;

    const isSequenceItem = /^\s*-\s/.test(line);
    if (sequenceIndent === undefined && isSequenceItem) sequenceIndent = indent;
    if (sequenceIndent === undefined) continue;
    if (indent < sequenceIndent) break;

    if (indent === sequenceIndent && isSequenceItem) {
      if (current) steps.push({ lines: current, sequenceIndent });
      current = [line];
    } else if (current) {
      current.push(line);
    }
  }

  if (current && sequenceIndent !== undefined) steps.push({ lines: current, sequenceIndent });
  return steps;
}

function stepProperties(step: WorkflowStep, property: string): string[] {
  const propertyIndent = step.sequenceIndent + 2;
  const values: string[] = [];
  for (const [index, line] of step.lines.entries()) {
    const indent = indentation(line);
    const text = line.trim();
    if (text.startsWith('#')) continue;

    // YAML permits the first mapping key on the same line as the sequence dash.
    const content = index === 0 ? text.replace(/^-\s*/, '') : indent === propertyIndent ? text : '';
    const value = mappingValue(stripYamlComment(content), property);
    if (value !== undefined) values.push(value.trim());
  }
  return values;
}

function jobProperties(job: WorkflowJob, property: string): string[] {
  const propertyIndent = job.indent + 2;
  const values: string[] = [];
  for (const line of job.lines.slice(1)) {
    const text = line.trim();
    if (!text || text.startsWith('#') || indentation(line) !== propertyIndent) continue;
    const value = mappingValue(stripYamlComment(text), property);
    if (value !== undefined) values.push(value.trim());
  }
  return values;
}

function runBody(step: WorkflowStep): { shell: string; body: string } | undefined {
  const propertyIndent = step.sequenceIndent + 2;
  const shell = stepProperties(step, 'shell');
  const runIndexes = step.lines.flatMap((line, index) =>
    indentation(line) === propertyIndent && mappingValue(stripYamlComment(line.trim()), 'run') !== undefined ? [index] : [],
  );
  if (shell.length !== 1 || runIndexes.length !== 1) return undefined;

  const index = runIndexes[0]!;
  const value = mappingValue(stripYamlComment(step.lines[index]!.trim()), 'run')!.trim();
  if (/^\|(?:[+-][1-9]?|[1-9][+-]?)?$/.test(value)) {
    const body: string[] = [];
    for (const line of step.lines.slice(index + 1)) {
      if (line.trim() && indentation(line) <= propertyIndent) break;
      body.push(line.trim() ? line.slice(Math.min(line.length, propertyIndent + 2)) : '');
    }
    return { shell: shell[0]!.trim(), body: body.join('\n') };
  }
  if (/^[|>]/.test(value)) return undefined;
  return { shell: shell[0]!.trim(), body: value };
}

function shellCommentless(line: string): string {
  // The caller's YAML block has already been separated from YAML comments.
  // For these workflow shells, a shell comment begins at a whitespace-bound #.
  if (line.trimStart().startsWith('#')) return '';
  return line.replace(/\s+#.*$/, '').trim();
}

function bodyInvocationCount(body: string): number {
  return body.split(/\r?\n/).filter((line) => line.includes('probe-readonly.ps1')).length;
}

function runBodyPropagates(body: string, shell: string): boolean {
  const lines = body.split(/\r?\n/).map(shellCommentless).filter(Boolean);
  const bareInvocation = /^(?:&\s*)?\.\/server\/tools\/probe-readonly\.ps1(?:\s+-Apply)?$/i;
  if (/^(?:pwsh|powershell)$/i.test(shell) && lines.length === 1 && bareInvocation.test(lines[0]!)) return true;

  // This is the only conditional caller form present in the repository. Both
  // branches end in the probe invocation, so PowerShell propagates its status.
  return /^(?:pwsh|powershell)$/i.test(shell) && lines.length === 3 &&
    /^\$apply\s*=\s*'\$\{\{\s*inputs\.apply\s*\}\}'\s*-eq\s*'true'$/i.test(lines[0]!) &&
    /^if\s*\(\$apply\)\s*\{\s*\.\/server\/tools\/probe-readonly\.ps1\s+-Apply\s*\}$/i.test(lines[1]!) &&
    /^else\s*\{\s*\.\/server\/tools\/probe-readonly\.ps1\s*\}$/i.test(lines[2]!);
}

/** Check only the workflow steps that actually invoke the probe. */
function probeCallerViolations(yml: string): string[] {
  const violations: string[] = [];
  if (hasUnsupportedYamlComposition(yml)) {
    violations.push('workflow contains unsupported YAML anchors, aliases, merge keys, or complex mapping syntax');
  }
  const jobs = extractWorkflowJobs(yml);
  if (hasAmbiguousWorkflowStructure(yml, jobs)) {
    violations.push('workflow has duplicate or ambiguous root jobs / job steps mappings');
  }
  const callJobs = jobs.flatMap((job) => {
    const steps = extractWorkflowSteps(job.lines.join('\n'), job.indent);
    const callers = steps.filter((step) => {
      const parsed = runBody(step);
      return parsed !== undefined && bodyInvocationCount(parsed.body) > 0;
    });
    return callers.length > 0 ? [{ job, callers }] : [];
  });
  const everyStep = jobs.flatMap((job) => extractWorkflowSteps(job.lines.join('\n'), job.indent));
  const rawInvocationCount = everyStep.reduce((sum, step) => sum + (runBody(step) ? bodyInvocationCount(runBody(step)!.body) : 0), 0);
  const parsedInvocationCount = callJobs.reduce(
    (total, { callers }) =>
      total + callers.reduce(
        (jobTotal, step) =>
          jobTotal + (runBody(step) ? bodyInvocationCount(runBody(step)!.body) : 0),
        0,
      ),
    0,
  );

  if (rawInvocationCount === 0) violations.push('workflow does not invoke probe-readonly.ps1');
  if (rawInvocationCount > 0 && callJobs.length === 0) {
    violations.push('could not safely associate the probe invocation with a workflow job and step');
  }
  if (rawInvocationCount !== parsedInvocationCount) {
    violations.push('could not safely associate every probe invocation with its workflow job and step');
  }

  for (const { job, callers } of callJobs) {
    const jobLabel = `job ${job.name}`;
    const jobContinueOnError = jobProperties(job, 'continue-on-error');
    if (jobContinueOnError.length > 1) {
      violations.push(`${jobLabel} has duplicate continue-on-error properties`);
    }
    if (jobContinueOnError.some((value) => !/^false(?:\s+#.*)?$/i.test(value))) {
      violations.push(`${jobLabel} must omit continue-on-error or set a static false value`);
    }

    callers.forEach((step, index) => {
      const label = `${jobLabel} probe step ${index + 1}`;
      const properties = stepProperties(step, 'continue-on-error');
      if (properties.length > 1) {
        violations.push(`${label} has duplicate continue-on-error properties`);
      }
      if (properties.some((value) => !/^false(?:\s+#.*)?$/i.test(value))) {
        violations.push(`${label} must omit continue-on-error or set a static false value`);
      }

      const parsedRun = runBody(step);
      if (!parsedRun || !runBodyPropagates(parsedRun.body, parsedRun.shell)) {
        violations.push(`${label} run body is not proven to propagate the probe exit status`);
      }
      const runLines = parsedRun?.body.split(/\r?\n/).map(shellCommentless) ?? [];
      const invocationLines = runLines.filter((line) => line.includes('probe-readonly.ps1'));
      if (invocationLines.some((line) => /\|\|\s*true/.test(line))) {
        violations.push(`${label} suppresses failure with || true`);
      }
      if (invocationLines.some((line) => /;\s*\$?true/.test(line))) {
        violations.push(`${label} suppresses failure with a trailing true`);
      }
      if (invocationLines.some((line) => /-ErrorAction\s+SilentlyContinue/i.test(line))) {
        violations.push(`${label} suppresses PowerShell errors`);
      }
      if (runLines.some((line) => /catch\s*\{[^}]*\}\s*$/.test(line))) {
        violations.push(`${label} has a terminal catch block`);
      }
    });
  }

  return violations;
}

function addStepProperty(yml: string, stepName: string, property: string, value: string): string {
  const marker = `      - name: ${stepName}`;
  const occurrences = yml.split(marker).length - 1;
  if (occurrences !== 1) throw new Error(`Expected one workflow step named ${stepName}, found ${occurrences}`);
  return yml.replace(marker, `${marker}\n        ${property}: ${value}`);
}

function addJobProperty(yml: string, jobName: string, property: string, value: string): string {
  const marker = `  ${jobName}:`;
  const lines = yml.split(/\r?\n/);
  const occurrences = lines.filter((line) => line === marker).length;
  if (occurrences !== 1) throw new Error(`Expected one workflow job named ${jobName}, found ${occurrences}`);
  const jobLine = lines.findIndex((line) => line === marker);
  lines.splice(jobLine + 1, 0, `    ${property}: ${value}`);
  return lines.join('\n');
}

function appendUnrelatedJob(yml: string, jobName: string, property: string, value: string): string {
  return `${yml.trimEnd()}\n\n  ${jobName}:\n    ${property}: ${value}\n    runs-on: ubuntu-latest\n    steps:\n      - run: echo unrelated\n`;
}

function findPowerShell(): string {
  for (const candidate of process.platform === 'win32' ? ['powershell', 'pwsh'] : ['pwsh', 'powershell']) {
    const probe = spawnSync(candidate, ['-NoProfile', '-Command', '$PSVersionTable.PSVersion.Major'], {
      encoding: 'utf8',
      timeout: 30_000,
    });
    if (probe.status === 0) return candidate;
  }
  throw new Error('PowerShell is required to exercise the Docker exception path.');
}

function syntheticCertificate(): { pem: string; sha256: string } {
  const { publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const der = publicKey.export({ type: 'spki', format: 'der' });
  const body = der.toString('base64').match(/.{1,64}/g)!.join('\n');
  return {
    pem: `-----BEGIN CERTIFICATE-----\n${body}\n-----END CERTIFICATE-----\n`,
    sha256: createHash('sha256').update(der).digest('hex'),
  };
}

describe('probe-readonly exit-code contract', () => {
  it('uses 4 as the cleanup-only status', () => {
    expect(script).toContain('$script:CleanupOnlyExitCode = 4');
    expect(script).toContain('exit $script:CleanupOnlyExitCode');
  });

  it('does not use psql status 3 for cleanup failure', () => {
    // A bare "exit 3" would be indistinguishable from a psql ON_ERROR_STOP error.
    expect(script).not.toMatch(/^\s*exit\s+3\s*$/m);
  });

  it('preserves the primary psql status exactly', () => {
    expect(script).toContain('exit $PrimaryStatus');
    // The status must come from the captured psql code, not a constant.
    expect(script).toMatch(/Set-ProbeFailure -PrimaryStatus \$code/);
  });

  it('documents that 3 belongs to psql', () => {
    expect(script.toLowerCase()).toContain('on_error_stop');
    expect(script).toContain('psql documents');
  });

  // Every workflow that invokes the script must let a non-zero status fail.
  for (const name of ['probe-readonly.yml', 'probe-staging.yml']) {
    it(`${name} propagates the script's exit status`, () => {
      const yml = workflow(name);
      expect(probeCallerViolations(yml), name).toEqual([]);
    });
  }

  it('rejects continue-on-error true on the probe step, including quoted and dynamic values', () => {
    const yml = workflow('probe-readonly.yml');
    expect(probeCallerViolations(yml)).toEqual([]);
    expect(probeCallerViolations(addStepProperty(yml, 'Dry run or apply', 'continue-on-error', 'true')))
      .toContain('job apply probe step 1 must omit continue-on-error or set a static false value');
    expect(probeCallerViolations(addStepProperty(yml, 'Dry run or apply', 'continue-on-error', '"true"')))
      .toContain('job apply probe step 1 must omit continue-on-error or set a static false value');
    expect(probeCallerViolations(addStepProperty(yml, 'Dry run or apply', 'continue-on-error', '${{ inputs.ignore_failure }}')))
      .toContain('job apply probe step 1 must omit continue-on-error or set a static false value');
  });

  it('accepts an explicit static false on the probe step', () => {
    const yml = workflow('probe-readonly.yml');
    expect(probeCallerViolations(addStepProperty(yml, 'Dry run or apply', 'continue-on-error', 'false'))).toEqual([]);
  });

  it('normalizes quoted continue-on-error keys and rejects normalized duplicates', () => {
    const yml = workflow('probe-readonly.yml');
    const badStep = 'job apply probe step 1 must omit continue-on-error or set a static false value';
    for (const key of ['continue-on-error', '"continue-on-error"', "'continue-on-error'"]) {
      expect(probeCallerViolations(addStepProperty(yml, 'Dry run or apply', key, 'true'))).toContain(badStep);
      expect(probeCallerViolations(addStepProperty(yml, 'Dry run or apply', key, 'false'))).toEqual([]);
      expect(probeCallerViolations(addStepProperty(yml, 'Dry run or apply', key, '${{ inputs.allow }}'))).toContain(badStep);
    }
    const mixedDuplicate = addStepProperty(
      addStepProperty(yml, 'Dry run or apply', 'continue-on-error', 'false'),
      'Dry run or apply',
      '"continue-on-error"',
      'false',
    );
    expect(probeCallerViolations(mixedDuplicate)).toContain('job apply probe step 1 has duplicate continue-on-error properties');

    const badJob = 'job apply must omit continue-on-error or set a static false value';
    expect(probeCallerViolations(addJobProperty(yml, 'apply', "'continue-on-error'", 'true'))).toContain(badJob);
    expect(probeCallerViolations(addJobProperty(yml, 'apply', '"continue-on-error"', 'false'))).toEqual([]);
  });

  it('fails closed for structural YAML aliases, anchors, and merge keys but ignores literals', () => {
    const yml = workflow('probe-readonly.yml');
    const unsupported = 'workflow contains unsupported YAML anchors, aliases, merge keys, or complex mapping syntax';
    for (const name of ['defaults', 'co.flag', 'co+flag', 'co#flag']) {
      const anchorAndAlias = `env:\n  CONTINUE_KEY: &${name} continue-on-error\njobs:\n  probe:\n    steps:\n      - shell: pwsh\n        *${name} : true\n        run: |\n          ./server/tools/probe-readonly.ps1\n`;
      expect(probeCallerViolations(anchorAndAlias), name).toContain(unsupported);
    }
    const merge = yml.replace('  apply:\n', '  apply:\n    <<: *defaults\n');
    expect(probeCallerViolations(merge)).toContain(unsupported);
    const taggedKey = `jobs:\n  probe:\n    steps:\n      - shell: pwsh\n        !str continue-on-error: true\n        run: |\n          ./server/tools/probe-readonly.ps1\n`;
    expect(probeCallerViolations(taggedKey)).toContain(unsupported);

    const safeLiterals = yml
      .replace('name: probe-readonly-role\n', 'name: "quoted &co.flag and *alias text"\n# &co.flag *alias are comments\n')
      .replace('          else { ./server/tools/probe-readonly.ps1 }\n', '          else { ./server/tools/probe-readonly.ps1 }\n          # &co.flag *alias <<: continue-on-error: true\n')
      .replace('      - name: Upload sanitized evidence\n', '      - name: unrelated shell text\n        shell: bash\n        run: |\n          echo "&co.flag *alias" && echo ordinary\n\n      - name: Upload sanitized evidence\n');
    expect(probeCallerViolations(safeLiterals)).toEqual([]);
  });

  it('fails closed when root jobs or a job steps mapping is duplicated', () => {
    const yml = workflow('probe-readonly.yml');
    const ambiguity = 'workflow has duplicate or ambiguous root jobs / job steps mappings';
    expect(probeCallerViolations(`${yml}\njobs:\n  replacement:\n    steps: []\n`)).toContain(ambiguity);
    expect(probeCallerViolations(yml.replace('    steps:\n', '    steps:\n      - run: echo first\n    steps:\n'))).toContain(ambiguity);
  });

  it('requires the complete probe run body to propagate failure', () => {
    const original = workflow('probe-readonly.yml');
    expect(probeCallerViolations(original)).toEqual([]);
    const body = [
      '          ./server/tools/probe-readonly.ps1\n          exit 0',
      '          ./server/tools/probe-readonly.ps1\n          Write-Output masked',
      '          set +e\n          ./server/tools/probe-readonly.ps1\n          exit 0',
      '          ./server/tools/probe-readonly.ps1 || true',
      '          if ./server/tools/probe-readonly.ps1; then\n            echo suppressed\n          fi',
      '          ! ./server/tools/probe-readonly.ps1',
      '          try {\n            ./server/tools/probe-readonly.ps1\n          } catch {\n            Write-Output swallowed\n          }',
      '          ./server/tools/probe-readonly.ps1; Write-Output success',
    ];
    for (const mutatedBody of body) {
      const mutated = original.replace(
        "          $apply = '${{ inputs.apply }}' -eq 'true'\n          if ($apply) { ./server/tools/probe-readonly.ps1 -Apply }\n          else { ./server/tools/probe-readonly.ps1 }",
        mutatedBody,
      );
      expect(mutated).not.toBe(original);
      expect(probeCallerViolations(mutated)).toContain(
        'job apply probe step 1 run body is not proven to propagate the probe exit status',
      );
    }

    const unsupportedShell = original.replace('        shell: pwsh\n', '        shell: bash\n');
    expect(probeCallerViolations(unsupportedShell)).not.toEqual([]);
    const foldedRunBlock = original.replace('        run: |\n', '        run: >\n');
    expect(probeCallerViolations(foldedRunBlock)).not.toEqual([]);
  });

  it('does not associate an unrelated step continue-on-error property with the probe step', () => {
    const yml = workflow('probe-readonly.yml');
    expect(probeCallerViolations(addStepProperty(yml, 'Upload sanitized evidence', 'continue-on-error', 'true'))).toEqual([]);
  });

  it('rejects unsafe job-level continue-on-error values only for probe jobs', () => {
    const yml = workflow('probe-readonly.yml');
    const violation = 'job apply must omit continue-on-error or set a static false value';

    expect(probeCallerViolations(yml)).toEqual([]);
    expect(probeCallerViolations(addJobProperty(yml, 'apply', 'continue-on-error', 'true'))).toContain(violation);
    expect(probeCallerViolations(addJobProperty(yml, 'apply', 'continue-on-error', '"true"'))).toContain(violation);
    expect(probeCallerViolations(addJobProperty(yml, 'apply', 'continue-on-error', '${{ vars.ignore_job_failure }}')))
      .toContain(violation);
    expect(
      probeCallerViolations(
        addJobProperty(addJobProperty(yml, 'apply', 'continue-on-error', 'true'), 'apply', 'continue-on-error', 'false'),
      ),
    ).toContain('job apply has duplicate continue-on-error properties');
    expect(probeCallerViolations(addJobProperty(yml, 'apply', 'continue-on-error', 'false'))).toEqual([]);
  });

  it('ignores job-level values in unrelated jobs, nested mappings, comments, and block scalars', () => {
    const yml = workflow('probe-readonly.yml');
    const withUnrelatedJob = appendUnrelatedJob(yml, 'unrelated', 'continue-on-error', 'true');
    const withNestedAndComment = withUnrelatedJob
      .replace('  apply:\n', '  apply:\n    # continue-on-error: true\n    strategy:\n      matrix:\n        include:\n          - continue-on-error: true\n')
      .replace('          else { ./server/tools/probe-readonly.ps1 }\n', '          else { ./server/tools/probe-readonly.ps1 }\n          # continue-on-error: true\n');

    expect(probeCallerViolations(withNestedAndComment)).toEqual([]);
  });

  it('keeps step-level and job-level checks independent', () => {
    const yml = workflow('probe-readonly.yml');
    const jobAllowsFailures = addJobProperty(yml, 'apply', 'continue-on-error', 'false');
    const stepAllowsFailures = addStepProperty(jobAllowsFailures, 'Dry run or apply', 'continue-on-error', 'true');

    expect(probeCallerViolations(stepAllowsFailures)).toContain(
      'job apply probe step 1 must omit continue-on-error or set a static false value',
    );
    expect(probeCallerViolations(appendUnrelatedJob(yml, 'unrelated', 'continue-on-error', 'true'))).toEqual([]);
  });

  it('catches an in-process Docker function exception on POSIX and Windows', () => {
    const work = mkdtempSync(join(tmpdir(), 'probe-docker-throw-'));
    const certPath = join(work, 'root.crt');
    const calledPath = join(work, 'docker-called.txt');
    const runnerPath = join(work, 'throw-runner.ps1');
    const certificate = syntheticCertificate();
    writeFileSync(certPath, certificate.pem, 'utf8');
    writeFileSync(
      runnerPath,
      [
        'function docker { Set-Content -LiteralPath $env:DOCKER_CALLED -Value "yes"; throw "synthetic docker exception" }',
        `& '${resolve(repoRoot, 'server', 'tools', 'probe-readonly.ps1').replace(/'/g, "''")}' -Apply`,
        'exit $LASTEXITCODE',
        '',
      ].join('\n'),
      'utf8',
    );

    try {
      const powershell = findPowerShell();
      const result = spawnSync(
        powershell,
        ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', runnerPath],
        {
          cwd: work,
          encoding: 'utf8',
          timeout: 120_000,
          env: {
            ...process.env,
            POS_STAGING_PROJECT_REF: 'stagingref0000000001',
            POS_STAGING_POOLER_HOST: 'aws-0-eu-west-2.pooler.supabase.com',
            POS_STAGING_DB_ROOT_CERT: certPath,
            POS_STAGING_DB_ROOT_CERT_SHA256: certificate.sha256,
            PROBE_ADMIN_USER: 'postgres.stagingref0000000001',
            PROBE_ADMIN_PASS: 'synthetic-admin-pass-not-a-credential',
            PROBE_PW: 'synthetic-probe-pw-not-a-credential',
            POS_REVIEWED_SECURITY_DEFINER_FUNCTIONS: 'a,b,c',
            DOCKER_CALLED: calledPath,
          },
        },
      );
      const output = `${result.stdout ?? ''}${result.stderr ?? ''}`;
      expect(existsSync(calledPath), output).toBe(true);
      expect(result.status, output).toBe(4);
      expect(output).toContain('the database step failed before returning an exit status');
      expect(output).not.toContain('synthetic docker exception');
      expect(output).not.toContain('synthetic-admin-pass-not-a-credential');
      expect(output).not.toContain('synthetic-probe-pw-not-a-credential');
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });

  it('the identity workflow only mentions the script in comments', () => {
    const yml = workflow('probe-staging-identity.yml');
    const invocations = yml
      .split('\n')
      .filter((l) => l.includes('probe-readonly.ps1') && !l.trimStart().startsWith('#'));
    expect(invocations).toEqual([]);
  });
});
