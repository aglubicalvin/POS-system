# Phase 3 — Sales Reporting

Reporting that reads only the server's authoritative ledger — never client
claims.

## Scope

- **Local reporting** (`src/reports/`): sell-through, GST/Sale tax lines and
  day-closure summaries computed from the device ledger.
- **Server reports** (`server/.../reports/`): store-wide revenue, gross
  margin from catalog costs, stock-value and received-vs-sold reporting over
  the canonical tables.
- **Admin/manager surface**: sales report button in the till UI (admin
  roles), authenticated `/api/reports/*` endpoints.
- **Payments reconciliation data**: pending/confirmed/failed payment states
  banked into the same summaries (`payments` status index).

## How to verify

```bash
npm test                        # reports suites over seeded ledgers
cd server && ./gradlew test     # server-side report queries
```
