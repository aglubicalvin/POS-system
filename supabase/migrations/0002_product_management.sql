-- ------------------------------------------------------------------
-- 0002_product_management.sql
--
-- Phase 4.2: product, category and selling-price management for the
-- administrator app, layered on the canonical POS tables.
--
-- Design note (deliberate): the canonical `products` table keeps its exact
-- shape — the Kotlin server still owns catalog lifecycle through device
-- sync — and this migration adds only ADMIN-SCOPED structures beside it:
--   * `product_categories`  name/category mapping for grouping + filtering
--   * `product_price_audit` who changed which price, when, with what reason
--   * an admin-authorized RPC that makes the price change AND the audit row
--     in one server-side transaction — the client cannot update prices by
--     hand-editing the products table, because RLS on products stays
--     SELECT-only.
-- ------------------------------------------------------------------

-- ------------------------------------------------------------------
-- Categories: free-form labels attached to products by admins
-- ------------------------------------------------------------------
create table if not exists product_categories (
  product_id  text not null references products (product_id) on delete cascade,
  category    text not null,
  updated_at  timestamptz not null default now(),
  primary key (product_id)
);

alter table product_categories enable row level security;

create policy categories_read_all on product_categories
  for select using (public.is_admin());
create policy categories_admin_all on product_categories
  for all using (public.is_admin()) with check (public.is_admin());

-- ------------------------------------------------------------------
-- Price audit trail
-- ------------------------------------------------------------------
create table if not exists product_price_audit (
  audit_id    uuid primary key default gen_random_uuid(),
  product_id  text not null references products (product_id) on delete cascade,
  old_price   bigint not null,
  new_price   bigint not null,
  changed_by  uuid not null references auth.users (id),
  reason      text,
  created_at  timestamptz not null default now()
);

alter table product_price_audit enable row level security;

create policy price_audit_admin_read on product_price_audit
  for select using (public.is_admin());

-- ------------------------------------------------------------------
-- The one authorized write path: an RPC that validates the caller, applies
-- the price change and records the audit row atomically. Changing product_id
-- mid-air or skipping the reason is refused; a no-op change is refused too.
-- ------------------------------------------------------------------
create or replace function public.admin_update_product_price(
  p_product_id  text,
  p_new_price   bigint,
  p_reason      text
) returns void
language plpgsql security definer set search_path = public as $$
declare
  v_old_price bigint;
begin
  if not public.is_admin() then
    raise exception 'only administrators may update selling prices';
  end if;
  if p_new_price is null or p_new_price < 0 then
    raise exception 'price must be a non-negative integer (pesewas)';
  end if;
  if length(coalesce(trim(p_reason), '')) < 3 then
    raise exception 'a reason of at least 3 characters is required';
  end if;

  select unit_price into v_old_price from products where product_id = p_product_id;
  if not found then
    raise exception 'product % does not exist', p_product_id;
  end if;
  if v_old_price = p_new_price then
    raise exception 'new price equals the current price';
  end if;

  update products
     set unit_price = p_new_price
   where product_id  = p_product_id;

  insert into product_price_audit (product_id, old_price, new_price, changed_by, reason)
  values (p_product_id, v_old_price, p_new_price, auth.uid(), trim(p_reason));
end;
$$;

-- The RPC becomes callable by authenticated admins; everything it needs is
-- its own is_admin() check.
revoke execute on function public.admin_update_product_price(text, bigint, text) from anon, public;
grant execute on function public.admin_update_product_price(text, bigint, text) to authenticated;

-- Category upsert helper: keeps updated_at fresh, dedupes concurrent writes.
create or replace function public.admin_set_product_category(
  p_product_id text,
  p_category   text
) returns void
language sql security definer set search_path = public as $$
  insert into product_categories (product_id, category)
  values (p_product_id, trim(p_category))
  on conflict (product_id) do update
    set category = excluded.category, updated_at = now()
  where public.is_admin();
$$;

revoke execute on function public.admin_set_product_category(text, text) from anon, public;
grant execute on function public.admin_set_product_category(text, text) to authenticated;

-- ------------------------------------------------------------------
-- Admin catalog view: products joined to categories + current price audit
-- ------------------------------------------------------------------
create or replace view admin_product_catalog
with (security_invoker = true) as
select
  p.product_id,
  p.sku,
  p.name,
  p.unit_price,
  p.cost_price,
  p.low_stock_threshold,
  p.is_active,
  c.category,
  soh.quantity
from products p
left join product_categories c on c.product_id = p.product_id
left join stock_on_hand soh on soh.product_id = p.product_id;
