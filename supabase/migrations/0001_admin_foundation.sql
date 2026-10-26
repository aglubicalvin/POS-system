-- ------------------------------------------------------------------
-- 0001_admin_foundation.sql
--
-- Phase 4.1: shared-backend foundation for the administrator mobile app.
-- Runs against the SAME project/database the Kotlin POS server uses (the
-- canonical POS schema lives in server/src/main/resources/db/schema.sql and
-- is loaded by the server at startup — apply this migration AFTER the server
-- has created the canonical tables).
--
-- Principle: every permission check lives in the database. The mobile admin
-- app succeeds at reading these tables only where RLS allows it; role checks
-- performed in the client are surface polish, not the authority.
--
-- Money here stays in integer pesewas (BIGINT), matching the canonical
-- schema and the admin app's formatters.
-- ------------------------------------------------------------------

-- ------------------------------------------------------------------
-- Administrator identity
-- ------------------------------------------------------------------
-- Profiles bridge Supabase auth users and POS roles. Role changes,
-- deactivations and MFA requirements are made ONLY through privileged SQL or
-- the Kotlin server with its service connection; there is no RLS-granted
-- write path.
create table if not exists profiles (
  id           uuid primary key references auth.users (id) on delete cascade,
  full_name    text not null,
  role         text not null check (role in ('cashier', 'manager', 'admin')),
  is_active    boolean not null default true,
  mfa_required boolean not null default false,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);

alter table profiles enable row level security;

-- A user reads exactly their own row; administrators read all rows. Without
-- a matching row here, the admin app's gate denies access.

create policy profiles_self_read on profiles
  for select using (auth.uid() = id);
create policy profiles_admin_read on profiles
  for select using (public.is_admin());

-- ------------------------------------------------------------------
-- Authorization helper
-- ------------------------------------------------------------------
create or replace function public.is_admin() returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from profiles
    where id = auth.uid() and role = 'admin' and is_active
  );
$$;



-- Policies on sales etc. call is_admin(), so it must stay executable by
-- authenticated users. It is SECURITY DEFINER but reads only `profiles`, so
-- it cannot be abused to leak other tables.
-- (No REVOKE here: revoking execute from `authenticated` would break every
-- policy that references it.)

-- ------------------------------------------------------------------
-- Canonical POS tables: administrators get SELECT only. Writes still belong
-- to the Kotlin POS server (its postgres/service connection bypasses RLS), so
-- the admin app cannot create, sell, refund or reconcile — it can only look.
alter table sales          enable row level security;
alter table sale_items     enable row level security;
alter table payments       enable row level security;
alter table products       enable row level security;
alter table stock_on_hand  enable row level security;
alter table approvals      enable row level security;
alter table audit_log      enable row level security;

create policy sales_admin_read    on sales         for select using (public.is_admin());
create policy sale_items_read     on sale_items    for select using (public.is_admin());
create policy payments_read       on payments      for select using (public.is_admin());
create policy products_read       on products      for select using (public.is_admin());
create policy stock_on_hand_admin_read on stock_on_hand for select using (public.is_admin());
create policy approvals_read      on approvals     for select using (public.is_admin());
create policy audit_admin_read    on audit_log     for select using (public.is_admin());

-- ------------------------------------------------------------------
-- Dashboard views (security_invoker: the admin's own RLS applies)
-- ------------------------------------------------------------------
create or replace view dashboard_sales_daily
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
from sales s
left join sale_items si on si.sale_id = s.sale_id
left join products p    on p.product_id = si.product_id
group by 1;

create or replace view low_stock_overview
with (security_invoker = true) as
select
  p.sku,
  p.name,
  soh.quantity,
  p.low_stock_threshold
from products p
join stock_on_hand soh on soh.product_id = p.product_id
where p.is_active and soh.quantity <= p.low_stock_threshold;

-- ------------------------------------------------------------------
-- Seed hook for the first administrator. Replace the email below with the
-- owner's Supabase auth identity (create the auth user first via the CLI or
-- the dashboard), then run this block once and REMOVE it afterwards.
-- ------------------------------------------------------------------
-- insert into profiles (id, full_name, role, is_active, mfa_required)
-- select u.id, '<FULL NAME>', 'admin', true, true
-- from auth.users u where u.email = '<admin@example.com>';
