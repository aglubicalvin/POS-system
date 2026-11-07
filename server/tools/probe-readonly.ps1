# Creates/verifies the pos_probe_readonly role on the DESIGNATED STAGING project.
# Dry run by default. Pass -Apply to execute. Nothing is run without it.
#
# There is no default target. The probe refuses to run until the project owner
# designates the staging project through POS_STAGING_PROJECT_REF. The shared
# pooler hostname is not project identity, so the only accepted evidence is the
# administrator username suffix matching that reference exactly.
#
# Required environment (never put these in a file or in Git):
#   POS_STAGING_PROJECT_REF  designated staging project reference (no default)
#   POS_STAGING_POOLER_HOST  pooler hostname from the shared staging contract
#   POS_STAGING_DB_ROOT_CERT the staged root certificate (file path)
#   POS_STAGING_DB_ROOT_CERT_SHA256  SHA-256 of that certificate's DER encoding
#   PROBE_ADMIN_USER         e.g. postgres.<ref> (must match the reference, case-sensitively)
#   PROBE_ADMIN_PASS         admin password
#   PROBE_PW                 password to set for pos_probe_readonly
#   POS_REVIEWED_SECURITY_DEFINER_FUNCTIONS  optional; ';'-separated exact
#       identities (schema.function(argtypes)) of individually reviewed
#       SECURITY DEFINER functions. Blank means nothing is reviewed, which makes
#       the preflight refuse any executable SECURITY DEFINER function. There is
#       no built-in allowlist and no platform exemption.
#
# TLS and pooler rules (enforced here and in the Java ProbeState path):
#   - The pooler hostname is the SAME value the JDBC identity-probe path consumes;
#     it is validated against the shared staging contract first, so both paths use
#     exactly one approved pooler endpoint and no region is inferred from the
#     project reference or from any previously used endpoint.
#   - sslmode=verify-full only. There is no prefer/require/allow/disable fallback.
#   - The staged root certificate is verified against the independently supplied
#     SHA-256 over its DER encoding before anything is run; a mismatch refuses the
#     target. libpq inside Docker then reads the certificate from a fixed
#     container path (/certs/ca.pem) backed by a read-only mount of a private temp
#     copy, never from a host path, so the staged CA is the one libpq trusts. The
#     copy is deleted before exit on success and failure and is never printed.
#
# EXECUTION ORDER (one psql --single-transaction, ON_ERROR_STOP=1):
#   pos_probe_readonly_preflight.sql  refuses unreviewed SECURITY DEFINER functions
#   pos_probe_readonly.sql            creates the role and its least privileges
#   pos_probe_readonly_password.sql   \password, hashed client-side by psql
#   pos_probe_readonly_assert.sql     post-creation assertions, including the gate
# A refusal in any file aborts the transaction, so no role is left behind.
#
# EVIDENCE: the raw psql transcript is sensitive and is NOT teed to the workflow
# log or uploaded. It is captured to a private temp file, reduced to the
# allowlisted summary in server/tools/probe-role-evidence.ps1
# (server/build/probe-readonly-evidence.txt), and the transcript is deleted on
# success and failure. Unexpected transcript output is rejected, not forwarded.
# The original psql exit status is preserved so a failed run cannot look green.

param([switch]$Apply)

$ErrorActionPreference = 'Stop'
$ServerDir = Split-Path -Parent $PSScriptRoot
$Sql = Join-Path $PSScriptRoot 'pos_probe_readonly.sql'
$Assert = Join-Path $PSScriptRoot 'pos_probe_readonly_assert.sql'
$Preflight = Join-Path $PSScriptRoot 'pos_probe_readonly_preflight.sql'
$EvidenceFile = Join-Path $ServerDir 'build/probe-readonly-evidence.txt'
. (Join-Path $PSScriptRoot 'probe-role-evidence.ps1')

