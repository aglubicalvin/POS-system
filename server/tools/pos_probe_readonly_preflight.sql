-- Preflight review gate for executable SECURITY DEFINER functions.
--
-- Runs BEFORE pos_probe_readonly.sql, in the same psql --single-transaction with
-- ON_ERROR_STOP=1, so a refusal aborts the whole run and no role is created. The
-- wrapper refuses a non-zero psql exit and preserves its status.
--
-- WHY THIS EXISTS
--   PostgreSQL grants EXECUTE on every new function to PUBLIC by default, so the
--   proposed account inherits EXECUTE on functions it was never granted. A
--   SECURITY DEFINER function runs with its owner's privileges, and a read-only
--   transaction does not stop a function from RETURNING protected data. Listing
--   the functions is not a control: the account can call all of them. Creation
--   therefore stops until each one has been reviewed.
--
-- POLICY: FAIL CLOSED, NO BUILT-IN ALLOWLIST
--   Every SECURITY DEFINER function the account can execute must be listed by
--   exact identity -- schema.function(argtypes), as produced by
--   pg_get_function_identity_arguments -- in the `reviewed_security_definer`
--   psql variable. probe-readonly.ps1 fills it from
--   POS_REVIEWED_SECURITY_DEFINER_FUNCTIONS. There is no implicit allowance for
--   Supabase-owned or platform functions, and no "same schema as the app"
--   shortcut: those functions require the same individual review.
--
--   An allowlist entry that matches no executable SECURITY DEFINER function is
--   also refused, so a typo or a removed function cannot leave review coverage
--   silently wider than it looks.
--
-- IDENTITY AND OVERLOADS
--   Identities include the identity argument list, so overloads are reviewed
--   separately. Entries are separated by ';'. Argument lists contain commas, so
--   ',' is not a separator; they may not contain ';'.
--
-- EFFECTIVE VS DIRECT PRIVILEGES
--   Before the role exists the only applicable path is inheritance from PUBLIC:
--   a NULL ACL means the default PUBLIC EXECUTE, an explicit ACL means checking
--   for grantee 0. When the role already exists (a re-run, or the post-creation
--   assertion), has_function_privilege covers PUBLIC and every other grant path,
--   and a DIRECT grant to the account is refused outright -- the reviewed SQL
--   never grants function execution, so a direct grant came from somewhere that
--   was not reviewed with this file.
--
-- REPORTING
--   A refusal names the schema, the exact function identity and the reason
--   (unreviewed, inherited from PUBLIC, or a direct grant), capped at five
--   entries plus a count. Nothing else about the database is printed: no table,
--   column, role or extension inventory.
--
-- The wrapper always defines `reviewed_security_definer` (possibly empty), so
-- the psql variable is required, never defaulted to "everything is reviewed".

select set_config('pos.reviewed_security_definer', :'reviewed_security_definer', false);

do $$
declare
  role_name constant text := 'pos_probe_readonly';
  role_oid oid;
  reviewed text[] := '{}';
  seen text[] := '{}';
  entry text;
  offenders text[] := '{}';
  stale text[] := '{}';
  reviewed_executable int := 0;
  unreviewed int := 0;
  detail text;
  rec record;
  identity text;
begin
  select oid into role_oid from pg_roles where rolname = role_name;

  foreach entry in array string_to_array(
      coalesce(current_setting('pos.reviewed_security_definer', true), ''), ';')
  loop
    entry := btrim(entry);
    if entry <> '' then
      reviewed := array_append(reviewed, entry);
    end if;
  end loop;

  for rec in
    select n.nspname as schema_name,
           p.proname as function_name,
           pg_get_function_identity_arguments(p.oid) as identity_args,
           -- What the account can actually execute. Before creation only PUBLIC
           -- can grant that; afterwards the role's full effective privilege is
           -- what matters.
           (case
              when role_oid is not null then has_function_privilege(role_oid, p.oid, 'EXECUTE')
              when p.proacl is null then true
              else exists (select 1 from aclexplode(p.proacl) a
                           where a.grantee = 0 and a.privilege_type = 'EXECUTE')
            end) as executable,
           (role_oid is not null and exists (
              select 1 from aclexplode(p.proacl) a
              where a.grantee = role_oid and a.privilege_type = 'EXECUTE')) as direct_grant,
           (case
              when p.proacl is null then true
              else exists (select 1 from aclexplode(p.proacl) a
                           where a.grantee = 0 and a.privilege_type = 'EXECUTE')
            end) as public_grant
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where p.prosecdef
      and n.nspname <> 'information_schema'
      and n.nspname !~ '^pg_'
    order by 1, 2, 3
  loop
    if not rec.executable then
      continue;
    end if;

    identity := rec.schema_name || '.' || rec.function_name
      || '(' || rec.identity_args || ')';

    if rec.direct_grant then
      unreviewed := unreviewed + 1;
      if coalesce(array_length(offenders, 1), 0) < 5 then
        offenders := array_append(offenders,
          identity || ' (direct grant to ' || role_name || ')');
      end if;
      continue;
    end if;

    if identity = any(reviewed) then
      reviewed_executable := reviewed_executable + 1;
      seen := array_append(seen, identity);
      continue;
    end if;

    unreviewed := unreviewed + 1;
    if coalesce(array_length(offenders, 1), 0) < 5 then
      offenders := array_append(offenders,
        identity || (case when rec.public_grant
                          then ' (inherited EXECUTE from PUBLIC)'
                          else ' (effective EXECUTE)' end));
    end if;
  end loop;

  if unreviewed > 0 then
    detail := array_to_string(offenders, ', ');
    if unreviewed > coalesce(array_length(offenders, 1), 0) then
      detail := detail || ' and '
        || (unreviewed - coalesce(array_length(offenders, 1), 0)) || ' more';
    end if;
    raise exception 'pos_probe_readonly preflight refused: % unreviewed SECURITY DEFINER function(s) executable by %: %',
      unreviewed, role_name, detail;
  end if;

  foreach entry in array reviewed loop
    if not (entry = any(seen)) then
      stale := array_append(stale, entry);
    end if;
  end loop;

  if coalesce(array_length(stale, 1), 0) > 0 then
    raise exception 'pos_probe_readonly preflight refused: allowlist entries match no executable SECURITY DEFINER function: %',
      array_to_string(stale, ', ');
  end if;

  raise notice 'pos_probe_readonly preflight passed: % reviewed SECURITY DEFINER function(s) executable',
    reviewed_executable;
end
$$;
