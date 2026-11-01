# AGENTS — AI Coding Agent Rules

This repository runs **no agents at runtime**: the product has no model calls, no
tool-using services and no MCP servers. This file governs *development-time*
coding agents working on the codebase. Anything here is a convention for
contributors (human or AI), not a runtime component.

## 1. Agent roles

| Role | Purpose | May write |
| --- | --- | --- |
| `code-agent` | Implement features/fixes in one layer (till domain, Ktor server, admin app) | source + tests in that layer |
| `migration-agent` | Author `supabase/migrations/NNNN_*.sql` (+ rollback) | migrations only |
| `qa-agent` | Add/adjust tests, run suites, report failures | tests and fixtures only |
| `docs-agent` | Keep `docs/`, `README.md` and this set accurate | Markdown only |
| `release-agent` | Verify gates, assemble release evidence | nothing (read-only) |

## 2. Responsibilities and capabilities

- `code-agent`: reads the layer, makes the smallest change that satisfies the
  request, keeps money logic in `src/domain` and authorization in SQL. Must not
  weaken assertions, swallow errors, or add suppressions to make a check pass.
- `migration-agent`: writes forward SQL **and** the matching `*.rollback.sql`,
  keeps `search_path = ""` on `SECURITY DEFINER` functions, targets policies
  explicitly (`TO authenticated`), and never edits an applied migration.
- `qa-agent`: reproduces a failure before fixing it, runs the narrowest covering
  suite, and reports exactly what ran and what did not.
- `docs-agent`: documents behaviour that exists; future work is labelled as such.
- `release-agent`: never mutates production; produces fingerprints and diffs.

## 3. Tools and MCP servers

| Tool | Allowed use |
| --- | --- |
| `git` | Read history, create commits when asked; never force-push |
| Gradle wrapper | Build/test the Kotlin server |
| `npx vitest`, `npx tsc` | TS suites and typecheck |
| Supabase CLI (`db query`, `projects …`) | Dev/verify projects only, read-first |
| `server/tools/ProbeState.java` | Read-only parity audits (requires `PROBE_CONFIRM=yes`) |
| `server/tools/ApplyMigration.java` | Transaction-scoped migration apply on a test DB |

No MCP servers are configured for this project; see `MCP.md` for how one would be
added and why none is present today.

## 4. Triggers and execution conditions

Run an agent when: a failing check exists, a migration must be authored, a
document no longer matches the code, or a release gate needs evidence. Do **not**
run feature work while the release gates in `docs/production-readiness.md` are
open unless the user explicitly reprioritizes.

## 5. Input and output formats

- Input: a scoped task naming the layer, the acceptance check and the files that
  may change.
- Output: a diff, the commands run with exit codes, and a short report of
  limitations. No narrative claims that are not backed by a command.

## 6. Prompt templates

```
ROLE: code-agent
TASK: <one sentence>
LAYER: till-domain | ktor-server | admin-mobile | scripts
ACCEPTANCE: <command that must exit 0>
CONSTRAINTS: money in integer pesewas; no new deps without justification;
             do not touch supabase/migrations; keep authorization in SQL/RLS.
```

```
ROLE: migration-agent
TASK: add <change>
FILES: supabase/migrations/NNNN_<name>.sql + NNNN_<name>.rollback.sql
ACCEPTANCE: CleanReplayMigrationTest + AdminMigrationTest pass;
            ProbeState fingerprint updated in docs/production-readiness.md.
CONSTRAINTS: idempotent (if not exists), explicit TO authenticated policies,
             search_path = "" on SECURITY DEFINER, no edits to applied files.
```

## 7. Collaboration and handoffs

`code-agent → qa-agent` (tests) → `release-agent` (gates). `migration-agent` hands
the fingerprint delta to `docs-agent`. A handoff must include: what changed, the
command that proves it, and anything that could not be verified.

## 8. Memory and context management

- Source of truth is the repository, not chat history: re-read files before
  editing them.
- Durable facts belong in `docs/` (audit, readiness, this set), never only in the
  conversation.
- Prefer pinned artifacts (`contract/events.json`, `schema.sql`) over prose.

## 9. Guardrails and safety rules

1. Never run destructive commands against production; never apply migrations to
   production without explicit authorization and a closed release gate.
2. Never disable or weaken tests, RLS, CHECK constraints or `search_path` pinning
   to make something pass.
3. Never print secrets. `.env` is git-ignored; keys belong in a secret store.
4. Never add a service-role key to the admin/mobile bundle.
5. Use an isolated database for migration work; the verify project is disposable,
   the dev project holds data.
6. Commit only files that belong to the task; keep scratch artifacts untracked.

## 10. Error handling and retry

- Fix the cause; do not retry a failing migration blindly. Re-run after a fix and
  report the second result separately from the first.
- On a transient network failure, retry once, then report the blocker.
- A failed apply must leave no partial object: use one transaction per file.

## 11. Logging and observability

- Server logs unhandled exceptions with the request context and returns a generic
  500 body.
- Agents report commands, exit codes and failure text verbatim (never "tests
  pass" without the counts).

## 12. Human-in-the-loop checkpoints

- Any production change, migration to production, credential rotation, project
  creation/deletion, or `git push`.
- Any decision that changes money semantics, authorization, or public API shape.
- Any rollback or data-restoring operation.

## 13. Scalability considerations

Keep changes layer-local; prefer additive endpoints and new migrations over edits.
If a change spans till + server + database, split it into tasks that each end with
a green check, so a failed step never leaves two layers out of sync.

## 14. Example workflows

**Add a field to a sale.** migration-agent authors 0010 + rollback; code-agent
updates DTO/validation in Kotlin and the SQLite dialect in `src/db/schema.ts`;
qa-agent updates `tests/schema.test.ts` (canonical comparison) and
`CleanReplayMigrationTest`; docs-agent updates `DATABASE.md` and the fingerprint.

**Fix an E2E failure.** qa-agent reproduces with `npm run e2e:supabase`, isolates
whether it is schema, RLS or script logic, fixes the cause, re-runs only that
suite, and reports the before/after evidence.
