# PRD — Business Store POS

## 1. Problem statement and value proposition

Small Ghanaian retailers run on cash and mobile money, often with unreliable
connectivity and a single shared Android tablet as the till. Paper receipts and
spreadsheets cannot answer "what sold today, what is my profit, who owes me on
hire purchase, and did the MoMo payment actually land?".

Business Store is an **offline-first point-of-sale** with an **authoritative
server** and an **administrator mobile app**:

- the till keeps selling when the network is down (local SQLite + outbox),
  then syncs deterministically with idempotent, ordered events;
- the Kotlin server is the single source of truth (PostgreSQL), so two tills can
  never disagree about stock, price or a payment;
- the admin app reads through Supabase with row-level security, so a cashier's
  phone cannot see payroll, customers or margins.

Value proposition: **sell offline, reconcile online, and prove the numbers**.

## 2. Target users

| User | Context | Needs |
| --- | --- | --- |
| Cashier | Behind the counter, one hand free, spotty data | Fast PIN login, tap-to-add catalog, cash/MoMo checkout, receipt |
| Manager / supervisor | Same store, responsible for stock and discounts | Price/stock overrides with approval, shift control, fault reporting |
| Owner / administrator | Away from the shop, phone in hand | Revenue and profit by period, low stock, customers, payroll, hire purchase, MoMo reconciliation |
| Support engineer | Debugging a store | Sync status per device, audit log, discrepancy queue |

## 3. Core journeys

**J1 — Sell a product offline.** Cashier logs in with PIN → adds items →
checkout → receipt prints/shows → sale is queued in the outbox → syncs later;
server reprices against the authoritative catalog and flags discrepancies.

**J2 — Take a mobile-money payment.** Cashier selects MoMo → initiates against
the provider adapter → provider callback/webhook confirms → payment row is
`confirmed` server-side; the till never marks a payment successful on its own.

**J3 — Reconcile a provider statement.** Admin opens the MoMo screen, pastes the
provider reference and amount → `admin_reconcile_mobile_money_payment` matches a
confirmed payment, is idempotent on retry, and refuses amount/reference
mismatches without mutating the row.

**J4 — Manage catalog and price.** Admin edits a product's price, category or
media from the phone → RPC writes the change **and** an audit row → tills pull
the update on next sync.

**J5 — Hire purchase.** Admin creates a contract for a customer (staff number,
product, duration, deposit) → installments are generated → paying an installment
reduces `remaining_balance`.

**J6 — Payroll deduction.** Admin records a deduction against a customer for a
month; the customer summary shows total purchased, total deducted, last purchase.

**J7 — Report a fault.** Supervisor posts a fault against a sale/payment →
escalates for approval → posting blocks the linked payment → denial or resolution
unblocks it. Ordinary admins cannot perform these transitions.

**J8 — Inventory check.** Admin sees low-stock and inventory value; large stock
adjustments require an approval record.

## 4. Features and functional requirements

| ID | Feature | Requirement |
| --- | --- | --- |
| F1 | Offline checkout | Sale completes with no network; totals computed in the domain layer, never in the UI |
| F2 | Deterministic sync | Device→server batches are idempotent (unique event id), ordered per device (`seq`), gaps buffered |
| F3 | Catalog sync | Server relays ingested catalog/user changes to other tills verbatim |
| F4 | Money integrity | All amounts integer **pesewas**; no floats anywhere in the money path |
| F5 | Auth: till | PIN bound to the device, Argon2id hashing, lockout after repeated failures |
| F6 | Auth: admin | Supabase email/password session in secure storage; every table read is RLS-gated by `is_admin()` |
| F7 | Customers & payroll | Customer records, payroll deductions, purchase summary |
| F8 | Hire purchase | Contracts, installment schedule, balances, overdue display status |
| F9 | MoMo reconciliation | Admin RPC, idempotent, refuses mismatches, records `reconciled_at` |
| F10 | Faults | Lifecycle `reported → with_approvals → posted|denied → closed`, supervisor-only writes, payment blocking side effect |
| F11 | Reporting | Daily sales, stock levels, review queue, price discrepancies, dashboard roll-ups |
| F12 | Auditability | Hash-chained `audit_log`; price/category changes write `product_price_audit` |
| F13 | Approvals | Over-limit discounts/stock adjustments persist an `approvals` row rather than a boolean |
| F14 | Receipts | 32-column text receipt for ESC/POS, plus an on-screen detailed receipt |

## 5. Non-functional requirements

