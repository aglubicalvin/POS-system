import { spawnSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Integration test for finding F-01: does the credential-bearing Gradle
 * invocation leave a process alive after it returns?
 *
 * This runs the REAL Gradle wrapper with --no-daemon against a throwaway
 * project in an isolated GRADLE_USER_HOME, so no unrelated daemon can affect
 * the result. It does not use a stub gradlew and does not assert on workflow
 * text -- those assertions live in staging-workflows.test.ts.
 *
 * What it establishes:
 *   - the synthetic sentinel reaches the Gradle execution process;
 *   - the sentinel reaches the JavaExec child;
 *   - the Gradle execution process is gone after the command returns;
 *   - no daemon from the isolated home is still running;
 *   - the JavaExec child is gone.
 *
 * What it does NOT establish: that no daemon process is ever created. Gradle
 * 9.7.1 with --no-daemon starts a SINGLE-USE daemon and stops it at the end of
 * the build (asserted from the daemon log below). The guarantee is that the
 * process holding the credential terminates, not that none exists.
 *
 * No Supabase, staging, production or PostgreSQL connection is made.
 */

const repoRoot = resolve(__dirname, '..');
const serverDir = join(repoRoot, 'server');
const isWindows = process.platform === 'win32';
const gradlew = join(serverDir, isWindows ? 'gradlew.bat' : 'gradlew');

/** A synthetic value. Never a real credential. */
const SENTINEL = 'synthetic-sentinel-not-a-credential';

function javaProcesses(): number[] {
  if (isWindows) {
    const out = spawnSync(
      'powershell',
      ['-NoProfile', '-Command', "Get-Process java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id"],
      { encoding: 'utf8' },
    );
    return (out.stdout ?? '')
      .split(/\r?\n/)
      .map((line) => Number.parseInt(line.trim(), 10))
      .filter((n) => Number.isFinite(n));
  }
  const out = spawnSync('pgrep', ['-f', 'java'], { encoding: 'utf8' });
  return (out.stdout ?? '')
    .split(/\r?\n/)
    .map((line) => Number.parseInt(line.trim(), 10))
    .filter((n) => Number.isFinite(n));
}

function isAlive(pid: number): boolean {
  if (isWindows) {
    const out = spawnSync(
      'powershell',
      ['-NoProfile', '-Command', `if (Get-Process -Id ${pid} -ErrorAction SilentlyContinue) { 'alive' } else { 'gone' }`],
      { encoding: 'utf8' },
    );
    return (out.stdout ?? '').includes('alive');
  }
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

/**
 * Seed an isolated GRADLE_USER_HOME from the cached distribution the wrapper
 * will actually use.
 *
 * The version is read from gradle-wrapper.properties rather than guessed: the
 * cache may hold several distributions, and seeding the wrong one makes the
 * wrapper download (a network operation this test must not depend on).
 *
 * robocopy is used on Windows because the distribution contains paths longer
 * than MAX_PATH, which fs.cpSync cannot create.
 */
function seedGradleHome(home: string): boolean {
  const props = readFileSync(join(serverDir, 'gradle', 'wrapper', 'gradle-wrapper.properties'), 'utf8');
  const match = /gradle-([0-9.]+)-(bin|all)\.zip/.exec(props);
  if (!match) return false;
  const distName = `gradle-${match[1]}-${match[2]}`;

  const dists = join(homedir(), '.gradle', 'wrapper', 'dists');
  const source = join(dists, distName);
  if (!existsSync(source)) return false;

  const target = join(home, 'wrapper', 'dists', distName);
  mkdirSync(target, { recursive: true });

  if (isWindows) {
    const r = spawnSync('robocopy', [source, target, '/E', '/NFL', '/NDL', '/NJH', '/NJS', '/NP'], {
      encoding: 'utf8',
      timeout: 300_000,
    });
    // robocopy: 0-7 are success codes.
    return (r.status ?? 99) < 8;
  }
  cpSync(source, target, { recursive: true });
  return true;
}

/**
 * Opt-in: this test starts a real Gradle build, which is slow and competes for
 * CPU with the rest of the suite. Running it inside the default parallel run
 * starves the PowerShell-based tests and makes them time out, so it is gated
 * behind GRADLE_LIFETIME_TEST=1 and run as its own step.
 */
const enabled = process.env.GRADLE_LIFETIME_TEST === '1';

describe.skipIf(!enabled)('F-01: the credential-bearing Gradle process terminates', () => {
  it(
    'runs the real wrapper with --no-daemon and leaves no process holding the sentinel',
    () => {
      const work = mkdtempSync(join(tmpdir(), 'gradle-lifetime-'));
      const projectDir = join(work, 'project');
      const gradleHome = join(work, 'gradle-home');
      mkdirSync(projectDir, { recursive: true });
      mkdirSync(gradleHome, { recursive: true });

      try {
        writeFileSync(
          join(projectDir, 'settings.gradle.kts'),
          'rootProject.name = "lifetime-probe"\n',
          'utf8',
        );
        writeFileSync(
          join(projectDir, 'build.gradle.kts'),
          [
            'val record = layout.buildDirectory.file("record.txt")',
            'val childRecord = layout.buildDirectory.file("child.txt")',
            '',
            'tasks.register("recordEnv") {',
            '    doLast {',
            '        val f = record.get().asFile',
            '        f.parentFile.mkdirs()',
            '        val v = System.getenv("CHECK_PASS") ?: "<absent>"',
            '        f.writeText("pid=" + ProcessHandle.current().pid() + "\\n" +',
            '            "sentinel_present=" + (v == System.getenv("EXPECTED_SENTINEL")) + "\\n")',
            '    }',
            '}',
            '',
            '// A real child JVM, spawned from the Gradle process, that records its',
            '// own PID and whether it inherited the sentinel. This stands in for the',
            '// JavaExec probe child. The source is compiled with the JDK compiler at',
            '// build time so no prebuilt class is needed.',
            'tasks.register("childEnv") {',
            '    dependsOn("recordEnv")',
            '    doLast {',
            '        val f = childRecord.get().asFile',
            '        f.parentFile.mkdirs()',
            '        val src = File(f.parentFile, "ChildProbe.java")',
            '        src.writeText(',
            '            "public class ChildProbe {" +',
            '            "  public static void main(String[] a) throws Exception {" +',
            '            "    String v = System.getenv(\\"CHECK_PASS\\");" +',
            '            "    String e = System.getenv(\\"EXPECTED_SENTINEL\\");" +',
            '            "    java.nio.file.Files.writeString(java.nio.file.Path.of(a[0])," +',
            '            "      \\"child_pid=\\" + ProcessHandle.current().pid() + \\"\\\\n\\" +" +',
            '            "      \\"child_sentinel_present=\\" + (v != null && v.equals(e)) + \\"\\\\n\\");" +',
            '            "  }" +',
            '            "}")',
            '        val javaHome = System.getProperty("java.home")',
            '        val javac = File(javaHome, "bin/javac").absolutePath',
            '        val java = File(javaHome, "bin/java").absolutePath',
            '        val compile = ProcessBuilder(javac, "-d", f.parentFile.absolutePath, src.absolutePath)',
            '            .redirectErrorStream(true)',
            '            .redirectOutput(ProcessBuilder.Redirect.DISCARD)',
            '            .start()',
            '        if (compile.waitFor() != 0) throw GradleException("javac failed")',
            '        val p = ProcessBuilder(java, "-cp", f.parentFile.absolutePath, "ChildProbe", f.absolutePath)',
            '            .redirectErrorStream(true)',
            '            .redirectOutput(ProcessBuilder.Redirect.DISCARD)',
            '            .start()',
            '        val code = p.waitFor()',
            '        if (code != 0) throw GradleException("child JVM exited " + code)',
            '    }',
            '}',
            '',
          ].join('\n'),
          'utf8',
        );

        const seeded = seedGradleHome(gradleHome);
        if (!seeded) {
          console.warn('SKIPPED: no cached Gradle distribution to seed the isolated home');
          return;
        }

        const before = new Set(javaProcesses());

        // On Windows gradlew is a .bat file, which spawnSync cannot execute
        // directly (status comes back null); it needs a shell. On POSIX the
        // wrapper is a shell script and runs directly.
        // The repository path contains a space, so with shell:true the command
        // must be quoted or cmd.exe splits it at the space.
        const result = spawnSync(
          isWindows ? `"${gradlew}"` : gradlew,
          ['--no-daemon', '-q', 'recordEnv', 'childEnv'],
          {
            cwd: projectDir,
            encoding: 'utf8',
            shell: isWindows,
            env: {
              ...process.env,
              GRADLE_USER_HOME: gradleHome,
              CHECK_PASS: SENTINEL,
              EXPECTED_SENTINEL: SENTINEL,
            },
            timeout: 300_000,
          },
        );

        expect(result.status, `gradle failed:\n${result.stdout}\n${result.stderr}`).toBe(0);

        const record = readFileSync(join(projectDir, 'build', 'record.txt'), 'utf8');
        expect(record).toContain('sentinel_present=true');
        const gradlePid = Number.parseInt(/pid=(\d+)/.exec(record)?.[1] ?? '', 10);
        expect(Number.isFinite(gradlePid)).toBe(true);

        const childRecord = readFileSync(join(projectDir, 'build', 'child.txt'), 'utf8');
        expect(childRecord).toContain('child_sentinel_present=true');
        const childPid = Number.parseInt(/child_pid=(\d+)/.exec(childRecord)?.[1] ?? '', 10);
        expect(Number.isFinite(childPid)).toBe(true);

        expect(isAlive(gradlePid), `Gradle process ${gradlePid} is still alive`).toBe(false);
        expect(isAlive(childPid), `JavaExec child ${childPid} is still alive`).toBe(false);

        const after = javaProcesses();
        const leaked = after.filter((pid) => !before.has(pid));
        expect(leaked, `new java processes survived: ${leaked.join(', ')}`).toEqual([]);

        const daemonDir = join(gradleHome, 'daemon');
        if (existsSync(daemonDir)) {
          const logs = readdirSync(daemonDir, { recursive: true })
            .map(String)
            .filter((name) => name.endsWith('.out.log'));
          const text = logs
            .map((name) => readFileSync(join(daemonDir, name), 'utf8'))
            .join('\n');
          expect(text).toContain('Daemon will be stopped at the end of the build');
        }

        expect(record).not.toContain(SENTINEL);
        expect(childRecord).not.toContain(SENTINEL);
      } finally {
        rmSync(work, { recursive: true, force: true });
      }
    },
    360_000,
  );
});