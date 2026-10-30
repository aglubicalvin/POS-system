# MoMo provider: sandbox recommendation

The architecture is fixed (`docs/security-audit.md`): the till initiates and
polls only, the Kotlin server verifies signed provider webhooks, Supabase
stores the authoritative payment records, and admin-mobile reconciles. What is
missing for "production-ready" is a real provider behind the `MomoProvider`
seam and the Kotlin webhook verifier, exercised in that provider's sandbox.

## Recommendation

**Primary yardstick: Paystack.** (the closest match to this codebase's design)

- Ghana mobile money is a first-class channel (MTN, Telecel, AirtelTigo) with
  instant, self-serve test keys — no partner agreement needed for the sandbox.
- Its webhooks are HMAC-signed over the raw body (HMAC-SHA512, header
  `x-paystack-signature`), the same trust model this repo's Kotlin verifier
  already enforces (ours is SHA-256; the adapter maps the provider's algorithm
  onto the same "only a correctly signed callback may change a payment" rule).
- The provider callback carries `reference` (our idempotency key) and the
  charged amount, which maps directly onto the `pending → confirmed` transition
  and the reconciliation RPC (`admin_reconcile_mobile_money_payment`).

**Second: MTN MoMo direct Collections** (`momodeveloper.mtn.com`) —
the right long-term answer for direct-operator integration, wrong yardstick
first:

- Sandbox is self-serve and has outcome-simulating test numbers, but its
  callbacks are documented-unreliable by design (deliberate approval delays,
  silent delivery failures), which would slow down E2E red/green cycles.
- Callbacks carry no HMAC by default; trust comes from HTTPS + the
  configured callback host, so our webhook secret would be validation theater.
  The correct MTN pattern is our existing one anyway: poll
  `GET /collection/v1_0/requesttopay/{reference-id}` — exactly the
  pull-verification the till already implements.
- Ghana production access requires a signed partner agreement (2–4 weeks
  procurement) before any of it can be exercised end-to-end.

**Not a yardstick:** Flutterwave covers MTN/Telecel and auto-authorizes in test
mode, but its Ghana MoMo flow routes the customer through a hosted checkout
page (OTP + redirect), not a till-appropriate STK prompt.

## What each provider needs from this codebase

| Concern | Till (`MomoProvider`) | Kotlin webhook | Reconciliation |
| --- | --- | --- | --- |
| Paystack | `POST /charge` (or checkout host) with `reference` = payment idempotency key, amount in kobo-first GHS minor units, channel `mobile_money`; poll `GET /transaction/verify/:reference` at the status poll | verify HMAC-SHA512 of raw bytes against `x-paystack-signature`; treat `success` → confirmed, others leave the row pending per the dual-confirmation rule | provider ref = transaction's `tr_reference` (`flw`/`ps` prefix conventions aside) |
| MTN direct | provision API user + key in sandbox, POST `requesttopay` with `X-Reference-Id` = idempotency key, poll `requesttopay/{id}` | no HMAC — Kotlin accepts callbacks only from the configured host and still re-verifies by polling before trusting the verdict | provider ref = MTN's `financialTransactionId` |

## Sandbox execution checklist (needs credentials, run by a human)

1. `SUPABASE_URL` + `SUPABASE_SERVICE_ROLE_KEY` (+ optional `SUPABASE_DB_URL`),
   pointing at an isolated test project — `npm run e2e:supabase` refuses to run
   against anything it cannot verify as a test project, and tells you "SKIPPED".
2. Paystack **test** secret key env for the provider adapter (never commit it);
   the webhook target must be a routable HTTPS URL for the Kotlin server, so
   use a tunnel (e.g. `ngrok`) during the test.
3. Drive the full journey manually once: till checkout → initiate → approve
   (test mode auto-answers the charge) → webhook lands at Kotlin → till poll
   shows `confirmed` → admin-mobile reconciles the payment against the
   provider statement. The automated journey in `MobileMoneyApiTest` covers the
   same steps against the fake provider; this run validates the real one.

## Done means

Provider sandbox transaction confirmed through the webhook, recorded by the
Kotlin server, present in Supabase, reconciled in admin-mobile — and the
existing 109 Node tests, 29 admin-mobile tests and the Kotlin suite still green.