# Fail closed: the staging project must be designated explicitly, and the three
# shared-staging values must be present. Known non-staging projects are refused.
$StagingRef = $env:POS_STAGING_PROJECT_REF
if ([string]::IsNullOrWhiteSpace($StagingRef)) {
    throw 'BLOCKED: POS_STAGING_PROJECT_REF has not been explicitly configured. The staging project must be designated by the project owner.'
}
$StagingRef = $StagingRef.Trim()
if ($StagingRef -notmatch '^[a-z0-9]{20}$') {
    throw 'BLOCKED: POS_STAGING_PROJECT_REF is not a valid Supabase project reference.'
}
# Immutable built-ins, plus POS_NON_STAGING_REFS which is ADDITIVE: a blank or
# empty custom value never removes them. Entries are trimmed, lower-cased and
# de-duplicated; a malformed entry is refused rather than silently ignored.
if ($StagingRef -cne $StagingRef.ToLowerInvariant()) {
    throw 'BLOCKED: POS_STAGING_PROJECT_REF is not a valid Supabase project reference.'
}
$KnownNonStaging = @('uemdrtrzlvhdtonccczt', 'numwzjogaanfhlrfgrqk')
$CustomNonStaging = @()
if (-not [string]::IsNullOrWhiteSpace($env:POS_NON_STAGING_REFS)) {
    $CustomNonStaging = @($env:POS_NON_STAGING_REFS -split ',' |
        ForEach-Object { $_.Trim().ToLowerInvariant() } |
        Where-Object { $_ })
    foreach ($entry in $CustomNonStaging) {
        if ($entry -notmatch '^[a-z0-9]{20}$') {
            throw 'BLOCKED: POS_NON_STAGING_REFS contains an entry that is not a Supabase project reference. Refusing to guess which projects are non-staging.'
        }
    }
}
$DeniedRefs = @($KnownNonStaging + $CustomNonStaging | Select-Object -Unique)
if ($DeniedRefs -contains $StagingRef) {
    throw 'BLOCKED: POS_STAGING_PROJECT_REF names a known non-staging project. Set the dedicated staging reference.'
}

$PoolerHost = $env:POS_STAGING_POOLER_HOST
if ([string]::IsNullOrWhiteSpace($PoolerHost)) {
    throw 'BLOCKED: POS_STAGING_POOLER_HOST has not been explicitly configured. The staging connection must use an approved Supabase pooler hostname; there is no default, no region inference, and no fallback to a previously used endpoint.'
}
$Normalized = $PoolerHost.Trim().ToLowerInvariant()
# Exactly the shared grammar: identical to SUPABASE_POOLER_HOST_PATTERN in
# src/config/staging-target.ts, StagingPoolerHost.PATTERN in Kotlin and
# POOLER_HOST_PATTERN in ProbeState.java. The vector table in
# server/tools/staging-pooler-host-vectors.txt is asserted against all four.
if ($Normalized -notmatch '^aws-[0-9]{1,3}-[a-z]{2,}(?:-[a-z0-9]+)*\.pooler\.supabase\.com$') {
    throw 'BLOCKED: POS_STAGING_POOLER_HOST does not name an approved Supabase pooler endpoint. Expected exactly aws-<n>-<region>.pooler.supabase.com, with <n> one to three digits and <region> two or more lowercase letters followed by optional letter/digit/hyphen groups. IP literals, loopback names, arbitrary DNS names, URIs, paths, credentials, query strings and ports are refused.'
}

$RootCert = $env:POS_STAGING_DB_ROOT_CERT
if ([string]::IsNullOrWhiteSpace($RootCert)) {
    throw 'BLOCKED: POS_STAGING_DB_ROOT_CERT has not been explicitly configured. A staged root certificate is mandatory for the staging contract.'
}
$RootCertSha256 = $env:POS_STAGING_DB_ROOT_CERT_SHA256
if ([string]::IsNullOrWhiteSpace($RootCertSha256)) {
    throw 'BLOCKED: POS_STAGING_DB_ROOT_CERT_SHA256 has not been explicitly configured. A staged certificate fingerprint is mandatory for the staging contract.'
}
if ($RootCertSha256 -notmatch '^[0-9a-fA-F]{64}$') {
    throw 'BLOCKED: POS_STAGING_DB_ROOT_CERT_SHA256 is not 64 hex characters. Verify the SHA-256 of the staged root certificate.'
}

