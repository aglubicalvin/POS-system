# Phase 4.2 — Product, Category & Selling-Price Management

Admin-app management screens on top of Phase 4.1's shared backend.

## Scope

- **Migration** `supabase/migrations/0002_product_management.sql` (+ rollback):
  `product_categories` (name/category mapping), `product_price_audit`
  (old/new price, who, why, when), `admin_product_catalog` view, and the
  authorized write path — `admin_update_product_price()` /
  `admin_set_product_category()` RPCs that validate the caller via
  `is_admin()` and record the audit row in the same transaction. The admin
  app holds **no UPDATE grant on `products`**; prices can only change
  through these RPCs.
- **Products screen** (`admin-mobile/src/app/(admin)/products.tsx`): search
  by name/SKU, category chips, low-stock markers, price edit modal that
  requires a reason, category assignment.
- **Pure validation contract** (`admin-mobile/src/lib/product-management.ts`,
  unit-tested in `admin-mobile/tests/product-management.test.ts`): decimal
  GHS → integer pesewas parsing, reason requirement, 64-char category clamp —
  mirrored server-side by the RPC for defense in depth.

## Known limitation (documented, not hidden)

The Kotlin server still owns catalog lifecycle from the till side; selling
prices changed here are authoritative on Supabase but are **not yet pushed
back to offline tills** — a future sync event should relay admin price edits
to devices, or device-side edits will win the next `product.upserted`
round-trip.

## How to verify

```bash
cd admin-mobile
npm test          # 21 tests incl. validation contract
npm run typecheck
```
