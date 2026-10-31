-- 0009_phase47_faults.rollback.sql
-- Reverts migration 0009_phase47_faults.sql: drops the faults table, its
-- triggers/functions, the approval queue, and the supervisor flag/function.

drop trigger if exists faults_escalation_queue_sync on public.faults;
drop trigger if exists faults_payment_block_sync   on public.faults;
drop trigger if exists faults_transition_guard     on public.faults;
drop function if exists public.faults_escalation_queue();
drop function if exists public.faults_sync_payment_block();
drop function if exists public.faults_guard_transition();

drop policy if exists faults_supervisor_all on public.faults;
drop policy if exists faults_admin_read     on public.faults;
drop table if exists public.faults;

drop function if exists public.process_approval_queues(uuid);
drop policy if exists approval_queues_admin_read on public.approval_queues;
drop table if exists public.approval_queues;

drop function if exists public.is_supervisor();
alter table public.profiles drop column if exists is_supervisor;
alter table public.payments drop column if exists blocked;