# Verify the independently supplied fingerprint against the certificate we are
# actually about to trust, over its DER encoding -- the same rule ProbeState
# enforces. A shape check alone would accept any certificate.
if (-not (Test-Path -LiteralPath $RootCert -PathType Leaf)) {
    throw 'BLOCKED: POS_STAGING_DB_ROOT_CERT does not name a readable file.'
}
# Read the certificate ONCE. The bytes hashed below are the bytes written
# into the container later; re-reading the caller's path after verification
# would let a file replaced in between be trusted from one sequence and
# consumed from another.
try {
    $PemBytes = [System.IO.File]::ReadAllBytes($RootCert)
    $b64 = (([System.Text.Encoding]::ASCII.GetString($PemBytes)) -split "`r?`n" |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and -not $_.StartsWith('-----') }) -join ''
    $der = [Convert]::FromBase64String($b64)
} catch {
    throw 'BLOCKED: POS_STAGING_DB_ROOT_CERT is not a readable PEM certificate.'
}
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $actualSha256 = ($sha256.ComputeHash($der) | ForEach-Object { $_.ToString('x2') }) -join ''
} finally {
    $sha256.Dispose()
}
$der = $null
if ($actualSha256 -ne $RootCertSha256.Trim().Replace(':', '').ToLowerInvariant()) {
    throw 'BLOCKED: the SHA-256 of the staged root certificate does not match POS_STAGING_DB_ROOT_CERT_SHA256. The certificate may have been rotated, or this is not the approved staging target.'
}

# Case-sensitive: PowerShell's -ne is case-insensitive, so `POSTGRES.<ref>` or an
# upper-case reference suffix would have been accepted here while ProbeState and
# the SQL identity check compare exactly. -cne keeps every implementation on the
# same exact-match semantics.
if ($env:PROBE_ADMIN_USER -cne "postgres.$StagingRef") {
    throw 'BLOCKED: PROBE_ADMIN_USER does not belong to the designated staging project. Expected postgres.<POS_STAGING_PROJECT_REF>.'
}
if (-not $env:PROBE_ADMIN_PASS -or -not $env:PROBE_PW) {
    throw 'Refusing to run: PROBE_ADMIN_PASS and PROBE_PW must be set in the environment.'
}

# The reviewed SECURITY DEFINER allowlist. Absent, blank or whitespace-only means
# nothing has been individually reviewed, which fails closed on the first
# executable SECURITY DEFINER function. Quotes and line breaks are refused
# because this value is passed as one psql variable, not SQL.
$ReviewedSecurityDefiner = $env:POS_REVIEWED_SECURITY_DEFINER_FUNCTIONS
if ($null -eq $ReviewedSecurityDefiner) { $ReviewedSecurityDefiner = '' }
if ($ReviewedSecurityDefiner -match '[\r\n\x00'']') {
    throw 'Refusing to run: POS_REVIEWED_SECURITY_DEFINER_FUNCTIONS must be a single line without quotes.'
}
if ($ReviewedSecurityDefiner.Length -gt 8192) {
    throw 'Refusing to run: POS_REVIEWED_SECURITY_DEFINER_FUNCTIONS is implausibly long.'
}
$ReviewedSecurityDefiner = $ReviewedSecurityDefiner.Trim()

# The pooler hostname is the ONLY host ever printed. No cert contents, no secrets.
Write-Host "Target pooler host: $Normalized  staging project: $StagingRef  database: postgres"
Write-Host "Scripts: $Preflight , $Sql , $Assert"
Write-Host "Sanitized evidence: $EvidenceFile"

if (-not $Apply) {
    Write-Host 'DRY RUN. Re-run with -Apply to execute against the designated staging project.'
    exit 0
}

# psql's \password reads two lines from stdin and hashes client-side. Reject
# line breaks so the two prompts cannot consume different values.
if ($env:PROBE_PW -match '[\r\n\x00]') {
    throw 'Refusing to run: PROBE_PW must not contain line breaks or NUL.'
}

# The CA must exist INSIDE the container: a host path means nothing to libpq in
# Docker. Copy it into a private temp directory and mount that directory read-only
# at a fixed container path, so sslrootcert is a constant rather than an
# interpolated host path.
$CertDir = Join-Path ([System.IO.Path]::GetTempPath()) ('pos-staging-ca-' + [Guid]::NewGuid().ToString('N'))
$ContainerCertDir = '/certs'
$ContainerCert = "$ContainerCertDir/ca.pem"
$LocalCert = Join-Path $CertDir 'ca.pem'
New-Item -ItemType Directory -Path $CertDir | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path $EvidenceFile) | Out-Null

