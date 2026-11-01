# FEATURES — Feature Development

Every feature here exists in the repository. Unbuilt items are marked **future**
and correspond to `docs/roadmap.md`.

## 1. Feature list and scope

| ID | Feature | Scope | Priority | Effort | Status |
| --- | --- | --- | --- | --- | --- |
| F1 | Offline checkout | cart → totals → sale/item/payment/stock + outbox atomically | P0 | M | done |
| F2 | Deterministic sync | idempotent events, per-device seq, gap buffering, repricing | P0 | L | done |
| F3 | Catalog sync | product/user upserts relayed verbatim to other tills | P0 | M | done |
| F4 | Money integrity | integer pesewas end to end, server-side recompute | P0 | S | done |
| F5 | Till auth | PIN login bound to device, Argon2id, lockout | P0 | M | done |
| F6 | Admin auth | Supabase session + RLS `is_admin()`, SecureStore | P0 | M | done |
| F7 | Customers & payroll | records, deductions, purchase summary | P1 | M | done |
| F8 | Hire purchase | contracts, schedule, installments, balances | P1 | L | done |
| F9 | MoMo reconciliation | idempotent RPC, mismatch refusal | P1 | M | done |
| F10 | Faults & approvals | supervisor lifecycle, payment blocking, queue | P1 | L | done |
| F11 | Reporting | daily sales, stock, review queue, discrepancies, dashboard | P1 | M | done |
| F12 | Auditability | hash-chained audit log, price audit | P1 | S | done |
| F13 | Approvals | persisted approval rows, thresholds | P2 | S | done |
| F14 | Receipts | 32-column text layout + detailed on-screen receipt | P2 | M | done partial |
| F15 | Till voids/refunds UI | domain supports; no screen | P2 | M | future |
| F16 | ESC/POS encoder | text only today | P2 | M | future |
| F17 | Live MoMo provider | adapter only | P1 | L | future |
| F18 | MFA / server login | schema flags only | P2 | M | future |
| F19 | GRA E-VAT | not started | P3 | L | future |

## 2. Objective per feature

- **F1** never block a sale on the network; never compute money in the UI.
- **F2** make retries free: a duplicated event must change nothing.
- **F3** one authoritative catalog; tills converge without manual refresh.
- **F4** no rounding drift; the server is the arbiter.
- **F5** a stolen tablet cannot sell without a bound cashier; PINs are never stored.
- **F6** a cashier's phone cannot read payroll or margins even with a valid JWT.
- **F9** a mismatched amount can never mark a payment reconciled.
- **F10** only a supervisor can block money, and every transition is auditable.

## 3. Implementation plan (per feature)

1. Domain/API contract first (`src/domain` or server DTO).
2. Storage change: SQLite schema (`src/db/schema.ts`) **and** PostgreSQL
   (`schema.sql` for canonical, a new migration for admin).
3. Enforcement in the database for anything security-relevant (RLS/RPC/trigger).
4. UI wiring last, calling domain/helpers only.
5. Tests: unit (domain/helpers), integration (Kotlin/embedded PG), E2E (REST).

## 4. Frontend details

- **Till** (`src/ui/static/app.js`): renders catalog from local SQLite, cart state
  in memory, checkout through `checkout()`, receipt from the domain result.
  Manager/admin action buttons are hidden unless permitted.
- **Admin** (`admin-mobile/src/app/(admin)`): dashboard cards, list screens,
  reconciliation form; each screen uses `lib/*` helpers, never inline SQL.
- Empty/loading/error states per `DESIGN.md` §7.

## 5. Backend details

- `EventApplier.applyBatch` claims each event (`processed_events`), applies in one
  transaction, buffers gaps, and returns per-event results.
- `MobileMoneyService` owns the initiate → webhook → status lifecycle; HMAC is
  verified over the raw body; unset secret ⇒ 503.
- `Reports` serves daily sales, stock, review queue and discrepancies.
- Admin writes are RPCs: price (+audit), category, media, reconciliation,
  hire-purchase creation/installment payment, approval-queue processing.

## 6. Database changes and migrations

| Feature | Objects |
| --- | --- |
| F6 | `profiles`, `is_admin()`, 0008 policy retarget |
| F7 | `customers`, `payroll_deductions`, `customer_purchase_summary` |
| F8 | `hire_purchase_contracts`, `hire_purchase_payments`, `hire_purchase_overview` |
| F9 | reconcile RPC (0007) |
| F10 | `faults`, `approval_queues`, `is_supervisor()`, `payments.blocked` (0009) |
| F11 | `dashboard_sales_daily`, `low_stock_overview`, `admin_product_catalog`, `admin_inventory_overview` |
| F12 | `audit_log` (canonical), `product_price_audit` (0002) |

Any new migration needs its rollback; 0006/0007 are the known exceptions.

## 7. AI agent integration

None at runtime. Development agents follow `AGENTS.md` (roles, handoffs,
guardrails). No feature depends on an agent or MCP server.

## 8. MCP usage

None. See `MCP.md` — MCP is documented as an optional development-time
integration point only.

## 9. External integrations

Supabase (all admin features), provider adapter (F9/live MoMo), Expo (F6 UI).
No email, SMS, storage or analytics providers are wired up.

## 10. UI/UX references

`DESIGN.md` for tokens, components, states and page layout; `PRD.md` §6 for the
screen list.

## 11. API endpoints per feature

| Feature | Endpoints |
| --- | --- |
| F1/F2/F3 | `POST /sync`, `GET /sync/pull` |
| F5 | `POST /devices`, till PIN check (local) |
| F6 | Supabase Auth + PostgREST reads |
| F7/F8 | `admin_create_hire_purchase_contract`, `admin_pay_hire_purchase_installment`, table reads |
| F9 | `admin_reconcile_mobile_money_payment` |
| F10 | `faults` table writes (supervisor), `process_approval_queues` |
| F11 | `GET /reports/*`, dashboard views |
| F14 | local `renderReceipt` |

## 12. File structure and organization

`src/domain/*` (rules), `src/db/*` (storage), `src/sync/*` (transport),
`src/ui/*` (screens), `server/src/main/kotlin/.../{api,db,domain,sync,payments,reports,security}`,
`supabase/migrations/*`, `admin-mobile/src/{app,lib,context}`.

## 13. Testing checklist per feature

- [ ] Domain unit tests cover the happy path and the limit/refusal path
- [ ] SQLite and PostgreSQL schemas stay aligned (`tests/schema.test.ts`)
- [ ] Migration applies on a clean database (`CleanReplayMigrationTest`)
- [ ] Authorization negative case (anon, plain admin) asserted at REST
- [ ] Money asserted as exact integers
- [ ] Error state visible in the UI

## 14. Dependencies

F1 → F2 → F3. F6 → F7/F8/F9/F10/F11 (all admin reads need RLS). F10 needs F9's
payment shape. F15/F16 depend on F1/F14.

## 15. Example user flows

**Sale (F1/F2).** Pin → add 2×Milo → cash → receipt → outbox pending → `/sync` →
applied → other till pulls.

**Reconciliation (F9).** Admin opens MoMo → reference `MOMO-123`, amount `10050`
→ RPC matches → `reconciled_at` set → retry returns the same result.

**Fault (F10).** Supervisor posts fault → escalates (queue row) → posts defect
(payment blocked) → denies with reason (payment unblocked) → closes.
