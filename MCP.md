# MCP — Model Context Protocol Integration

## 1. Status: not integrated

This project contains **no MCP servers, no MCP client and no AI tool calls at
runtime**. The product is a POS/server/admin app; nothing in the request path
depends on a model or on MCP. This document records that fact and the rules that
would govern adding one, so the decision is explicit rather than accidental.

## 2. What MCP is (brief)

MCP is a protocol that lets an AI client discover and call external tools
("servers") through a typed interface. A server exposes tools/resources; a client
(a coding agent or an app) lists them and invokes them with JSON arguments.

## 3. MCP servers in this repository

| Server | Purpose | Status |
| --- | --- | --- |
| — | — | none configured |

There is no `.mcp.json`, no `mcpServers` entry in any config, and no dependency on
an MCP SDK in `package.json`, `admin-mobile/package.json` or `build.gradle.kts`.

## 4. Why none is present

1. **Security surface.** The production path holds service-role credentials; an
   MCP server exposed to a model would be a new privileged path to the database.
2. **Determinism.** Money and authorization must be reproducible; model-driven
   tool calls are not part of that contract.
3. **Sufficient alternatives.** The same operations are available through the
   Supabase CLI, `scripts/e2e-supabase.ts` and `server/tools/*`, which are auditable
   and non-interactive.

## 5. If one is added (required design)

- **Scope:** development-time only (e.g. read-only Supabase inspection). Never
  reachable from the app bundle and never holding production credentials.
- **Installation:** add the server under `tools/mcp/`, pin its version, document
  the launch command in this file and in `SETUP.md`.
- **Environment:** separate `.env.mcp` (git-ignored); read-only database role with
  `select` grants only — not the service-role key.
- **Permissions:** allow-list tables/views; deny `auth`, `supabase_migrations` and
  any write tool by default.
- **Application connections:** none. The till, server and admin app must not import
  or call an MCP client.
- **Agent access:** only a `release-agent`-style read-only role may call it, per
  `AGENTS.md`.
- **Capabilities to expose (maximum):** `probe_state`, `list_migrations`,
  `read_table(limit)`, `explain(sql)` (read-only).

## 6. Example request/response (illustrative, not implemented)

```jsonc
// request
{ "tool": "probe_state", "arguments": { "project": "verify" } }
// response
{ "tables": 30, "views": 6, "policies": 20, "policies_to_authenticated": 19,
  "functions": 12, "triggers": 5, "migrations": ["0000", "0001", "…", "0009"] }
```

## 7. Error handling and logging

- Tool errors must be returned as structured errors, never as fabricated success.
- Every invocation logs: tool name, arguments (secrets redacted), duration, result
  status. Logs are local to the developer machine.
- A tool must refuse production targets by default, mirroring
  `ProbeState`'s `PROBE_CONFIRM` guard.

## 8. API key management

- Keys come from the environment, never from the repository.
- Read-only role => read-only key. Rotate on any exposure.
- Never pass the service-role key to a model-accessible tool.

## 9. Testing an MCP integration

1. Start the server locally against the verify project.
2. Call `probe_state` and compare with `server/tools/ProbeState` output.
3. Assert write tools are absent/denied.
4. Assert a production URL is refused.

## 10. Scalability and adding servers

Keep one server per concern (database inspection, CI status). Prefer composing
existing CLI tools over building new privileged paths. Document each server here
with: purpose, tools, credentials, allow-list, refusal rules and an example call.

## 11. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Client sees no tools | server not started / wrong command | check the launch command in this file |
| 401 from Supabase | expired or wrong key | rotate, use read-only key |
| Tool refuses a project | production guard fired | use the verify/dev project |
| Model invents results | tool error swallowed | return structured errors; never mock |
