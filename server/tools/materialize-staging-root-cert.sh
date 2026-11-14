#!/usr/bin/env bash
#
# Materialise the staged staging root certificate for a workflow run.
#
# Both staging workflows run on ephemeral GitHub-hosted runners, so a CA path
# stored in repository or environment configuration cannot be assumed to exist.
# This script writes the certificate to a private temp file on the runner,
# verifies its DER SHA-256 against the separately configured fingerprint, and
# exports ONLY the resulting path (via $GITHUB_ENV) to the existing Java and
# PowerShell code. Certificate bytes, the base64 value and the fingerprint are
# never printed.
#
# Inputs (both required; there is no default and no fallback):
#   POS_STAGING_DB_ROOT_CERT_B64     base64 of the PEM certificate
#   POS_STAGING_DB_ROOT_CERT_SHA256  SHA-256 of the certificate's DER encoding,
#                                    obtained independently of the certificate
#
# Outputs:
#   $RUNNER_TEMP/staging-root-ca.pem (mode 0600)
#   POS_STAGING_DB_ROOT_CERT=<that path> in $GITHUB_ENV, when set
#
# Exit status: 0 = materialised and verified, 1 = refused. Nothing is written to
# a location the workflow did not name, and the file is removed by the caller's
# `if: always()` cleanup step.

set -euo pipefail

fail() {
  echo "::error::staging root certificate: $*" >&2
  exit 1
}

if [ -z "${POS_STAGING_DB_ROOT_CERT_B64:-}" ]; then
  fail "POS_STAGING_DB_ROOT_CERT_B64 is not set. The staging connection cannot be made without the staged CA."
fi
if [ -z "${POS_STAGING_DB_ROOT_CERT_SHA256:-}" ]; then
  fail "POS_STAGING_DB_ROOT_CERT_SHA256 is not set. A staged CA without an independent fingerprint proves nothing."
fi

target_dir="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
cert_path="$target_dir/staging-root-ca.pem"

# 0600: the private key material of a trust anchor is not secret, but a runner
# temp file should still not be world readable.
umask 077

# --- decode the base64 payload into a PEM file -------------------------------
if ! printf '%s' "$POS_STAGING_DB_ROOT_CERT_B64" | tr -d ' \t\r\n' | base64 -d >"$cert_path" 2>/dev/null; then
  rm -f -- "$cert_path"
  fail "the base64 certificate value could not be decoded."
fi

grep -q -- '-----BEGIN CERTIFICATE-----' "$cert_path" ||
  { rm -f -- "$cert_path"; fail "the decoded value is not a PEM certificate."; }
grep -q -- '-----END CERTIFICATE-----' "$cert_path" ||
  { rm -f -- "$cert_path"; fail "the decoded value is not a complete PEM certificate."; }

# --- verify the independent fingerprint over the DER encoding ----------------
if ! actual_sha256="$(
  sed -e '/-----/d' "$cert_path" |
    tr -d ' \t\r\n' |
    base64 -d 2>/dev/null |
    sha256sum |
    cut -d' ' -f1
)"; then
  rm -f -- "$cert_path"
  fail "the PEM body could not be decoded to DER."
fi

supplied_sha256="$(printf '%s' "$POS_STAGING_DB_ROOT_CERT_SHA256" | tr -d ': \t\r\n' | tr 'A-F' 'a-f')"

if [ "$actual_sha256" != "$supplied_sha256" ]; then
  rm -f -- "$cert_path"
  fail "the certificate does not match POS_STAGING_DB_ROOT_CERT_SHA256. It may have been rotated, or this is not the approved staging CA."
fi

chmod 600 "$cert_path"

if [ -n "${GITHUB_ENV:-}" ]; then
  printf 'POS_STAGING_DB_ROOT_CERT=%s\n' "$cert_path" >>"$GITHUB_ENV"
fi

# Path only: never the certificate, never the fingerprint.
echo "Staged root certificate materialized at $cert_path and verified against the independent fingerprint."
