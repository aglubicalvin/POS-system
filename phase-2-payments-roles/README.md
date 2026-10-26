# Phase 2 — Payment Methods & Role-Based Control

Layered on Phase 1 without changing its data model.

## Scope

- **Four payment methods at checkout**: `cash`, `mobile_money`, `card`,
  `credit` — `PaymentMethod` in `src/domain/payments.ts`, wired through
  `checkout()`, persisted per payment row with idempotency keys, and enforced
  server-side.
- **Role model** (`src/domain/permissions.ts`): cashier / manager / admin
  capability matrix — void, refund, discount, price override, stock
  adjustment, large-approval thresholds (5% cashier discount cap, >20%
  manager approval, >10 units stock approval).
- **Role-based till UI** (`src/ui/`): manager/admin action panels and sales
  report button appear only for the logged-in role; payment-method selection
  on checkout; method printed on the receipt.
- **Web API** (`src/ui/server.ts`): `/api/checkout` takes `paymentMethod`;
  `/api/reports/sales` for admins.

## How to verify

```bash
npm test        # covers discount/adjustment permission gates + payment flows
npm run demo
```
