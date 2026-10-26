# Roadmap

What the brief asks for that this repository does **not** yet contain. Nothing
here is stubbed or faked in code — it is simply absent.

## 1. Product surface (largest gap)

- **Till UI first slice.** A cashier web app exists (`npm run till`): PIN login
  with device binding and lockout, catalog grid with on-hand stock, cart, and an
  atomic cash checkout that drives the real `checkout()` — no business rules in
  the UI layer. Still missing: voids/refunds and receipts on screen, mobile-money
  payment screens, manager override prompts, and goods-received/inventory
  screens (the domain calls are ready; `scripts/demo.ts` shows the sequence).
- **No PWA / Capacitor shell.** The agreed target is a PWA packaged for Android
  via Capacitor. The device database is `node:sqlite`, which needs an
  Android-capable SQLite binding (or a WASM/OPFS strategy) once the till stops
  running on Node.
- **No admin UI** for the server. The reporting endpoints exist and are
  authenticated, but there is no screen and no role model on the server side
  beyond device tokens.

## 2. Server hardening

- **No production deployment.** The server has been verified against PostgreSQL
  18.6 via embedded Postgres, which is a real PostgreSQL binary — but it has never
  run against a managed instance, with no connection-string management, TLS to
  the database, backups, or a tested restore.
- **No versioned migrations.** `Schema.apply` runs the DDL with
  `CREATE TABLE IF NOT EXISTS` at startup. That is fine for a first deploy and
  useless for evolving a live schema; a real migration tool should replace it.
- **No rate limiting or request size caps** on `/sync`.
- **Enrollment secret is a shared string**, not per-admin identity. Reasonable
  for a pilot, not for scale.
- **`payment.updated` only updates an existing payment.** Nothing creates payment
  rows server-side, so a device whose payment rows never sync leaves the server
  unable to reconcile.
- **Supplier / `goods_received` headers are not created server-side.** Only the
  stock movements are applied, because supplier and GRN data is not in any event
  yet. Server-side received-vs-sold reporting therefore reads movements, and
  `goods_received` stays empty. A catalog/supplier sync event is needed.

## 3. Money and payments

- **No real provider integration.** No aggregator chosen or wired up (see
  [ghana-compliance.md](ghana-compliance.md)). The state machine, idempotency
  keys and reconciliation hook those integrations need are in place.
- **No settlement-file ingestion.** `reconcilePayment` takes an already-parsed
  statement entry; nothing downloads or parses a provider statement.

## 4. Receipts

- **Text layout only.** `renderReceipt` produces the 32-column body an ESC/POS
  printer consumes, but there is no ESC/POS byte encoder, no printer discovery,
  and no cash-drawer kick.
- **No PDF, SMS, or email delivery.**

## 5. Security

- **Argon2id wired on the device, not the server.** PINs and passwords hash with
  a real Argon2id (`node:crypto` on Node >= 24.7, default 19 MiB / 2 passes /
  1 lane per OWASP), and old scrypt hashes still verify and are upgraded lazily
  on each successful PIN login. Untested at scale on a low-end Android device:
  tunables live in one struct (`DEFAULT_PARAMS`) for exactly that. The Kotlin
  server stores no password hashes at all yet, so it has no login of its own.
- **No MFA challenge flow.** `users.mfa_enabled` is stored and set for admins in
  fixtures, but nothing prompts for a second factor.
- **No TLS.** Ktor is started plain; TLS termination and HSTS are deployment work.
- **No OS keystore.** `DeviceService.register` and the device's `registerDevice`
  return a raw token intended for Android Keystore / Windows DPAPI. Nothing
  stores it there yet.
- **No OWASP security test** and no load test.
- **Both sync halves are connected, catalog included.** Server→device:
  `GET /sync/pull` (store-scoped, per-source cursors, authenticated) relays
  ingested envelopes field-complete, pinned cross-language by
  `contract/pull.json` (PullApiTest relays over real HTTP; the TS suite parses
  the same file through the real dispatcher). Device→server: the Kotlin server
  is the sole applier, and the device now ENQUEUES `product.upserted` from real
  `createProduct`/`updateProduct` writes and `user.upserted` from `createUser`
  (per-edit idempotency keys; the row write and the event commit together).
  The server applies those upserts into its authoritative `products`/`users`
  tables — with the same validation as the receiving device: hashed secrets
  only and store-global users (no device binding) — and relays them verbatim to
  the other tills. Remaining gap: the Till UI has no manager catalog-edit
  screen yet (the domain calls and the sync wiring they produce exist), and
  the contract fixture still covers only the sale-side event types.

## 6. Operations

- No signed builds or update channel.
- No encrypted backups or tested restore. The schema is built for it —
  `rebuildStockOnHand` (device) and the movement ledger let a restore be verified.
- No crash monitoring.
- No single-shop pilot.

## 7. Cross-language maintenance

- `contract/events.json` is a generated artifact. It must be re-emitted
  (`npm run contract:emit`) whenever an event payload changes, or the Kotlin
  contract test fails against a stale fixture. That failure is intentional, but
  it is a manual step that a build hook should own.

## Suggested next increments, in order

1. **Till UI** (cashier + checkout) on top of the existing domain calls, plus the
   Capacitor shell and an Android SQLite binding.
2. **Versioned migrations + a deployed PostgreSQL** with TLS and backups.
3. **MFA challenge, server-side login, and per-admin enrollment**
   to close the remaining security items (Argon2id is done on the device).
4. **ESC/POS encoder** behind `renderReceipt`.
5. **Supplier/GRN sync events** so server-side reconciliation is complete.
6. **GRA E-VAT integration**, pending the confirmations in the compliance doc.
7. **OWASP test, load test, then the pilot.**
