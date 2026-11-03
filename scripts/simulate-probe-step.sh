#!/usr/bin/env bash
# Local simulation of the "Run read-only staging identity probe" step.
# Runs the step's own body under `bash -eo pipefail` against a stub ./gradlew.
# No database, network or GitHub call is made.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Lift the step body out of the workflow so the simulation cannot drift from it.
#
# The dedent is deliberately strict: `sed 's/^          //'` treats its argument
# as a BRE, where `*` applies to the preceding SPACE, so it strips exactly one
# space and leaves the rest — indented evidence lines then fail exact matching
# (`read_only_transaction=server_confirmed` reads as not-confirmed). The
# positive character class below strips the block indent
# unambiguously.
sed -n '/Run read-only staging identity probe/,/Publish sanitized evidence/p' \
  "$root/.github/workflows/probe-staging-identity.yml" \
  | sed '1,/run: |/d' \
  | sed '/Publish sanitized evidence/,$d' \
  | sed 's/^[[:space:]]\{10\}//' > "$work/step.sh"

grep -qx 'if ./gradlew -q execProbeState > probe-raw.txt 2>&1; then' "$work/step.sh" || {
  echo "SIMULATION ABORTED: extracted step body does not look right" >&2
  exit 99
}

simulate() {
  label="$1"; output="$2"; want_status="$3"
  dir="$work/$label"; mkdir -p "$dir/server"
  printf '%s\n' "$output" > "$dir/probe-output.txt"
  {
    echo '#!/usr/bin/env bash'
    echo "cat '$dir/probe-output.txt'"
    echo "exit $want_status"
  } > "$dir/server/gradlew"
  (
    cd "$dir" || exit 98
    bash -eo pipefail ../step.sh > step.stdout 2>&1
  )
  status=$?
  echo "== $label =="
  echo "step exit:  $status (probe returned $want_status)"
  echo "evidence:"
  sed 's/^/  /' "$dir/server/probe-evidence.txt" 2>/dev/null || echo "  (none written)"
  if [ -e "$dir/server/probe-raw.txt" ]; then
    echo "raw file:   STILL PRESENT (FAIL)"
  else
    echo "raw file:   deleted"
  fi
  [ "$status" -eq "$want_status" ] && echo "exit code:  preserved" || echo "exit code:  NOT PRESERVED (FAIL)"
  echo
}

simulate success '== read-only state probe ==
  read_only_transaction=server_confirmed
  database=postgres
  supabase_migrations_exists=true
  migration_history=9 rows from supabase_migrations.schema_migrations
  probe_status=ok' 0

simulate failure '== read-only state probe ==
  probe_status=error
  error_category=connection_failed
  error_detail=sqlstate=08001 message=Connection to <host> refused.' 5

simulate read-only-unconfirmed '== read-only state probe ==
  probe_status=error
  error_category=read_only_not_confirmed
  error_detail=transaction_read_only=off' 6

simulate no-session '== read-only state probe ==
  database=postgres
  probe_status=ok' 0
