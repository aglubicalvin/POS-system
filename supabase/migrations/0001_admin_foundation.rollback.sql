-- Rollback for 0001_admin_foundation.sql
-- Run in reverse order; the canonical POS tables keep their data — only the
-- admin-app access layer is dropped.

drop view if exists low_stock_overview;
drop view if exists dashboard_sales_daily;

drop policy if exists audit_admin_read on audit_log;
drop policy if exists approvals_read on approvals;
drop policy if exists stock_on_hand_admin_read on stock_on_hand;
drop policy if exists products_read on products;
drop policy if exists payments_read on payments;
drop policy if exists sale_items_read on sale_items;
drop policy if exists sales_admin_read on sales;

alter table audit_log disable row level security;
alter table approvals disable row level security;
alter table stock_on_hand disable row level security;
alter table products disable row level security;
alter table payments disable row level security;
alter table sale_items disable row level security;
alter table sales disable row level security;

drop function if exists public.is_admin();
drop table if exists profiles;
