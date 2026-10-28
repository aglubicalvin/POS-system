-- ------------------------------------------------------------------
-- 0004_rpc_search_path_hardening.sql
--
-- 0002's two SECURITY DEFINER RPCs ran with `set search_path = public`.
-- They are the only authorized write path into `products` /
-- `product_price_audit` / `product_categories`, so a hijacked object name
-- anywhere in `public` would execute with the owner's privileges. Same
-- treatment is_admin() got in 0003: pinned to an EMPTY search_path with
-- every referenced object schema-qualified, grants restated so the
-- callers' ACL is untouched. Behavior (inputs, messages, effects) is
-- unchanged.
-- ------------------------------------------------------------------

-- The one write path into selling prices: validates the caller, applies the
-- change and records the audit row atomically.
create or replace function public.admin_update_product_price(
  p_product_id  text,
  p_new_price   bigint,
  p_reason      text
) returns void
language plpgsql
security definer
set search_path = ''
as $$
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

  select unit_price into v_old_price
    from public.products
   where product_id = p_product_id;
  if not found then
    raise exception 'product % does not exist', p_product_id;
  end if;
  if v_old_price = p_new_price then
    raise exception 'new price equals the current price';
  end if;

  update public.products
     set unit_price = p_new_price
   where product_id  = p_product_id;

  insert into public.product_price_audit (product_id, old_price, new_price, changed_by, reason)
  values (p_product_id, v_old_price, p_new_price, auth.uid(), trim(p_reason));
end;
$$;

revoke execute on function public.admin_update_product_price(text, bigint, text) from anon, public;
grant execute on function public.admin_update_product_price(text, bigint, text) to authenticated;

-- Category upsert helper: keeps updated_at fresh, dedupes concurrent writes.
create or replace function public.admin_set_product_category(
  p_product_id text,
  p_category   text
) returns void
language sql
security definer
set search_path = ''
as $$
  insert into public.product_categories (product_id, category)
  values (p_product_id, trim(p_category))
  on conflict (product_id) do update
    set category = excluded.category, updated_at = now()
  where public.is_admin();
$$;

revoke execute on function public.admin_set_product_category(text, text) from anon, public;
grant execute on function public.admin_set_product_category(text, text) to authenticated;
