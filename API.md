# API — API Documentation

Two APIs serve this system:

1. **Device API** — Kotlin/Ktor server, used by tills. Base URL
   `http://<server>:8080`, no version prefix (single-tenant, versioned by deploy).
2. **Admin API** — Supabase PostgREST + RPCs, used by the admin mobile app and
   scripts. Base `https://<project-ref>.supabase.co`, REST root `/rest/v1`,
   auth `/auth/v1`.

Versioning strategy: the device API is additive-only; breaking changes are a new
path (e.g. `/sync/v2`) deployed alongside. Supabase migrations are immutable once
applied; changes ship as new numbered files.

## 1. Device API endpoints

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/health` | none | Liveness `{status:"ok",service:"business-store-server"}` |
| POST | `/devices` | optional `X-Enrollment-Secret` | Register a till, returns device id + bearer token |
| POST | `/sync` | device bearer | Apply a batch of events |
| GET | `/sync/pull` | device bearer | Relay other devices' events since cursor |
| GET | `/reports/daily` | device bearer | Daily sales for `from`,`to`,`deviceId` |
| GET | `/reports/stock` | device bearer | Stock levels |
| GET | `/reports/review-queue` | device bearer | Movements requiring review |
| GET | `/reports/discrepancies` | device bearer | Sales whose client total ≠ server total |
| POST | `/payments/mobile-money/initiate` | device bearer | Create/lookup a pending MoMo payment |
| POST | `/payments/mobile-money/webhook` | HMAC `X-Momo-Signature` | Provider callback (no device token) |
| GET | `/payments/mobile-money/{paymentId}` | device bearer | Payment status |

### POST /devices

```json
// request
{ "storeId": "store-accra-01", "name": "TILL-1", "model": "tablet", "deviceId": "optional-uuid" }
// 201
{ "deviceId": "uuid", "token": "raw-token-shown-once", "expiresAt": "2026-12-01T00:00:00Z" }
```

Rules: 401 when `POS_ENROLLMENT_SECRET` is set and the header does not match
(constant-time compare). The raw token is never stored — only its hash.

### POST /sync

```json
{ "events": [ { "eventId": "uuid", "deviceId": "uuid", "seq": 41,
  "eventType": "sale.created", "aggregateId": "sale-uuid", "payload": { } } ] }
```

```json
// 200
{ "results": [ { "eventId": "uuid", "status": "applied", "seq": 41 } ] }
```

- 403 if any event claims another `deviceId` (one till cannot forge another's
  movements).
- 422 `unknown product` / `invalid event payload` — the event is **not** claimed,
  so a corrected payload can be resent after the catalog syncs.
- Idempotent: re-sending an applied event returns `duplicate` and changes nothing.
- Ordering: per-device `seq`; gaps are buffered in `pending_events` until filled.

Event types: `sale.created`, `payment.updated`, `product.upserted`,
`user.upserted`, `goods_received.created`, `stock.adjusted`, `shift.*`,
`audit.appended` (see `contract/events.json`, generated).

### GET /sync/pull

Query: `since` = JSON map `{sourceDeviceId: lastSeq}` (URL-encoded), `limit` 1–1000
(default 200). Response `{ "events": [ envelope… ] }`, store-scoped and
authenticated. Invalid `since`/`limit` ⇒ 400.

### Reports

`GET /reports/daily?from=YYYY-MM-DD&to=YYYY-MM-DD&deviceId=…` →
`[{deviceId, day, salesCount, revenue, discounts, tax, refunds, grossProfit}]`.
Missing `from` ⇒ 400.

### Mobile money

```json
// initiate
{ "paymentId": "pay-uuid", "saleId": "sale-uuid", "amount": 10050,
  "msisdn": "233244000000", "provider": "mtn-momo" }
