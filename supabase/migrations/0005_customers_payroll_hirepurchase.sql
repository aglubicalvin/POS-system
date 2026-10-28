-- ------------------------------------------------------------------
-- 0005_customers_payroll_hirepurchase.sql
-- Phase 4.3: Customers, payroll deductions, hire purchase, MoMo/payroll payment channels, product images
-- ------------------------------------------------------------------

-- Customers table (identified by staff number for payroll deductions)
create table if not exists customers (
  customer_id uuid primary key default gen_random_uuid(),
  staff_number text unique,
  full_name text not null,
  company text,
  phone text,
  email text,
  payment_method_preference text check (payment_method_preference in ('cash', 'ghana_card', 'payroll_deduction', 'momo')) default 'cash',
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table customers enable row level security;

-- Admins can manage customers
create policy customers_admin_all on customers
  for all using (public.is_admin()) with check (public.is_admin());

-- Extend products with images and description
alter table products add column if not exists description text;
alter table products add column if not exists image_url text;
alter table products add column if not exists thumbnail_url text;

-- Extend sales to link to customers and track payment channel
alter table sales add column if not exists customer_id uuid references customers(customer_id);
alter table sales add column if not exists payment_channel text check (payment_channel in ('cash', 'ghana_card', 'payroll_deduction', 'momo')) default 'cash';
alter table sales add column if not exists staff_number text;

-- Payroll deductions
create table if not exists payroll_deductions (
  deduction_id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references customers(customer_id) on delete cascade,
  sale_id text references sales(sale_id) on delete set null,
  amount bigint not null check (amount >= 0),
  deduction_month text not null, -- YYYY-MM format
  status text not null check (status in ('pending', 'processed', 'paid', 'cancelled')) default 'pending',
  processed_at timestamptz,
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table payroll_deductions enable row level security;
create policy payroll_deductions_admin_all on payroll_deductions
  for all using (public.is_admin()) with check (public.is_admin());

-- Hire purchase contracts
create table if not exists hire_purchase_contracts (
  contract_id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references customers(customer_id) on delete restrict,
  sale_id text references sales(sale_id) on delete set null,
  product_id text not null references products(product_id) on delete restrict,
  quantity bigint not null check (quantity > 0),
  retail_price bigint not null check (retail_price >= 0),
  deposit bigint not null default 0 check (deposit >= 0),
  duration_months integer not null check (duration_months > 0),
  installment_amount bigint not null check (installment_amount >= 0),
  total_amount bigint not null check (total_amount >= 0),
  remaining_balance bigint not null check (remaining_balance >= 0),
  status text not null check (status in ('active', 'completed', 'defaulted', 'cancelled')) default 'active',
  start_date date not null default current_date,
  end_date date,
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table hire_purchase_contracts enable row level security;
create policy hp_contracts_admin_all on hire_purchase_contracts
  for all using (public.is_admin()) with check (public.is_admin());

-- Hire purchase payments
create table if not exists hire_purchase_payments (
  hp_payment_id uuid primary key default gen_random_uuid(),
  contract_id uuid not null references hire_purchase_contracts(contract_id) on delete cascade,
  amount bigint not null check (amount > 0),
  due_date date not null,
  paid_at timestamptz,
  status text not null check (status in ('pending', 'paid', 'overdue', 'waived')) default 'pending',
  receipt_number text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table hire_purchase_payments enable row level security;
create policy hp_payments_admin_all on hire_purchase_payments
  for all using (public.is_admin()) with check (public.is_admin());

-- View for customers with purchase history summary
create or replace view customer_purchase_summary
with (security_invoker = true) as
select
  c.customer_id,
  c.staff_number,
  c.full_name,
  c.company,
  c.payment_method_preference,
  count(distinct s.sale_id) as total_purchases,
  coalesce(sum(s.total) filter (where s.kind = 'sale'), 0) as total_spent,
  coalesce(sum(pd.amount), 0) as total_payroll_deductions,
  max(s.created_at) as last_purchase_date
from customers c
left join sales s on s.customer_id = c.customer_id
left join payroll_deductions pd on pd.customer_id = c.customer_id
group by c.customer_id, c.staff_number, c.full_name, c.company, c.payment_method_preference;

grant select on customer_purchase_summary to authenticated;
revoke all on customer_purchase_summary from anon;

-- View for hire purchase contracts with countdown
create or replace view hire_purchase_overview
with (security_invoker = true) as
select
  hpc.contract_id,
  hpc.customer_id,
  c.staff_number,
  c.full_name,
  c.company,
  hpc.product_id,
  p.name as product_name,
  p.description,
  hpc.quantity,
  hpc.retail_price,
  hpc.duration_months,
  hpc.installment_amount,
  hpc.deposit,
  hpc.total_amount,
  hpc.remaining_balance,
  hpc.status,
  hpc.start_date,
  hpc.end_date,
  (hpc.end_date - current_date) as days_remaining,
  case 
    when hpc.end_date < current_date and hpc.status = 'active' then 'overdue'
    when hpc.status = 'completed' then 'completed'
    else 'active'
  end as display_status
from hire_purchase_contracts hpc
left join customers c on c.customer_id = hpc.customer_id
left join products p on p.product_id = hpc.product_id;

grant select on hire_purchase_overview to authenticated;
revoke all on hire_purchase_overview from anon;
