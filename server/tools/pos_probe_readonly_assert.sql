-- Assertions for pos_probe_readonly. Run AFTER pos_probe_readonly.sql.
-- Raises an exception (non-zero psql exit with ON_ERROR_STOP=1) if any check fails.
-- Read-only: no DDL or DML.

do $$
declare
  r record;
  bad text[] := '{}';
begin
  select * into r from pg_roles where rolname = 'pos_probe_readonly';
  if not found then
    raise exception 'role pos_probe_readonly does not exist';
  end if;

  if r.rolsuper or r.rolcreaterole or r.rolcreatedb or r.rolreplication or r.rolbypassrls then
    bad := array_append(bad, 'elevated attribute set');
  end if;
  if not r.rolcanlogin then
    bad := array_append(bad, 'LOGIN not enabled');
  end if;
  if exists (
    select 1 from pg_auth_members m join pg_roles u on u.oid = m.member
    where u.rolname = 'pos_probe_readonly'
  ) then
    bad := array_append(bad, 'role has memberships');
  end if;

  if has_table_privilege('pos_probe_readonly', 'auth.users', 'SELECT')
     or has_table_privilege('pos_probe_readonly', 'auth.identities', 'SELECT')
     or has_table_privilege('pos_probe_readonly', 'public.profiles', 'SELECT')
     or has_table_privilege('pos_probe_readonly', 'public.customers', 'SELECT')
     or has_table_privilege('pos_probe_readonly', 'public.sales', 'SELECT')
     or has_table_privilege('pos_probe_readonly', 'public.payments', 'SELECT') then
    bad := array_append(bad, 'business or auth table SELECT granted');
  end if;
  if has_column_privilege('pos_probe_readonly', 'supabase_migrations.schema_migrations', 'statements', 'SELECT')
     or has_column_privilege('pos_probe_readonly', 'supabase_migrations.schema_migrations', 'created_by', 'SELECT') then
    bad := array_append(bad, 'migration statements or created_by column readable');
  end if;
  if not has_column_privilege('pos_probe_readonly', 'supabase_migrations.schema_migrations', 'version', 'SELECT')
     or not has_column_privilege('pos_probe_readonly', 'supabase_migrations.schema_migrations', 'name', 'SELECT') then
    bad := array_append(bad, 'migration version or name column not readable (probe needs both)');
  end if;
  if exists (
    select 1 from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    join lateral aclexplode(c.relacl) a on true
    where a.grantee = r.oid
      and n.nspname in ('public', 'auth', 'supabase_migrations')
  ) then
    bad := array_append(bad, 'table-level ACL entry on public, auth or supabase_migrations');
  end if;
  if has_schema_privilege('pos_probe_readonly', 'public', 'USAGE') then
    bad := array_append(bad, 'USAGE on public schema');
  end if;
  if has_schema_privilege('pos_probe_readonly', 'auth', 'USAGE') then
    bad := array_append(bad, 'USAGE on auth schema');
  end if;
  if not has_schema_privilege('pos_probe_readonly', 'supabase_migrations', 'USAGE') then
    bad := array_append(bad, 'USAGE on supabase_migrations schema missing');
  end if;
  if has_schema_privilege('pos_probe_readonly', 'public', 'CREATE') then
    bad := array_append(bad, 'CREATE on public schema');
  end if;
  -- Check effective privileges, including grants to PUBLIC, on all non-system
  -- tables and views. Table-level SELECT is not allowed even on migrations.
  if exists (
    select 1 from pg_class c join pg_namespace n on n.oid = c.relnamespace
    cross join (values ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE'),
                       ('TRUNCATE'), ('REFERENCES'), ('TRIGGER')) p(priv)
    where c.relkind in ('r', 'p', 'v', 'm', 'f')
      and n.nspname <> 'information_schema' and n.nspname !~ '^pg_'
      and has_table_privilege(r.oid, c.oid, p.priv)
  ) then
    bad := array_append(bad, 'unexpected effective table privilege');
  end if;
  if exists (
    select 1 from pg_attribute att
    join pg_class c on c.oid = att.attrelid
    join pg_namespace n on n.oid = c.relnamespace
    cross join (values ('SELECT'), ('INSERT'), ('UPDATE'), ('REFERENCES')) p(priv)
    where c.relkind in ('r', 'p', 'v', 'm', 'f')
      and n.nspname <> 'information_schema' and n.nspname !~ '^pg_'
      and att.attnum > 0 and not att.attisdropped
      and has_column_privilege(r.oid, c.oid, att.attnum, p.priv)
      and not (n.nspname = 'supabase_migrations' and c.relname = 'schema_migrations'
               and att.attname in ('version', 'name') and p.priv = 'SELECT')
  ) then
    bad := array_append(bad, 'unexpected effective column privilege');
  end if;

  if array_length(bad, 1) is not null then
    raise exception 'pos_probe_readonly assertions failed: %', array_to_string(bad, '; ');
  end if;

  raise notice 'pos_probe_readonly assertions passed';
end
$$;
