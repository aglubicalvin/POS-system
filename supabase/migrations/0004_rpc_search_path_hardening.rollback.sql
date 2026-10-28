-- Rollback for 0004_rpc_search_path_hardening.sql
-- Restores the 0002 definitions (search_path = public). Only run if you want
-- to unwind migration 0004 deliberately.

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
