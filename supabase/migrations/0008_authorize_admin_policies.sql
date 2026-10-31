-- 0008_authorize_admin_policies.sql
-- Defense-in-depth: retarget every is_admin() RLS policy from TO public to
-- TO authenticated.
--
-- Why: `is_admin()` EXECUTE is revoked from anon/public (migration 0003/0004)
-- and granted only to authenticated + service_role. Policies written with the
-- implicit `TO public` therefore let Postgres evaluate an is_admin() call for
-- anonymous requests, which fails with `42501 permission denied for function
-- is_admin` instead of a clean RLS denial. Functionally anon is still denied
-- either way (fail-closed), but the policy roles should match the function's
-- grant surface so every caller reaches a proper RLS verdict.
--
-- Every policy expression (USING / WITH CHECK) is preserved verbatim; only the
-- TO role list changes. `profiles_self_read` (auth.uid() = id, no is_admin)
-- is intentionally NOT touched — it must keep applying to every signed-in user.
--
-- Apply order: after 0007. Do not apply to production without first running
-- it on a development/test project (see rollback file).

-- 0001_admin_foundation.sql ------------------------------------------------
alter policy profiles_admin_read      on public.profiles      to authenticated;
alter policy sales_admin_read         on public.sales         to authenticated;
alter policy sale_items_read          on public.sale_items    to authenticated;
alter policy payments_read            on public.payments      to authenticated;
alter policy products_read            on public.products      to authenticated;
alter policy stock_on_hand_admin_read on public.stock_on_hand to authenticated;
alter policy approvals_read           on public.approvals     to authenticated;
alter policy audit_admin_read         on public.audit_log     to authenticated;

-- 0002_product_management.sql ----------------------------------------------
alter policy categories_read_all      on public.product_categories   to authenticated;
alter policy categories_admin_all     on public.product_categories   to authenticated;
alter policy price_audit_admin_read   on public.product_price_audit  to authenticated;

-- 0005_customers_payroll_hirepurchase.sql -----------------------------------
alter policy customers_admin_all            on public.customers              to authenticated;
alter policy payroll_deductions_admin_all   on public.payroll_deductions     to authenticated;
alter policy hp_contracts_admin_all         on public.hire_purchase_contracts  to authenticated;
alter policy hp_payments_admin_all          on public.hire_purchase_payments   to authenticated;
