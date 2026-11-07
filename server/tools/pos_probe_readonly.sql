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
--   * Proving that the probe works under pos_probe_readonly needs a separate
--     least-privilege connection test (a probe run with CHECK_USER set to that
--     role and that account's own password). That test is future work: creating
--     the role is not evidence that the probe can run under it.
--   * Verifying project identity and creating this role are separate operations
--     with separate authorization requirements.
--
-- TARGET SCOPE (revised): the DESIGNATED STAGING project only, run by an
-- administrator of that project. This file previously said "DEVELOPMENT project
-- only"; that instruction is superseded because the probe role now exists to
-- read the staging database. Development is no longer a supported target for
-- this role.
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
--     business data.
--   * steps 6-8 are read-only verification queries.
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

-- 0. Confirm the target before running anything.
--    This file is intended for the DESIGNATED STAGING project ONLY.
--    current_database() returns 'postgres' on every Supabase project, so it does
--    NOT identify the project. The operator must open the Supabase dashboard for
--    the project actually connected to, read that project's reference, and
--    confirm it matches the approved POS_STAGING_PROJECT_REF. That comparison is
--    manual and is the only accepted project-identity evidence at this point.
--    Never run this file against production, and do not infer the project from
--    the pooler hostname either: the pooler is shared by every project in a
--    region. This query only prints the executing account and database name.
select current_user, current_database();

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
--    public is NOT granted. ProbeState reads pg_catalog, which needs no schema
--    USAGE. No table privileges are granted anywhere.
grant usage on schema supabase_migrations to pos_probe_readonly;

-- 5. Migration history: only the two columns the probe prints.
--    `statements` (raw SQL text) and `created_by` are deliberately NOT granted.
grant select (version, name) on supabase_migrations.schema_migrations to pos_probe_readonly;

-- 6. Verification (read-only). Expected result: every column is false/none.
--    Run after steps 1-5 to confirm the role holds nothing it should not.
select
  has_schema_privilege('pos_probe_readonly', 'auth', 'USAGE')                              as auth_schema_usage,
  has_schema_privilege('pos_probe_readonly', 'public', 'USAGE')                           as public_schema_usage,
  has_table_privilege('pos_probe_readonly', 'auth.users', 'SELECT')                        as auth_users_select,
  has_table_privilege('pos_probe_readonly', 'auth.identities', 'SELECT')                   as auth_identities_select,
  has_table_privilege('pos_probe_readonly', 'public.profiles', 'SELECT')                   as profiles_select,
  has_table_privilege('pos_probe_readonly', 'public.customers', 'SELECT')                  as customers_select,
  has_table_privilege('pos_probe_readonly', 'public.sales', 'SELECT')                      as sales_select,
  has_table_privilege('pos_probe_readonly', 'public.payments', 'SELECT')                   as payments_select,
  has_column_privilege('pos_probe_readonly', 'supabase_migrations.schema_migrations', 'statements', 'SELECT') as migration_statements_select,
  has_schema_privilege('pos_probe_readonly', 'public', 'CREATE')                           as public_create,
  (select rolsuper or rolcreaterole or rolcreatedb or rolreplication or rolbypassrls
     from pg_roles where rolname = 'pos_probe_readonly')                                   as elevated_attribute;

-- 7. Login and membership checks. Expected: can_login = true, memberships = none.
select
  (select rolcanlogin from pg_roles where rolname = 'pos_probe_readonly')                 as can_login,
  (select string_agg(r.rolname, ',' order by r.rolname)
     from pg_auth_members m
     join pg_roles r on r.oid = m.roleid
     join pg_roles u on u.oid = m.member
     where u.rolname = 'pos_probe_readonly')                                             as memberships;

-- 8. Table-level grants held by the role (should be none in public or auth).
--    Uses pg_class ACLs: information_schema.role_table_grants only shows grants
--    where the querying user is grantor or grantee, so it would hide these.
--    Column-level grants (the migration columns in step 5) are NOT listed here;
--    step 6 checks those.
select n.nspname as table_schema, c.relname as table_name, a.privilege_type
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
join lateral aclexplode(c.relacl) a on true
where a.grantee = (select oid from pg_roles where rolname = 'pos_probe_readonly')
  and n.nspname in ('public', 'auth', 'supabase_migrations')
order by 1, 2, 3;
