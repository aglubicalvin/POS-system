-- pos_probe_readonly: least-privilege role for server/tools/ProbeState.java
--
-- STATUS: PROPOSED. NOT EXECUTED. Requires explicit manual approval.
-- Run once by a project administrator, in the DEVELOPMENT project only.
-- Target project reference must be confirmed before running (see step 0).
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

-- 0. Confirm the target before running anything. current_database() returns
--    'postgres' on every Supabase project, so it does NOT identify the project.
--    Check the project ref in the Supabase dashboard you are logged into, and
--    check it matches the approved DEVELOPMENT ref. This query only prints the
--    executing account; the project check is manual.
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

-- 4. May see schema names. No table privileges are granted anywhere.
grant usage on schema public to pos_probe_readonly;
grant usage on schema supabase_migrations to pos_probe_readonly;

-- 5. Migration history: only the two columns the probe prints.
--    `statements` (raw SQL text) and `created_by` are deliberately NOT granted.
grant select (version, name) on supabase_migrations.schema_migrations to pos_probe_readonly;

-- 6. Verification (read-only). Expected result: every column is false/none.
--    Run after steps 1-5 to confirm the role holds nothing it should not.
select
  has_schema_privilege('pos_probe_readonly', 'auth', 'USAGE')                              as auth_schema_usage,
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
