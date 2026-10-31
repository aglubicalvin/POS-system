-- 0008_authorize_admin_policies.rollback.sql
-- Reverts migration 0008_authorize_admin_policies.sql: restores every
-- is_admin() policy to its original implicit `TO public` role list.
-- Expressions were not modified by 0008, so nothing else needs restating.

-- 0001_admin_foundation.sql ------------------------------------------------
alter policy profiles_admin_read      on public.profiles      to public;
alter policy sales_admin_read         on public.sales         to public;
alter policy sale_items_read          on public.sale_items    to public;
alter policy payments_read            on public.payments      to public;
alter policy products_read            on public.products      to public;
alter policy stock_on_hand_admin_read on public.stock_on_hand to public;
alter policy approvals_read           on public.approvals     to public;
alter policy audit_admin_read         on public.audit_log     to public;

-- 0002_product_management.sql ----------------------------------------------
alter policy categories_read_all      on public.product_categories   to public;
alter policy categories_admin_all     on public.product_categories   to public;
alter policy price_audit_admin_read   on public.product_price_audit  to public;

-- 0005_customers_payroll_hirepurchase.sql -----------------------------------
alter policy customers_admin_all            on public.customers              to public;
alter policy payroll_deductions_admin_all   on public.payroll_deductions     to public;
alter policy hp_contracts_admin_all         on public.hire_purchase_contracts  to public;
alter policy hp_payments_admin_all          on public.hire_purchase_payments   to public;