| Area | Requirement |
| --- | --- |
| Offline | Full sell flow without network; queued writes survive app restart |
| Performance | Checkout is a local transaction (no network on the critical path) |
| Correctness | Server re-computes totals; client totals are advisory and discrepancies are flagged |
| Security | RLS on every client-reachable table; anon reaches nothing; `SECURITY DEFINER` functions pin `search_path = ""` |
| Availability | Server is stateless behind PostgreSQL; till degrades gracefully, admin app is read-mostly |
| Data protection | No service-role key in the mobile bundle; secrets only in env/secret store |
| Compliance | VAT/tax fields carried per line (`tax_rate_bp`), GRA E-VAT integration still out of scope |
| Portability | Single-binary Kotlin server (JVM 21) + PostgreSQL; Expo app for Android/iOS/web |

## 6. Pages and screens

**Till (web, `npm run till`)**
1. Login — cashier select + PIN, error line, lockout messaging.
2. Sell — catalog grid, cart, totals, payment method, checkout, receipt dialog.
3. Manager actions — price override, large stock adjustment, shift approval.
4. Admin actions — users, devices, reports.
5. Sales report (dialog/route).

**Admin mobile (Expo, `admin-mobile/`)**
1. Login (email/password).
2. Dashboard — today/week/month revenue, profit, discounts, tax, refunds, sales count, low stock.
3. Products & prices — list, edit price/category/media.
4. Customers & payroll.
5. Hire purchase — contracts and installments.
6. Inventory — quantity, value, low stock.
7. MoMo payments — reconciliation.
8. POS preview (read-only demo of the till surface).

## 7. Roles and permissions

| Capability | Cashier | Manager | Supervisor (admin + flag) | Admin |
| --- | --- | --- | --- | --- |
| Sell, receipt | yes | yes | yes | yes |
| Discount ≤ 5% unaided | yes | yes | yes | yes |
| Discount > 20% | approval | approval | approval | approval |
| Price override / large stock adjustment | no | yes | yes | yes |
| Read sales/products/stock/payments/audit | on till only | on till only | yes | yes |
| Customer, payroll, hire-purchase data | no | no | yes | yes |
| Fault post/escalate/deny/close | no | no | **yes only** | read only |
| Price/category/media writes | no | no | yes | yes |

Enforced in the database (`is_admin()`, `is_supervisor()`, RLS policies,
transition triggers), not in the UI.

## 8. Data requirements

- Canonical POS schema: 21 tables (devices, users, products, suppliers, GRNs,
  stock ledger, sales, payments, shifts, approvals, audit log, sync state).
- Admin extensions: customers, payroll deductions, hire-purchase contracts and
  installments, approval queues, faults, product categories/media, price audit.
- Money: `BIGINT` pesewas; quantities `BIGINT`; tax as basis points.
- Retention: audit log append-only; PII retention policy **undecided** (see
  `docs/production-readiness.md` §4.4).

## 9. Integrations

| Integration | State |
| --- | --- |
| Supabase (Postgres + Auth + REST/PostgREST) | In use, migrations 0001–0009 |
| Mobile-money provider (MTN/Telecel via an aggregator) | Adapter + HMAC webhook implemented; **no live provider credentials** |
| GRA E-VAT | Out of scope, documented in `docs/ghana-compliance.md` |
| ESC/POS printer | Text layout only; no byte encoder |

## 10. Loading, empty and error states

| Surface | Loading | Empty | Error |
| --- | --- | --- | --- |
| Till catalog | Local read, effectively instant | "No products" hint | Checkout error line, `role="alert"` |
| Admin dashboard | `ActivityIndicator` | "No sales recorded in this window." | Red error text; pull-to-refresh retries |
| Products / customers / inventory lists | Spinner | Empty-list message | Error text with retry |
| MoMo reconciliation | Button disabled while running | "No payments to reconcile" | Server error surfaced verbatim (e.g. "reference mismatch") |
| Auth gate | Splash/loading | n/a | Redirect to login |

## 11. MVP scope

**In scope (delivered):** F1–F14 on the till + admin app, migrations 0001–0009,
RLS, reconciliation, faults, dashboard, hire purchase, payroll, catalog
management, admin E2E (43 checks), clean replay + rollback tests.

**Out of scope (documented, not stubbed):** live payment-provider credentials,
ESC/POS byte encoding, printed/PDF/SMS receipts, MFA challenge, server-side
login, TLS termination, GRA E-VAT, load/OWASP testing, production deployment.

## 12. Success criteria

| Criterion | Target | Evidence |
| --- | --- | --- |
| Clean install | 0001–0009 apply with no manual step | PASS on disposable project |
| Till regression | all unit tests green | 109 passed / 1 skipped (15 files) |
| Server regression | all Kotlin tests green | full suite PASS (11 classes) |
| Admin authorization | 43 REST checks pass incl. negatives | 43/43 PASS |
| Data integrity | no orphan rows, checksums stable | 0 orphans, checksums match |
| Dev parity | dev matches verified fingerprint | rebuilt and verified |
| Production readiness | all release gates closed | **NO-GO** (see `docs/production-readiness.md`) |
