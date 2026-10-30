-- Phase 4.3 workflow support: reorder points, product media, inventory reporting.
-- Apply after 0005_customers_payroll_hirepurchase.sql.

alter table public.products
  add column if not exists reorder_point bigint not null default 0 check (reorder_point >= 0);

-- Replace the earlier catalog view with the complete media/reorder projection.
create or replace view public.admin_product_catalog
with (security_invoker = true) as
select
  p.product_id, p.sku, p.name, p.unit_price, p.cost_price,
  p.low_stock_threshold, p.is_active, c.category, soh.quantity,
  p.description, p.image_url, p.thumbnail_url, p.reorder_point
from public.products p
left join public.product_categories c on c.product_id = p.product_id
left join public.stock_on_hand soh on soh.product_id = p.product_id;

grant select on public.admin_product_catalog to authenticated;
revoke all on public.admin_product_catalog from anon;

create or replace view public.admin_inventory_overview
with (security_invoker = true) as
select
  p.product_id, p.sku, p.name, p.image_url, p.thumbnail_url,
  coalesce(soh.quantity, 0) as quantity,
  p.low_stock_threshold, p.reorder_point, p.cost_price,
  (coalesce(soh.quantity, 0) * p.cost_price) as inventory_value,
  (coalesce(soh.quantity, 0) <= greatest(p.low_stock_threshold, p.reorder_point)) as low_stock
from public.products p
left join public.stock_on_hand soh on soh.product_id = p.product_id
where p.is_active;

grant select on public.admin_inventory_overview to authenticated;
revoke all on public.admin_inventory_overview from anon;

create index if not exists idx_payroll_deductions_month_status
  on public.payroll_deductions (deduction_month, status);
create index if not exists idx_hire_purchase_status_end_date
  on public.hire_purchase_contracts (status, end_date);
create index if not exists idx_sales_customer_created
  on public.sales (customer_id, created_at desc);

-- Aggregate sales and deductions independently: joining both child relations
-- directly multiplies values for customers with multiple sales/deductions.
create or replace view public.customer_purchase_summary
with (security_invoker = true) as
with sales_by_customer as (
  select customer_id,
    count(*) filter (where kind = 'sale') as total_purchases,
    coalesce(sum(total) filter (where kind = 'sale'), 0) as total_spent,
    max(created_at) as last_purchase_date
  from public.sales where customer_id is not null group by customer_id
), deductions_by_customer as (
  select customer_id,
    coalesce(sum(amount) filter (where status <> 'cancelled'), 0) as total_payroll_deductions
  from public.payroll_deductions group by customer_id
)
select c.customer_id, c.staff_number, c.full_name, c.company,
  c.payment_method_preference,
  coalesce(s.total_purchases, 0) as total_purchases,
  coalesce(s.total_spent, 0) as total_spent,
  coalesce(d.total_payroll_deductions, 0) as total_payroll_deductions,
  s.last_purchase_date
from public.customers c
left join sales_by_customer s on s.customer_id = c.customer_id
left join deductions_by_customer d on d.customer_id = c.customer_id;

grant select on public.customer_purchase_summary to authenticated;
revoke all on public.customer_purchase_summary from anon;

-- Admin-only media write path. HTTPS image URLs only; no client UPDATE grant
-- on the authoritative POS products table is introduced by this migration.
create or replace function public.admin_update_product_media(
  p_product_id text,
  p_description text,
  p_image_url text,
  p_thumbnail_url text
) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if not public.is_admin() then
    raise exception 'only administrators may update product media';
  end if;
  if p_image_url is not null and p_image_url !~ '^https://' then
    raise exception 'image URL must use HTTPS';
  end if;
  if p_thumbnail_url is not null and p_thumbnail_url !~ '^https://' then
    raise exception 'thumbnail URL must use HTTPS';
  end if;
  update public.products
     set description = nullif(trim(p_description), ''),
         image_url = p_image_url,
         thumbnail_url = p_thumbnail_url
   where product_id = p_product_id;
  if not found then raise exception 'product % does not exist', p_product_id; end if;
end;
$$;
revoke execute on function public.admin_update_product_media(text, text, text, text) from public, anon;
grant execute on function public.admin_update_product_media(text, text, text, text) to authenticated;

