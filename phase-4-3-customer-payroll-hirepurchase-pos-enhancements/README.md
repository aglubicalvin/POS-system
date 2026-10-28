# Phase 4.3 — Customer Payroll Deductions, Hire Purchase, Payments, Inventory & POS Enhancements

This phase builds on 4.1 (admin foundation + auth/RLS) and 4.2 (product/category/price management) to extend both the **Administrator Mobile App** and the **Cashier Till (web/POS)** with the features requested. Where functionality already exists, we improve upon it; where missing, we add it.

## Goals

- Track customers and their purchases with **payroll deductions** (monthly reports/history)
- Identify customers by **payment method** (Cash/Ghana Card) vs **payroll deductions** using **company staff numbers**
- Add a **tab/view for products bought on Hire Purchase** (name, description, quantity, retail price, duration in months, with countdown/`numericText`-style display)
- **Payment integration** (Mobile Money / MoMo) — extend existing payment model
- **Inventory tracking** improvements (beyond current stock_on_hand/movements)
- **User login system** with separate logins for **Administrator** (username/password) and **Employee/Cashier** (separate auth flow)
- **Display products with images**
- **Cart functionality**: add items, adjust quantities (+/-), remove items, process checkout, generate receipt (improve existing till cart/checkout)

## Current state vs required

| Feature | Current | Action |
| --- | --- | --- |
| Customers | Minimal `customer_ref` on sales; no dedicated customers table | **Add** `customers` table (staff number, company, payment method preference, contact, etc.) linked to sales |
| Payroll deductions | Not implemented | **Add** payroll deduction tracking (deduction schedules, monthly reports, customer history by staff number) |
| Payment method identification (cash/Ghana Card vs payroll) | Payments have method `cash\|mobile_money\|card\|credit` | **Extend** to explicitly track `payment_channel` / `payment_type` (cash/ghana_card/payroll_deduction) and link to customer/staff number |
| Hire Purchase tab | Not implemented | **Add** hire purchase contracts (product, qty, retail price, duration months, deposit, installments, status, countdown) + UI tab |
| MoMo payment integration | `mobile_money` exists in enum but no provider integration/reconciliation UI | **Improve/extend**: provider config, payment initiation, status tracking, reconciliation hooks |
| Inventory tracking | `stock_on_hand`, `stock_movements`, review queue exist | **Improve**: low stock alerts, reorder points, valuation, product images, better admin visibility |
| Separate logins (Admin vs Employee) | Admin app uses Supabase auth + `profiles`/`is_admin()`; Till uses PIN auth for employees/cashiers via device binding | **Unify/clarify**: keep separate flows but document and harden; ensure admin login is username/password distinct from employee PIN login |
| Product images | Products table has no image fields | **Add** image URLs/storage (thumbnail/full) |
| Cart (add/adjust/remove/checkout/receipt) | Till has cart in UI + `checkout()` works; admin app has no cart/checkout flow | **Improve** in till; **add/evaluate** in admin app if needed for admin POS features |

## Schema changes (migrations)

Create migrations 0005+ under `supabase/migrations/` (and ensure parity where needed):

- `0005_customers_payroll_hirepurchase.sql`
  - `customers` (customer_id, staff_number UNIQUE, full_name, company, phone, email, payment_method_preference, is_active, ...)
  - `payroll_deductions` (deduction_id, customer_id, sale_id, amount, deduction_month, status, processed_at, ...)
  - `hire_purchase_contracts` (contract_id, customer_id, sale_id, product_id, quantity, retail_price, deposit, duration_months, installment_amount, remaining_balance, status, start_date, end_date, ...)
  - `hire_purchase_payments` (hp_payment_id, contract_id, amount, due_date, paid_at, status)
  - Extend `sales` to link `customer_id`, add `payment_channel` ('cash'|'ghana_card'|'payroll_deduction'|'momo')
  - Extend `products` to add `image_url`, `thumbnail_url`, `description` (if not present)
- `0006_inventory_improvements.sql` (if needed) - reorder points, valuation views
- Update RLS policies for admin access

Also update server schema (`server/src/main/resources/db/schema.sql`) and TS schema (`src/db/schema.ts`) to maintain parity where applicable, or document differences.

## Implementation plan

### 1) Unify migrations (do now)
- Create unified migration tracking approach
- Have Kotlin server use migration runner (apply migrations from unified location, track in `schema_migrations`)
- Update TS side to be migration-aware where feasible
- Keep schema parity tests passing

### 2) Wrapper decision (do now)
- Reverted Gradle wrapper to 8.14.3 (was changed to 9.7.1). Commit this decision or keep as-is. Current state: reverted to original.

### 3) Phase 4.3 features
- **Customers & Payroll**: Admin screens for customers, staff number lookup, payroll deduction reports by month, customer purchase history
- **Hire Purchase**: New tab in admin app (and/or till) showing HP contracts with countdown/duration display
- **Payments/MoMo**: Extend payment UI, add provider configuration hooks
- **Inventory**: Enhanced inventory tracking UI, alerts
- **Auth**: Document/separate login flows clearly (Admin vs Employee)
- **Products/Images/Cart/Receipt**: Ensure product images display, cart fully functional, receipt generation solid

## Testing
- Add tests for payroll deductions, hire purchase calculations, customer identification
- Extend admin app tests
- Extend server/TS tests as needed

## Notes
- "numericText(countsDown:)" mentioned - in React Native/SwiftUI context; in RN we can implement similar animated numeric display
- Ghana Card mentioned as payment method
- Company staff numbers for identification
