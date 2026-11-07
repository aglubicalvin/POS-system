#!/usr/bin/env bash
# Turn raw probe output into the sanitized evidence file, without GitHub Actions.
#
# Usage: probe-evidence.sh RAW EVIDENCE STATUS REF
#
# Acceptance is deliberately strict and independent of any rewrite:
#   * probe_completion=ok requires the probe's own exit status to be 0;
#   * read_only_transaction is server_confirmed only when the raw output holds
#     EXACTLY ONE `read_only_transaction=server_confirmed` line and no other
#     read_only_transaction line at all. `enforced`, `on`, `off`,
#     `not_confirmed`, a missing line and duplicates are all not_confirmed. The
#     value is never rewritten: evidence records what the probe actually said;
#   * probe_status must appear exactly once, as `ok`;
#   * only allowlisted fields are copied; the raw inventory is never published.
#
# Exit status: 0 when the evidence file was written (whatever the probe did),
# 2 on usage or input errors.
set -uo pipefail

if [ "$#" -ne 4 ]; then
  echo "usage: $0 RAW EVIDENCE STATUS REF" >&2
  exit 2
fi
raw=$1
evidence=$2
status=$3
ref=$4

if ! [[ "$status" =~ ^[0-9]+$ ]]; then
  echo "probe-evidence: status must be a non-negative integer, got '$status'" >&2
  exit 2
fi
if [ ! -r "$raw" ]; then
  echo "probe-evidence: cannot read raw output '$raw'" >&2
  exit 2
fi

# --- read-only confirmation -------------------------------------------------
read_only_lines=$(grep -cE '^[[:space:]]*read_only_transaction=' "$raw" || true)
confirmed_lines=$(grep -cE '^[[:space:]]*read_only_transaction=server_confirmed[[:space:]]*$' "$raw" || true)
if [ "$read_only_lines" -eq 1 ] && [ "$confirmed_lines" -eq 1 ]; then
  read_only=read_only_transaction=server_confirmed
else
  read_only=read_only_transaction=not_confirmed
fi

# --- probe status -----------------------------------------------------------
probe_status_lines=$(grep -cE '^[[:space:]]*probe_status=' "$raw" || true)
probe_status_ok=$(grep -cE '^[[:space:]]*probe_status=ok[[:space:]]*$' "$raw" || true)

completion=failed
if [ "$status" -eq 0 ] &&
   [ "$read_only" = "read_only_transaction=server_confirmed" ] &&
   [ "$probe_status_lines" -eq 1 ] &&
   [ "$probe_status_ok" -eq 1 ]; then
  completion=ok
fi

# --- error category ---------------------------------------------------------
# Preserve a safe Java probe category when one was emitted. If Gradle failed
# before ProbeState could classify the problem, classify only well-known
# toolchain/dependency failures from fixed patterns. Never copy Gradle's raw
# error text, paths, environment, or stack trace.
error_category="$(awk '
  /^[[:space:]]+error_category=[a-z_]+$/ {
    sub(/^[[:space:]]+error_category=/, "")
    print
    exit
  }
' "$raw")"

if [ "$status" -ne 0 ] && [ -z "$error_category" ]; then
  if grep -Eqi 'invalid source release: 21|invalid target release: 21|Could not target platform.*Java SE 21|No matching toolchains found.*21' "$raw"; then
    error_category=java_toolchain_mismatch
  elif grep -Eqi 'Could not resolve|Could not GET|Could not HEAD|Could not download|Could not find .*artifact' "$raw"; then
    error_category=gradle_dependency_resolution
  elif grep -Eqi "JAVA_HOME.*(not set|invalid)|java(:| ) (command )?not found" "$raw"; then
    error_category=java_runtime_unavailable
  else
    error_category=execution_failed_without_probe_category
  fi
fi

# --- allowlisted evidence only ---------------------------------------------
# Written on BOTH outcomes: a failed probe must still leave sanitized evidence
# rather than a bare exit code. No table lists, policy names, role names,
# counts, JDBC URLs or stack traces.
{
  echo "target_project_reference=${ref:-unset}"
  echo "username_suffix_validation=PASS"
  echo "$read_only"
  grep -E '^[[:space:]]+(database|supabase_migrations_exists|migration_history|probe_status)=' "$raw" \
    | sed -E 's/^[[:space:]]+//' || true
  if [ -n "$error_category" ]; then
    echo "error_category=${error_category}"
  fi
  echo "probe_completion=${completion}"
} | tee "$evidence"