-- Admin-facing contract creation creates the contract and all scheduled due
-- rows in one transaction; the client cannot leave a contract without a plan.
create or replace function public.admin_create_hire_purchase_contract(
  p_customer_id uuid,
  p_product_id text,
  p_quantity bigint,
  p_retail_price bigint,
  p_deposit bigint,
  p_duration_months integer,
  p_start_date date
) returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  v_contract_id uuid := gen_random_uuid();
  v_subtotal bigint;
  v_sale_tax bigint;
  v_total bigint;
  v_financed bigint;
  v_base_installment bigint;
  v_remaining bigint;
  v_item_tax bigint;
  v_device_id text;
  v_product_sku text;
  v_product_name text;
  v_due date;
  v_amount bigint;
  v_idx integer;
  v_payment_id uuid;
  v_sale_id text;
  v_cashier_id text;
  v_receipt text;
  v_created_at timestamptz := now();
begin
  if not public.is_admin() then raise exception 'only administrators may create hire-purchase contracts'; end if;
  if p_quantity is null or p_quantity <= 0 or p_retail_price is null or p_retail_price < 0
     or p_deposit is null or p_deposit < 0 or p_duration_months is null or p_duration_months <= 0
     or p_start_date is null then
    raise exception 'quantity and duration must be positive; prices, deposit and start date are required';
  end if;
  if not exists (select 1 from public.customers where customer_id = p_customer_id and is_active) then
    raise exception 'active customer not found';
  end if;
  if not exists (select 1 from public.products where product_id = p_product_id and is_active) then
    raise exception 'active product not found';
  end if;
  select device_id, tax_rate_bp, sku, name
    into v_device_id, v_item_tax, v_product_sku, v_product_name
    from public.products where product_id = p_product_id and is_active;
  v_cashier_id := coalesce((select user_id from public.users where is_active order by created_at limit 1), '');
  if v_device_id is null or v_device_id = '' or v_cashier_id = '' then
    raise exception 'POS device and active cashier are required to record the hire-purchase sale';
  end if;
  if p_retail_price > 0 and p_quantity > 9223372036854775807 / p_retail_price then
    raise exception 'contract total exceeds supported amount';
  end if;
  v_subtotal := p_retail_price * p_quantity;
  v_sale_tax := floor((v_subtotal::numeric * v_item_tax) / 10000 + 0.5)::bigint;
  if v_subtotal > 9223372036854775807 - v_sale_tax then
    raise exception 'contract total exceeds supported amount';
  end if;
  v_total := v_subtotal + v_sale_tax;
  if p_deposit > v_total then raise exception 'deposit cannot exceed the contract total'; end if;
  v_financed := v_total - p_deposit;
  v_sale_id := gen_random_uuid()::text;
  v_receipt := 'HP-' || to_char(v_created_at at time zone 'UTC', 'YYYYMMDD') || '-' || upper(substr(replace(v_sale_id, '-', ''), 1, 8));
  v_base_installment := case when p_duration_months = 0 then 0 else v_financed / p_duration_months end;
  v_remaining := v_financed;

  insert into public.sales (
    sale_id, device_id, receipt_number, cashier_id, customer_ref, currency,
    subtotal, discount_total, tax_total, total, kind, status, sync_state, created_at,
    customer_id, payment_channel, staff_number
  ) select v_sale_id, v_device_id, v_receipt, v_cashier_id, c.staff_number, 'GHS',
      v_subtotal, 0, v_sale_tax, v_total, 'sale', 'completed', 'synced', v_created_at,
      c.customer_id, 'payroll_deduction', c.staff_number
    from public.customers c where c.customer_id = p_customer_id;

  insert into public.sale_items (
    item_id, sale_id, product_id, sku, name, quantity, unit_price, discount,
    tax_rate_bp, tax, line_total, price_source
  ) values (
    gen_random_uuid()::text, v_sale_id, p_product_id, v_product_sku, v_product_name,
    p_quantity, p_retail_price, 0, v_item_tax, v_sale_tax, v_total, 'catalog'
  );

  insert into public.hire_purchase_contracts (
    contract_id, customer_id, sale_id, product_id, quantity, retail_price, deposit,
    duration_months, installment_amount, total_amount, remaining_balance,
    status, start_date, end_date
  ) values (
    v_contract_id, p_customer_id, v_sale_id, p_product_id, p_quantity, p_retail_price, p_deposit,
    p_duration_months, v_base_installment, v_total, v_financed,
    case when v_financed = 0 then 'completed' else 'active' end,
    p_start_date, (p_start_date + make_interval(months => p_duration_months))::date
  );

  if p_deposit > 0 then
    v_payment_id := gen_random_uuid();
    insert into public.payments (payment_id, sale_id, method, amount, status, idempotency_key, provider, created_at, updated_at)
    values (v_payment_id::text, v_sale_id, 'cash', p_deposit, 'confirmed', 'hp-deposit:' || v_contract_id::text, null, v_created_at, v_created_at);
  end if;
  if v_financed > 0 then
    insert into public.payroll_deductions (customer_id, sale_id, amount, deduction_month, status, notes)
    values (p_customer_id, v_sale_id, v_financed, to_char(p_start_date, 'YYYY-MM'), 'pending', 'Hire-purchase financed balance');
  end if;

  for v_idx in 1..p_duration_months loop
    v_due := (p_start_date + make_interval(months => v_idx))::date;
    v_amount := case when v_idx = p_duration_months then v_remaining else least(v_base_installment, v_remaining) end;
    if v_amount > 0 then
      insert into public.hire_purchase_payments (contract_id, amount, due_date, status)
      values (v_contract_id, v_amount, v_due, 'pending');
      v_remaining := v_remaining - v_amount;
    end if;
  end loop;
  return v_contract_id;
