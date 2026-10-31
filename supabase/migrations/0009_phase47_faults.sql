-- 0009_phase47_faults.sql
-- Phase 4.7: fault lifecycle (reporting, escalation, posting, denial, closure)
-- plus the approval queue the escalation feeds.
--
-- Everything here is derived from the contract the E2E script already encodes
-- (scripts/e2e-supabase.ts, fault-lifecycle block) and docs/security-audit.md:
--   fields     : fault_id, sale_id, payment_id, reported_by_user_id,
--                issue_summary, priority, status, is_defect, payment_blocked,
--                denial_reason, created_at/updated_at
--   statuses   : reported -> with_approvals -> (posted | denied);
--                denied -> closed; closed -> reported (reopen); reported -> posted
--   permissions: supervisors (profiles.is_supervisor) post/escalate/deny/close;
--                plain admins read only; anon and ordinary authenticated users
--                get nothing.
--   side effect: posting a defect blocks the linked payment (payments.blocked);
--                denial or an unset defect unblocks it. Escalation inserts an
--                approval_queues row.
--
-- Apply order: after 0008. Rollback: 0009_phase47_faults.rollback.sql.

-- ------------------------------------------------------------------
-- Supervisor flag on profiles
-- ------------------------------------------------------------------
-- Faults are lifecycle-managed by supervisors, not every admin. The flag is
-- set ONLY through privileged SQL / the Kotlin server (no RLS write path),
-- mirroring how profiles.role is handled in 0001.
alter table public.profiles add column if not exists is_supervisor boolean not null default false;

create or replace function public.is_supervisor() returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.profiles
    where id = auth.uid() and is_supervisor and is_active and role = 'admin'
  );
$$;

revoke all on function public.is_supervisor() from public;
revoke all on function public.is_supervisor() from anon;
grant execute on function public.is_supervisor() to authenticated, service_role;

-- ------------------------------------------------------------------
-- Approval queue (escalation side effect)
-- ------------------------------------------------------------------
create table if not exists public.approval_queues (
  queue_id     uuid primary key default gen_random_uuid(),
  user_id      uuid not null references auth.users (id) on delete cascade,
  entity_type  text not null check (entity_type in ('fault')),
  entity_id    text not null,
  action       text not null,
  inserted_at  timestamptz not null default now(),
  processed_at timestamptz
);

create index if not exists approval_queues_user_pending_idx
  on public.approval_queues (user_id, inserted_at)
  where processed_at is null;

alter table public.approval_queues enable row level security;

create policy approval_queues_admin_read on public.approval_queues
  for select to authenticated using (public.is_admin());

-- Escalation inserts a queue row for the reporting supervisor via the fault
-- trigger; the trigger runs as the invoker, so supervisors need this.
create policy approval_queues_supervisor_insert on public.approval_queues
  for insert to authenticated with check (public.is_supervisor() and user_id = auth.uid());

-- Processed by the server (service connection, RLS bypass) or the owning
-- supervisor via the RPC below.
create or replace function public.process_approval_queues(p_user_id uuid)
returns integer
language plpgsql security definer
set search_path = ''
as $$
declare
  v_count integer;
begin
  if p_user_id is null or p_user_id <> auth.uid() then
    raise exception 'process_approval_queues may only process the caller''s own queue';
  end if;
  update public.approval_queues
     set processed_at = now()
   where user_id = p_user_id and processed_at is null;
  get diagnostics v_count = row_count;
  return v_count;
end;
$$;

revoke all on function public.process_approval_queues(uuid) from public;
revoke all on function public.process_approval_queues(uuid) from anon;
grant execute on function public.process_approval_queues(uuid) to authenticated, service_role;

-- ------------------------------------------------------------------
-- Faults
-- ------------------------------------------------------------------
-- The payment-blocking side effect needs a flag on payments: true while a
-- posted defect is anchored to the payment, false once the fault is denied.
alter table public.payments add column if not exists blocked boolean not null default false;

create table if not exists public.faults (
  fault_id            text primary key,
  sale_id             text not null references public.sales (sale_id) on delete cascade,
  payment_id          text references public.payments (payment_id) on delete set null,
  reported_by_user_id uuid not null references auth.users (id) on delete cascade,
  issue_summary       text not null check (length(issue_summary) between 1 and 500),
  priority            text not null default 'medium'
                        check (priority in ('low', 'medium', 'high')),
  status              text not null default 'reported'
                        check (status in ('reported', 'with_approvals', 'posted', 'denied', 'closed')),
  is_defect           boolean not null default false,
  payment_blocked     boolean not null default false,
  denial_reason       text,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now()
);

create index if not exists faults_status_idx   on public.faults (status);
create index if not exists faults_sale_idx     on public.faults (sale_id);
create index if not exists faults_payment_idx  on public.faults (payment_id);

alter table public.faults enable row level security;

create policy faults_admin_read on public.faults
  for select to authenticated using (public.is_admin());

create policy faults_supervisor_all on public.faults
  for all to authenticated using (public.is_supervisor()) with check (public.is_supervisor());

-- ------------------------------------------------------------------
-- Status-transition guard
-- ------------------------------------------------------------------
create or replace function public.faults_guard_transition()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' and new.status not in ('reported', 'posted') then
    raise exception 'a fault starts as reported (or an already-posted defect)';
  end if;

  if tg_op = 'UPDATE' and new.status <> old.status then
    if not (
      (old.status = 'reported'       and new.status in ('with_approvals', 'posted'))
      or (old.status = 'with_approvals' and new.status in ('posted', 'denied'))
      or (old.status = 'denied'      and new.status = 'closed')
      or (old.status = 'closed'      and new.status = 'reported')
    ) then
      raise exception 'illegal fault transition % -> %', old.status, new.status;
    end if;
  end if;

  if new.status = 'denied' and coalesce(new.denial_reason, '') = '' then
    raise exception 'a denied fault requires a denial_reason';
  end if;
  return new;
end;
$$;

create trigger faults_transition_guard
  before insert or update on public.faults
  for each row execute function public.faults_guard_transition();

-- ------------------------------------------------------------------
-- Payment-blocking side effect
-- ------------------------------------------------------------------
-- SECURITY DEFINER so the payments UPDATE bypasses RLS: payments have no
-- authenticated write path, and this enforced side effect is the ONLY thing
-- that may move `blocked`. It reads/writes just the one flag.
create or replace function public.faults_sync_payment_block()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_should_block boolean;
begin
  v_should_block := new.status = 'posted' and new.is_defect;
  if new.status = 'denied' then
    v_should_block := false;
  end if;
  new.payment_blocked := v_should_block;

  if new.payment_id is not null and (
    tg_op = 'INSERT' or v_should_block <> old.payment_blocked or new.is_defect <> old.is_defect
  ) then
    update public.payments
       set blocked = v_should_block,
           updated_at = now()
     where payment_id = new.payment_id;
  end if;
  return new;
end;
$$;

create trigger faults_payment_block_sync
  before insert or update on public.faults
  for each row execute function public.faults_sync_payment_block();

-- ------------------------------------------------------------------
-- Escalation feeds the approval queue
-- ------------------------------------------------------------------
create or replace function public.faults_escalation_queue()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.status = 'with_approvals' and old.status is distinct from 'with_approvals' then
    insert into public.approval_queues (user_id, entity_type, entity_id, action)
    values (new.reported_by_user_id, 'fault', new.fault_id,
            coalesce('escalate:' || new.fault_id, 'escalate'));
  end if;
  return new;
end;
$$;

create trigger faults_escalation_queue_sync
  after update on public.faults
  for each row execute function public.faults_escalation_queue();
