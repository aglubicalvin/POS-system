-- pos_probe_readonly: least-privilege role for server/tools/ProbeState.java
--
-- STATUS: PROPOSED. NOT EXECUTED. Requires explicit manual approval.
-- Committing, reviewing or merging this file is not evidence that it was run.
-- No CI job, no workflow and no test executes it: the only runner is
-- probe-readonly.ps1 with -Apply, invoked deliberately by the project owner.
--
-- WHO RUNS THIS, AND WITH WHICH CREDENTIAL:
--   * It runs as the designated staging ADMINISTRATOR (PROBE_ADMIN_USER =
--     postgres.<POS_STAGING_PROJECT_REF>), because creating a role requires
--     CREATEROLE on the project.
--   * It creates a SEPARATE least-privilege account, pos_probe_readonly, with its
--     own password (set by pos_probe_readonly_password.sql; the password is never
--     stored in this repository).
--   * The identity probe (probe-staging-identity.yml -> ProbeState.java) also
--     authenticates as the staging administrator and does NOT use
--     pos_probe_readonly. Nothing in this repository currently logs in as that
--     role.
--   * The current staging identity gate accepts the ADMINISTRATOR login only:
--     ProbeState requires CHECK_USER to be exactly postgres.<POS_STAGING_PROJECT_REF>.
--     Setting CHECK_USER=pos_probe_readonly is therefore NOT sufficient today --
--     the gate refuses every other login with exit 11 before a connection is
--     attempted, and that administrator check is deliberately not relaxed here.
--   * Proving that the probe works under pos_probe_readonly needs a separate
--     future test path, or an explicitly designed role-aware identity mode, that
--     authenticates as that account with its own password. Until such a test
--     exists and passes, creating the role is not evidence that the probe can run
--     under it: the role is uncreated and unproven.
--   * Verifying project identity and creating this role are separate operations
--     with separate authorization requirements.
--
-- TARGET SCOPE: the DESIGNATED STAGING project only, run by an administrator
-- of that project. This file previously said "DEVELOPMENT project only"; that
-- instruction is superseded: the role is scoped to the designated staging
-- project and to nothing else. The pos_probe_readonly role is defined in the
-- proposed SQL but remains uncreated and unproven. No repository workflow or
-- test currently authenticates as that role.
--
-- Why this DDL is valid on staging, statement by statement:
--   * step 1 creates a LOGIN role owned by the executing administrator. It names
--     no schema, database or environment; Supabase's postgres role may create
--     roles on any project it administers.
--   * step 2 sets role defaults only (read-only transactions, statement timeout).
--   * step 3 grants CONNECT on the postgres database, which exists on every
--     Supabase project.
--   * steps 4-5 grant USAGE on supabase_migrations and column SELECT on
--     supabase_migrations.schema_migrations, both created and owned by Supabase
--     itself. Nothing in public or auth is granted, so the role cannot read
--     business data. USAGE on public is inherited from Supabase's PUBLIC
--     baseline and confers no object access; it is expected, not granted here.
--   * steps 6-8 are read-only verification queries that emit labelled
--     evidence lines (label=value), never psql-formatted tables.
-- There is no environment-specific object, no extension, no data and no
-- destructive statement anywhere in this file.
--
-- FAIL-CLOSED: the wrapper (probe-readonly.ps1) runs all three files in ONE
-- transaction with ON_ERROR_STOP=1. If supabase_migrations or its table is absent
-- (for example on a project where migrations were never run), the grant fails,
-- the transaction rolls back and no role is left behind.
--
-- The target project reference must be confirmed before running (see step 0), and
-- the wrapper refuses unless POS_STAGING_PROJECT_REF, the validated pooler host,
-- the staged CA and the administrator login all agree.
-- Never run against production.
--
-- EVIDENCE: the wrapper does not tee the psql transcript. It captures it to a
-- private temp file, reduces it to labelled evidence lines
-- (server/tools/probe-role-evidence.ps1) and deletes the transcript on success
-- and failure. Only that summary is uploaded. Steps 0 and 6-8 therefore emit
-- label=value lines, and nothing here prints schema, table, function or role
-- inventories.
--
-- The password is NOT in this file. Set it separately in psql so the secret
-- never appears in a script or in shell history:
--     \password pos_probe_readonly
--
-- ProbeState.java has been changed to match this role: catalog counts use
-- pg_catalog (information_schema hides objects without privileges, which would
-- undercount), and the migration count uses count(version). Running the probe
-- under this role is still the test that proves it works.
--
-- SCOPE: the probe checks counts and names only. It does NOT audit constraints,
-- indexes, function bodies, grants, extensions or data checksums. Do not report
-- its output as a complete RLS, constraint or integrity audit.

