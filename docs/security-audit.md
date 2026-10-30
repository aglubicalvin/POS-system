# Phase 4.3 security audit — customers, payroll, hire purchase, MoMo

Audit date: 2026-10-30. Scope: everything Phase 4.3 added to the admin
surface and the mobile-money workflow. Every claim below is backed by an
automated check named in Evidence, not by code reading alone.

## Threat areas and where they are enforced

| Area | Decision | Enforced by |
| --- | --- | --- |
| Customer data | `customers` is admin-only (RLS `for all` on `is_admin()`); the admin app has no read path for a non-admin and anon sees nothing | migration `0005` policies; `Phase43WorkflowTest > RLS hides protected rows…`, `> anonymous clients cannot reach the admin surface` |
| Payroll amounts | `payroll_deductions` admin-only; month/status indexes; a client cannot create deductions outside the RPC path | migration `0005` policies + `0006` indexes; same RLS tests; `admin` mobile writes go through authenticated RPCs only |
| Hire-purchase integrity | contract + sale + full installment plan are written inside ONE security-definer RPC; deposit payment, deductions and schedule are consistent; paying an installment is locked (`for update`) and idempotent by idempotency key | `0006` functions; `Phase43WorkflowTest > hire purchase writes one consistent sale…`, `> paying installments reduces the balance once and never twice`, `> an installment larger than the remaining balance is refused`, `> a refused contract leaves no partial records` |
| MoMo initiation | only the server (device-token API + provider webhook) creates/moves MoMo payments; the admin mobile app cannot confirm payments, only reconcile them | Kotlin `Routes.kt` routing; `MobileMoneyApiTest` (6 tests) |
| MoMo webhook | HMAC signature over the exact raw body with a constant-time compare; disabled (HTTP 503) without a configured secret; replay is a no-op; forged signature, wrong amount or unknown id cannot move anything | `MobileMoneyApiTest > a signed callback confirms once and a replay is a no-op`, `> a forged signature or a wrong amount cannot change the payment`, `> the webhook stays disabled without a configured secret` |
| MoMo reconciliation | `admin_reconcile_mobile_money_payment` never mutates status/amount/provider_ref; refuses pending payments, mismatched amounts and mismatched references; is idempotent on `reconciled_at` | migration `0007`; `Phase43WorkflowTest > reconciliation matches a confirmed payment and is idempotent`, `> reconciliation refuses mismatches without touching the payment`; E2E `scripts/e2e-supabase.ts` repeats the lifecycle over REST |
| Till-side MoMo | checkout leaves the attempt `pending`; initiation is refused (HTTP 503) without a provider; re-initiation never re-contacts the provider; ONLY a provider verdict transitions pending → confirmed/failed; a failed verdict is audited and synced home | `src/domain/momo.ts`; `tests/momo-till.test.ts` (7 tests) |
| Payment forgery by clients | authenticated sessions have no INSERT/UPDATE on `payments`; admin RPCs are the only write paths | `Phase43WorkflowTest > authenticated clients cannot write financial tables directly`; E2E `admin cannot insert/edit payment rows` |
| `is_admin()` hardening | SECURITY DEFINER, empty `search_path`, fully qualified names, EXECUTE revoked from `public`/`anon` | migration `0003`+`0004`; `AdminMigrationTest > is_admin recognizes only active admins`, `> is_admin is executable by authenticated only`, `> admin rpcs are pinned to an empty search_path…` |
| Device enrollment | `POS_ENROLLMENT_SECRET` gates device registration; unset secret prints a startup WARNING and is the documented operational responsibility | `Application.kt`; Kotlin test suites exercise both paths |
| Migration safety | 0005–0007 apply in order over the canonical schema; canonical POS tables only GAIN columns (never lose constraints); admin objects tested against the migrations that create them, not assumptions | `AdminMigrationTest` (5 tests, embedded Postgres + Supabase shim), `Phase43WorkflowTest > migrations 0005 to 0007 create every object they advertise`, `> migrations only add columns to the canonical POS tables`, `tests/schema.test.ts` |

## Test evidence

- Kotlin: `gradlew check` — 62 tests, 0 failures (`AdminMigrationTest` 5,
  `Phase43WorkflowTest` 17, `MobileMoneyApiTest` 6, plus the pre-existing suites).
- TypeScript till/mobile domain: `npx vitest run` — 108 passed, 1 skipped,
  including `tests/momo-till.test.ts` (7 new) and `tests/ui.test.ts`.
- admin-mobile: `npx vitest run` — 29 passed, including the Phase 4.3 screens'
  pure-module tests.
- Live project (optional, env-gated): `npm run e2e:supabase` — runs the same
  authz + reconciliation lifecycle over real Supabase REST, then deletes
  everything it created. Refuses to run without explicitly set credentials so
  production is never touched by accident.

## Residual risks (accepted, documented)

1. **Provider credentials** stay with the vendor adapter implementation
   (`MomoProvider` on the till host, `MOMO_WEBHOOK_SECRET`/adapter on the
   Kotlin server). No credential ever ships in the Expo bundle — enforced only
   by convention; review before store submission.
2. **`POS_ENROLLMENT_SECRET` unset** means unauthenticated device registration.
   The server warns loudly; treat it as a bring-your-own-ops requirement.
3. **Phone-number capture** is plain text in `customers.phone`. No PII
   minimization is implemented (no truncation); Ghana data-protection
   obligations are a business decision, not enforced here.
4. **Legacy till payments** for cash/card are confirmed at the till by design
   (the cashier took the money). MoMo is the only server-confirmed method.
   Void/refund flows reusing `payments` remain a roadmap item.
5. **Reconciliation provider statement input** is trusted text from the admin
   app. The RPC validates amount/reference, but the *statement itself* must
   come from a real provider export; nothing verifies the operator's source.