end;
$$;
revoke execute on function public.admin_create_hire_purchase_contract(uuid, text, bigint, bigint, bigint, integer, date) from public, anon;
grant execute on function public.admin_create_hire_purchase_contract(uuid, text, bigint, bigint, bigint, integer, date) to authenticated;

-- Payment recording locks contract + installment and updates both atomically.
create or replace function public.admin_pay_hire_purchase_installment(p_payment_id uuid)
returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_payment public.hire_purchase_payments%rowtype;
  v_contract public.hire_purchase_contracts%rowtype;
begin
  if not public.is_admin() then raise exception 'only administrators may record hire-purchase payments'; end if;
  select * into v_payment from public.hire_purchase_payments where hp_payment_id = p_payment_id for update;
  if not found then raise exception 'installment not found'; end if;
  if v_payment.status = 'paid' then return; end if;
  if v_payment.status not in ('pending', 'overdue') then raise exception 'installment is not payable'; end if;
  select * into v_contract from public.hire_purchase_contracts where contract_id = v_payment.contract_id for update;
  if v_contract.status <> 'active' then raise exception 'contract is not active'; end if;
  if v_payment.amount > v_contract.remaining_balance then raise exception 'installment exceeds remaining contract balance'; end if;
  update public.hire_purchase_payments set status = 'paid', paid_at = now(), updated_at = now() where hp_payment_id = p_payment_id;
  if v_contract.sale_id is not null then
    insert into public.payments (payment_id, sale_id, method, amount, status, idempotency_key, provider, provider_ref, created_at, updated_at)
    values (gen_random_uuid()::text, v_contract.sale_id, 'credit', v_payment.amount, 'confirmed', 'hp-installment:' || p_payment_id::text, 'payroll', p_payment_id::text, now(), now())
    on conflict (idempotency_key) do nothing;
  end if;
  update public.payroll_deductions
     set amount = greatest(0, amount - v_payment.amount),
         status = case when v_payment.amount = v_contract.remaining_balance then 'paid' else status end,
         processed_at = case when v_payment.amount = v_contract.remaining_balance then now() else processed_at end,
         updated_at = now()
   where sale_id = v_contract.sale_id and status <> 'cancelled';
  update public.hire_purchase_contracts
     set remaining_balance = remaining_balance - v_payment.amount,
         status = case when remaining_balance - v_payment.amount = 0 then 'completed' else status end,
         updated_at = now()
   where contract_id = v_contract.contract_id;
end;
$$;
revoke execute on function public.admin_pay_hire_purchase_installment(uuid) from public, anon;
grant execute on function public.admin_pay_hire_purchase_installment(uuid) to authenticated;