-- 0. Confirm the target login before creating anything. probe-readonly.ps1 has
--    already validated PROBE_ADMIN_USER against POS_STAGING_PROJECT_REF; this
--    repeats the comparison against the account PostgreSQL actually opened, so a
--    session made as a different login cannot create the role. It emits a
--    boolean only: no account, database or project name is printed. The previous
--    select of current_user/current_database() printed inventory that reached the
--    uploaded artifact, and current_database() is 'postgres' on every project, so
--    it identified nothing.
--    Never run this file against production, and do not infer the project from
--    the pooler hostname: the pooler is shared by every project in a region.
select set_config('pos.expected_admin_user', :'admin_user', false);
select 'project_identity=' ||
  case when coalesce(current_setting('pos.expected_admin_user', true), '') <> ''
         and current_user = current_setting('pos.expected_admin_user', true)
       then 'confirmed' else 'mismatch' end;
do $$
declare
  expected text := coalesce(current_setting('pos.expected_admin_user', true), '');
begin
  if expected = '' or current_user <> expected then
    raise exception 'pos_probe_readonly project identity refused: the session is not the designated staging administrator';
  end if;
end
$$;

-- 1. Role: LOGIN only, no elevated attributes.
create role pos_probe_readonly
  login
  nosuperuser
  nocreatedb
  nocreaterole
  noreplication
  nobypassrls
  connection limit 2;

-- 2. Defence in depth: default every transaction to read-only, bound runtime.
alter role pos_probe_readonly set default_transaction_read_only = on;
alter role pos_probe_readonly set statement_timeout = '60s';

-- 3. May connect to the database.
grant connect on database postgres to pos_probe_readonly;

-- 4. Schema access: only supabase_migrations, which the probe queries by name.
--    public is NOT granted here. Supabase already grants USAGE on public to
--    PUBLIC, so the role inherits it; that baseline confers no access to any
--    object. ProbeState reads pg_catalog, which needs no schema USAGE. No table
--    privileges are granted anywhere.
grant usage on schema supabase_migrations to pos_probe_readonly;

-- 5. Migration history: only the two columns the probe prints.
--    `statements` (raw SQL text) and `created_by` are deliberately NOT granted.
grant select (version, name) on supabase_migrations.schema_migrations to pos_probe_readonly;

-- 6. Verification (read-only), emitted as labelled evidence lines only.
--    Expected: public_schema_usage = true (inherited from Supabase's PUBLIC
--    baseline and harmless), every other privilege column false, and the
--    elevated attribute false. psql runs with -q -A -t, so there are no headers
--    and no separators, and the sanitizer accepts exactly these labels.
select 'auth_schema_usage=' || has_schema_privilege('pos_probe_readonly', 'auth', 'USAGE')::text
union all
select 'public_schema_usage=' || has_schema_privilege('pos_probe_readonly', 'public', 'USAGE')::text
union all
select 'auth_users_select=' || has_table_privilege('pos_probe_readonly', 'auth.users', 'SELECT')::text
union all
select 'auth_identities_select=' || has_table_privilege('pos_probe_readonly', 'auth.identities', 'SELECT')::text
union all
select 'profiles_select=' || has_table_privilege('pos_probe_readonly', 'public.profiles', 'SELECT')::text
union all
select 'customers_select=' || has_table_privilege('pos_probe_readonly', 'public.customers', 'SELECT')::text
union all
select 'sales_select=' || has_table_privilege('pos_probe_readonly', 'public.sales', 'SELECT')::text
union all
select 'payments_select=' || has_table_privilege('pos_probe_readonly', 'public.payments', 'SELECT')::text
union all
select 'migration_statements_select=' || has_column_privilege('pos_probe_readonly', 'supabase_migrations.schema_migrations', 'statements', 'SELECT')::text
union all
select 'public_create=' || has_schema_privilege('pos_probe_readonly', 'public', 'CREATE')::text
union all
select 'elevated_attribute=' || (select (rolsuper or rolcreaterole or rolcreatedb or rolreplication or rolbypassrls)::text
     from pg_roles where rolname = 'pos_probe_readonly');

-- 7. Login and membership checks, again as labelled evidence. Expected:
--    can_login = true, membership_count = 0. Membership names are not emitted.
select 'can_login=' || (select rolcanlogin::text from pg_roles where rolname = 'pos_probe_readonly')
union all
select 'membership_count=' || (select count(*)::text
     from pg_auth_members m join pg_roles u on u.oid = m.member
     where u.rolname = 'pos_probe_readonly');

-- 8. Table-level grants held by the role, as a count only. Expected 0 in public,
--    auth and supabase_migrations. Uses pg_class ACLs: information_schema's
--    role_table_grants only shows grants where the querying user is grantor or
--    grantee. Column-level grants (step 5) are checked in step 6.
select 'table_grant_count=' || count(*)::text
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
join lateral aclexplode(c.relacl) a on true
where a.grantee = (select oid from pg_roles where rolname = 'pos_probe_readonly')
  and n.nspname in ('public', 'auth', 'supabase_migrations');
