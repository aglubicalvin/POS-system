-- 0007_mobile_money_reconciliation.sql
-- Admins may compare an already-confirmed MoMo payment with a provider
-- statement. Only an authenticated server-side provider hook may initiate or
-- confirm a payment; this RPC cannot change its status, amount or reference.

create or replace function public.admin_reconcile_mobile_money_payment(
  p_payment_id text,
  p_provider_ref text,
  p_amount bigint
) returns table (payment_id text, matched boolean, reason text)
language plpgsql security definer set search_path = '' as $$
declare
  v_method text;
  v_status text;
  v_amount bigint;
  v_provider_ref text;
begin
  if not public.is_admin() then
    raise exception 'only administrators may reconcile payments';
  end if;
  if nullif(trim(p_provider_ref), '') is null or p_amount <= 0 then
    raise exception 'a provider reference and positive statement amount are required';
  end if;

  select p.method, p.status, p.amount, p.provider_ref
    into v_method, v_status, v_amount, v_provider_ref
    from public.payments p
   where p.payment_id = p_payment_id
   for update;
  if not found then raise exception 'payment % does not exist', p_payment_id; end if;
  if v_method <> 'mobile_money' then raise exception 'only mobile-money payments can be reconciled here'; end if;

  if v_status <> 'confirmed' then
    return query select p_payment_id, false, 'not-confirmed'::text;
    return;
  end if;
  if v_amount <> p_amount then
    return query select p_payment_id, false, 'amount-mismatch'::text;
    return;
  end if;
  if v_provider_ref is null or v_provider_ref <> trim(p_provider_ref) then
    return query select p_payment_id, false, 'reference-mismatch'::text;
    return;
  end if;

  update public.payments p
     set reconciled_at = coalesce(p.reconciled_at, now()), updated_at = now()
   where p.payment_id = p_payment_id;
  return query select p_payment_id, true, 'matched'::text;
end;
$$;

revoke execute on function public.admin_reconcile_mobile_money_payment(text, text, bigint) from public, anon;
grant execute on function public.admin_reconcile_mobile_money_payment(text, text, bigint) to authenticated, service_role;
