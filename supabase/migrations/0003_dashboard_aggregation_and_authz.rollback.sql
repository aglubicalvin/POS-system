-- Rollback for 0003_dashboard_aggregation_and_authz.sql
-- Restores the definitions 0001 left behind. NOTE: this reinstates the
-- per-line fan-out bug in dashboard_sales_daily and the wide-open EXECUTE
-- grant on is_admin(). Only run it to unwind the migration deliberately.

revoke all   on public.dashboard_sales_daily from authenticated;
revoke all   on public.low_stock_overview   from authenticated;
grant  select on public.dashboard_sales_daily to anon;
grant  select on public.low_stock_overview   to anon;

create or replace view public.dashboard_sales_daily
with (security_invoker = true) as
select
  (s.created_at at time zone 'UTC')::date                                      as day,
  count(*) filter (where s.kind = 'sale')                                      as sales_count,
  coalesce(sum(s.total)          filter (where s.kind = 'sale'), 0)            as revenue,
  coalesce(sum(s.discount_total) filter (where s.kind = 'sale'), 0)            as discounts,
  coalesce(sum(s.tax_total)      filter (where s.kind = 'sale'), 0)            as tax,
  coalesce(sum(s.total)          filter (where s.kind = 'refund'), 0)          as refunds_total,
  coalesce(sum(si.line_total - si.tax - si.quantity * p.cost_price)
           filter (where s.kind = 'sale'), 0)                                  as gross_profit
from public.sales s
left join public.sale_items si on si.sale_id = s.sale_id
left join public.products p    on p.product_id = si.product_id
group by 1;

create or replace function public.is_admin() returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1 from profiles
    where id = auth.uid() and role = 'admin' and is_active
  );
$$;

revoke all on function public.is_admin() from anon, service_role;
grant execute on function public.is_admin() to public;