// 201 new / 200 existing
{ "paymentId": "pay-uuid", "status": "pending", "providerRef": "…", "created": true }
```

Webhook: raw body + `X-Momo-Signature` HMAC. `503` when `MOMO_WEBHOOK_SECRET` is
unset (refuse unsigned callbacks), `401` on bad signature, `409` on a conflicting
terminal state.

## 2. Admin API (PostgREST)

Every read is RLS-gated. Anonymous requests return **zero rows**, never data.

| Table/view | Method | Who |
| --- | --- | --- |
| `profiles` | SELECT | own row (any authenticated); all rows (admin) |
| `sales`, `sale_items`, `payments`, `products`, `stock_on_hand`, `approvals`, `audit_log`, `customers`, `payroll_deductions`, `hire_purchase_contracts`, `hire_purchase_payments`, `faults`, `approval_queues` | SELECT | admin (`is_admin()`); faults writes supervisor-only |
| the six views | SELECT | admin only; `security_invoker` |
| `product_categories`, `product_price_audit` | SELECT | admin |

Example:

```
GET /rest/v1/dashboard_sales_daily?select=day,revenue,gross_profit&order=day.desc
Authorization: Bearer <user access token>
apikey: <anon key>
```

Filters: `?column=eq.value`, `select=a,b`, `order=col.asc`, `limit=`, `offset=`.
Unfiltered `PATCH`/`DELETE` are refused by PostgREST (`21000`).

### RPCs (all `SECURITY DEFINER`, `search_path = ""`, admin-gated)

| Function | Signature | Effect |
| --- | --- | --- |
| `is_admin()` | `() → boolean` | `role='admin' AND is_active` |
| `is_supervisor()` | `() → boolean` | admin **and** `is_supervisor` |
| `admin_update_product_price` | `(text, bigint, text)` | Update price + write price audit |
| `admin_set_product_category` | `(text, text)` | Trim, upsert category |
| `admin_update_product_media` | `(text, text, text, text)` | description/image/thumbnail |
| `admin_reconcile_mobile_money_payment` | `(text, text, bigint)` | Reference + amount + expected; idempotent, refuses mismatch |
| `admin_create_hire_purchase_contract` | `(uuid, text, bigint, bigint, bigint, integer, date)` | Create contract + schedule |
| `admin_pay_hire_purchase_installment` | `(uuid)` | Mark installment paid, reduce balance |
| `process_approval_queues` | `(uuid)` | Process the caller's queue only |

```json
// POST /rest/v1/rpc/admin_reconcile_mobile_money_payment
{ "p_provider_ref": "MOMO-123", "p_payment_id": "pay-uuid", "p_amount": 10050 }
// 200 → { "matched": true, "reconciled_at": "…" }
// mismatch → error, row unchanged
```

Validation summary: amounts are positive bigints; references are matched exactly;
fault denials require `denial_reason`; fault transitions are guarded by trigger;
category strings are trimmed and non-empty; product ids must exist.

## 3. Auth API

- `POST /auth/v1/token?grant_type=password` — admin login.
- `POST /auth/v1/admin/users` / `DELETE /auth/v1/admin/users/{id}` — service role
  only (E2E seeding and cleanup).
- `POST /auth/v1/logout`, `POST /auth/v1/token?grant_type=refresh_token`.
- JWTs carry the subject; `auth.uid()` reads `request.jwt.claim.sub`.

## 4. Rate limiting, pagination

- Device API: **no rate limiting or body-size cap yet** (documented gap).
- PostgREST: platform defaults; admin app pages with `limit`/`offset`.
- `/sync/pull` is cursor-paginated; `/reports/*` is bounded by date range.

## 5. Webhooks

`POST /payments/mobile-money/webhook`. Requirements: HMAC over the **exact raw
body**, constant-time comparison, no device token, `503` when unconfigured,
`401` on mismatch, idempotent on replay. Payment initiation and provider
callbacks remain a separate milestone for live credentials.

## 6. File uploads

None. Product images are URLs (`image_url`, `thumbnail_url`), stored as text; the
app renders them remotely. There is no upload endpoint or storage bucket.

## 7. Errors

Device API: `{ "error": "message", "eventId": "optional" }` with 400/401/403/404/
409/422/500/503 as listed per endpoint. Supabase: `{code, message, details, hint}`
with Postgres SQLSTATE codes (`42501` permission denied, `21000` unfiltered
update, `23514` check violation, `P0001` raised transition error).
