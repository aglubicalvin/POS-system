-- Assertions for pos_probe_readonly. Run AFTER pos_probe_readonly.sql.
-- Raises an exception (non-zero psql exit with ON_ERROR_STOP=1) if any check fails.
-- Read-only: no DDL or DML.

do $$
declare
  r record;
  f record;
  bad text[] := '{}';
  reviewed text[] := '{}';
  entry text;
  reviewed_count int := 0;
  offenders text[] := '{}';
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
  -- Supabase grants USAGE on the public schema to PUBLIC, so the role inherits
  -- it. That is the platform baseline, not a grant made by this role's SQL,
  -- and it grants no access to any object by itself. Accepting it is not a
  -- weakened assertion: what this SQL must not do is create a DIRECT grant
  -- that would outlive a change to the baseline, or let the role create
  -- anything in the schema.
  if has_schema_privilege('pos_probe_readonly', 'public', 'CREATE') then
    bad := array_append(bad, 'CREATE on public schema');
  end if;
  if exists (
    select 1
    from pg_namespace n, aclexplode(n.nspacl) a
    where n.nspname = 'public' and a.grantee = r.oid
  ) then
    bad := array_append(bad, 'direct ACL entry for the role on public schema');
  end if;
  if has_schema_privilege('pos_probe_readonly', 'auth', 'USAGE') then
    bad := array_append(bad, 'USAGE on auth schema');
  end if;
  if not has_schema_privilege('pos_probe_readonly', 'supabase_migrations', 'USAGE') then
    bad := array_append(bad, 'USAGE on supabase_migrations schema missing');
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

  -- Functions: PostgreSQL grants EXECUTE to PUBLIC on every new function, so
  -- the role inherits EXECUTE on the platform's functions. Supabase cannot
  -- revoke that without breaking the services that depend on it, so inherited
  -- EXECUTE is accepted. A DIRECT grant to this role is not.
  if exists (
    select 1
    from pg_proc p
    cross join aclexplode(p.proacl) a
    where a.grantee = r.oid and a.privilege_type = 'EXECUTE'
  ) then
    bad := array_append(bad, 'direct EXECUTE grant on a function');
  end if;

  -- SECURITY DEFINER review gate, post-creation. The preflight already refused
  -- the run before the account existed; this repeats the comparison with the
  -- role that actually exists, so PUBLIC-inherited EXECUTE and a direct grant
  -- are both judged as effective privileges. Printing the function names was
  -- never a control; this fails the assertions instead. The reviewed allowlist
  -- is the same POS_REVIEWED_SECURITY_DEFINER_FUNCTIONS value, passed as the
  -- reviewed_security_definer psql variable.
  foreach entry in array string_to_array(
      coalesce(current_setting('pos.reviewed_security_definer', true), ''), ';')
  loop
    entry := btrim(entry);
    if entry <> '' then
      reviewed := array_append(reviewed, entry);
    end if;
  end loop;

  for f in
    select n.nspname || '.' || p.proname
             || '(' || pg_get_function_identity_arguments(p.oid) || ')' as identity
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where p.prosecdef
      and n.nspname <> 'information_schema' and n.nspname !~ '^pg_'
      and has_function_privilege('pos_probe_readonly', p.oid, 'EXECUTE')
    order by 1
  loop
    if f.identity = any(reviewed) then
      reviewed_count := reviewed_count + 1;
    else
      offenders := array_append(offenders, f.identity);
    end if;
  end loop;

  if coalesce(array_length(offenders, 1), 0) > 0 then
    bad := array_append(bad,
      'unreviewed SECURITY DEFINER function executable by pos_probe_readonly: '
      || array_to_string(offenders[1:5], ', '));
  end if;

  raise notice 'SECURITY DEFINER functions reviewed for pos_probe_readonly: %', reviewed_count;

  if array_length(bad, 1) is not null then
    raise exception 'pos_probe_readonly assertions failed: %', array_to_string(bad, '; ');
  end if;

  raise notice 'pos_probe_readonly assertions passed';
end
$$;
