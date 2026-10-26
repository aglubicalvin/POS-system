-- Rollback for 0002_product_management.sql
-- Run after 0001_rollback only if the whole admin access layer is being
-- dismantled; otherwise run this file alone to remove Phase 4.2 structures.

drop view if exists admin_product_catalog;
drop function if exists public.admin_set_product_category(text, text);
drop function if exists public.admin_update_product_price(text, bigint, text);

drop table if exists product_price_audit;
drop table if exists product_categories;

-- 0001 left products SELECT-only for admins; nothing to re-disable here
-- because 0002 never granted writes on products directly.