# The transcript never has a predictable name and never lives under the
# repository, so no artifact path or later step can pick it up by accident.
$RawLog = Join-Path ([System.IO.Path]::GetTempPath()) ('pos-probe-raw-' + [Guid]::NewGuid().ToString('N') + '.log')

# Build the pooler connection string from the validated staging contract only.
# No hardcoded host, no credentials, sslmode=verify-full, fixed container CA path.
$uri = "host=$Normalized port=5432 dbname=postgres user=$($env:PROBE_ADMIN_USER) sslmode=verify-full sslrootcert=$ContainerCert"

# -q -A -t: no command tags, no headers, no padding, so every line is a labelled
# evidence line the sanitizer can accept or reject exactly. The preflight runs
# first, in the same transaction, so a refusal aborts before CREATE ROLE.
$dockerArgs = @(
    'run', '--rm', '-i',
    '-e', 'PGPASSWORD',
    '-v', "$PSScriptRoot`:/work:ro",
    '-v', "$($CertDir):$($ContainerCertDir):ro",
    'postgres:17',
    'psql', $uri, '--single-transaction', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1',
    '-v', "reviewed_security_definer=$ReviewedSecurityDefiner",
    '-v', "admin_user=$($env:PROBE_ADMIN_USER)",
    '-f', '/work/pos_probe_readonly_preflight.sql',
    '-f', '/work/pos_probe_readonly.sql',
    '-f', '/work/pos_probe_readonly_password.sql',
    '-f', '/work/pos_probe_readonly_assert.sql'
)

$env:PGPASSWORD = $env:PROBE_ADMIN_PASS
$code = 1
try {
    # Exactly the verified bytes, not a fresh read of the caller's path.
    [System.IO.File]::WriteAllBytes($LocalCert, $PemBytes)
    # The CA is a public trust anchor and the container runs unprivileged, so the
    # copy must be readable inside the mount. It is never writable, holds no
    # secret, and the directory is removed in the finally block.
    if ($IsLinux -or $IsMacOS) {
        & chmod 0755 $CertDir
        & chmod 0444 $LocalCert
        if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: could not set permissions on the staged certificate copy.' }
    }

    # Windows PowerShell turns native stderr into errors. Capture it without
    # terminating, check the native exit code explicitly, and never write the
    # transcript to the workflow log.
    $ErrorActionPreference = 'Continue'
    & docker @dockerArgs 2>&1 |
        ForEach-Object {
            if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message }
            else { [string]$_ }
        } |
        Out-File -LiteralPath $RawLog -Encoding utf8
    $code = $LASTEXITCODE
} finally {
    $ErrorActionPreference = 'Stop'
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $CertDir -Recurse -Force -ErrorAction SilentlyContinue
}

# Reduce the transcript, then delete it whether or not the reduction succeeded.
$sanitizerFailure = $null
$summary = $null
try {
    if (-not (Test-Path -LiteralPath $RawLog -PathType Leaf)) {
        throw 'the psql transcript is missing, so this run cannot be evidenced.'
    }
    $summary = ConvertTo-ProbeRoleEvidence -Path $RawLog -ExitCode $code
} catch {
    $sanitizerFailure = $_
} finally {
    Remove-Item -LiteralPath $RawLog -Force -ErrorAction SilentlyContinue
}

if ($null -ne $sanitizerFailure) {
    # A refused transcript still leaves a controlled record, never raw output.
    Set-Content -LiteralPath $EvidenceFile -Encoding ASCII -Value @(
        'evidence_schema=probe-role/v1'
        'evidence_status=rejected'
        "psql_exit=$code"
    )
    throw "BLOCKED: the psql transcript could not be reduced to allowlisted evidence, so the run is refused and nothing raw was written to the log or uploaded. $($sanitizerFailure.Exception.Message)"
}

Set-Content -LiteralPath $EvidenceFile -Encoding ASCII -Value $summary

# Preserve psql's own status: a failed run must never look like a green one, and
# sanitizing the output must not rewrite the code.
if ($code -ne 0) {
    Write-Host "::error::psql failed with exit code $code. Sanitized evidence: $EvidenceFile"
    exit $code
}
if (-not ($summary -contains 'evidence_status=ok')) {
    Write-Host "::error::the psql run did not produce a verified evidence summary. See $EvidenceFile"
    exit 1
}
Write-Host "Completed. Sanitized evidence: $EvidenceFile"
