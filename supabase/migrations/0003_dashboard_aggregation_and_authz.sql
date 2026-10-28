-- ------------------------------------------------------------------
-- 0003_dashboard_aggregation_and_authz.sql
--
-- Two corrections to the Phase 4.1 layer, both applied with CREATE OR
-- REPLACE so 0001_stays the historical record and this file is what the
-- database actually runs:
--
--   1. dashboard_sales_daily multiplied every sales figure by the number of
--      LINE ITEMS on the ticket. Joining sale_items into the same grouped
--      query fanned each sale row out (1 sale x 3 items => sales_count 3,
--      revenue 3x). Sale-level money is now aggregated from `sales` alone;
--      the per-line cost-of-goods sum lives in a separate CTE keyed by day
--      and is joined back once per day.
--
--   2. public.is_admin() ran with `search_path = public` and inherited
--      EXECUTE for PUBLIC (hence anon). It is the single gate behind every
--      admin RLS policy, so it is now pinned to an EMPTY search_path with
--      every name schema-qualified, and EXECUTE is granted to authenticated
--      (and service_role) only.
-- ------------------------------------------------------------------

-- ------------------------------------------------------------------
-- 1. Authorization helper: hardened
-- ------------------------------------------------------------------
-- Pinned to an empty search_path so no caller-controlled schema can shadow
-- `profiles` or `auth.uid()`; SECURITY DEFINER is what lets it read the
-- profiles row of the calling user while bypassing RLS on profiles.
create or replace function public.is_admin() returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.profiles
    where id = auth.uid()
      and role = 'admin'
      and is_active
  );
$$;

-- Only signed-in sessions may evaluate the gate. PUBLIC/anon lose the
-- default grant, so an anonymous request against an admin table now fails
-- loudly with permission denied instead of being silently filtered to
-- zero rows.
revoke all on function public.is_admin() from public;
revoke all on function public.is_admin() from anon;
grant execute on function public.is_admin() to authenticated, service_role;

-- ------------------------------------------------------------------
-- 2. Dashboard roll-up: no fan-out
-- ------------------------------------------------------------------
-- sales_daily: one row per UTC day, money taken from `sales` only, so a
-- ticket's totals are counted exactly once regardless of how many lines it
-- has. profit_daily: the cost side needs the line items, so it is summed
-- separately (once per line) and folded in on the day key.
create or replace view public.dashboard_sales_daily
with (security_invoker = true) as
with sales_daily as (
  select
    (s.created_at at time zone 'UTC')::date                         as day,
    count(*) filter (where s.kind = 'sale')                         as sales_count,
    coalesce(sum(s.total)          filter (where s.kind = 'sale'), 0) as revenue,
    coalesce(sum(s.discount_total) filter (where s.kind = 'sale'), 0) as discounts,
    coalesce(sum(s.tax_total)      filter (where s.kind = 'sale'), 0) as tax,
    coalesce(sum(s.total)          filter (where s.kind = 'refund'), 0) as refunds_total
  from public.sales s
  group by 1
),
profit_daily as (
  select
    (s.created_at at time zone 'UTC')::date                         as day,
    coalesce(sum(si.line_total - si.tax - si.quantity * p.cost_price), 0) as gross_profit
  from public.sales s
  join public.sale_items si on si.sale_id = s.sale_id
  join public.products p    on p.product_id = si.product_id
  where s.kind = 'sale'
  group by 1
)
select
  d.day,
  d.sales_count,
  d.revenue,
  d.discounts,
  d.tax,
  d.refunds_total,
  coalesce(g.gross_profit, 0) as gross_profit
from sales_daily d
left join profit_daily g on g.day = d.day;

-- Explicit read grants: RLS (is_admin()) still decides which rows a caller
-- sees; this only makes the views readable by signed-in sessions at all.
-- Anonymous callers get nothing.
grant select on public.dashboard_sales_daily to authenticated;
grant select on public.low_stock_overview   to authenticated;
revoke all on public.dashboard_sales_daily from anon;
revoke all on public.low_stock_overview   from anon;
