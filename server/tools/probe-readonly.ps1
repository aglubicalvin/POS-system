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
# success and failure. The transcript is created owner-only (0600 on Linux, where
# a permissive umask would otherwise expose it to other local users) before any
# content is written. Unexpected transcript output is rejected, not forwarded.
# The original psql exit status is preserved so a failed run cannot look green.

param([switch]$Apply)

$ErrorActionPreference = 'Stop'
$ServerDir = Split-Path -Parent $PSScriptRoot
$Sql = Join-Path $PSScriptRoot 'pos_probe_readonly.sql'
$Assert = Join-Path $PSScriptRoot 'pos_probe_readonly_assert.sql'
$Preflight = Join-Path $PSScriptRoot 'pos_probe_readonly_preflight.sql'
$EvidenceFile = Join-Path $ServerDir 'build/probe-readonly-evidence.txt'
. (Join-Path $PSScriptRoot 'probe-role-evidence.ps1')

# A previous run's result must never be mistaken for this one. An -Apply run
# REMOVES any existing artifact before any step that can terminate the run, so a
# refusal during validation cannot leave a stale evidence_status=ok for the
# workflow to upload. A run that gets past validation records an explicit
# not_run state below, before credentials are read. A dry run never touches the
# artifact, and a removal failure fails closed.
if ($Apply) {
    try {
        if (Test-Path -LiteralPath $EvidenceFile) {
            Remove-Item -LiteralPath $EvidenceFile -Force -ErrorAction Stop
        }
    } catch {
        # Fail closed: if the previous result cannot be cleared, this run cannot
        # prove its own result, so it must not proceed.
        throw 'BLOCKED: a previous sanitized evidence artifact could not be removed, so this run cannot prove its own result.'
    }
}
<#
.SYNOPSIS
Delete a temporary sensitive path, recording failure instead of hiding it.

.DESCRIPTION
Cleanup used to run with -ErrorAction SilentlyContinue, so a failed deletion of
the raw transcript or the temporary certificate copy was invisible: the run
reported success while sensitive material remained on disk.

Returns $null on success, or a short message on failure. It never throws, so it
<#
.SYNOPSIS
Cleanup result accumulated across every sensitive-path deletion.

.DESCRIPTION
Warnings alone do not change a script's exit status, so a failed deletion of the
raw transcript or the temporary certificate copy could leave sensitive material
on disk while the run reported success. Every deletion now records its outcome
here, all deletions are attempted regardless of earlier failures, and the
collected result is applied to the final exit status by Set-ProbeFailure.

Messages name only the path kind. They never include file contents, the
transcript, or credential values.
#>
$script:CleanupFailures = [System.Collections.Generic.List[string]]::new()

# Exit status when the probe itself succeeded but required cleanup failed.
# Distinct from psql's documented 0-3 range (see Set-ProbeFailure).
$script:CleanupOnlyExitCode = 4

<#
.SYNOPSIS
Attempt deletion of every sensitive path, collecting failures.

.DESCRIPTION
Runs each deletion even if an earlier one failed, so one stuck file cannot leave
the rest behind. Returns the failures recorded during this call.
#>
function Clear-SensitivePaths {
    param(
        [Parameter(Mandatory)] [object[]] $Targets
    )
    $before = $script:CleanupFailures.Count
    foreach ($target in $Targets) {
        $message = Remove-SensitivePath -Path $target.Path -Label $target.Label -Recurse:$target.Recurse
        if ($null -ne $message) {
            [void]$script:CleanupFailures.Add($message)
            Write-Warning "cleanup: $message"
        }
    }
    return @($script:CleanupFailures | Select-Object -Skip $before)
}

<#
.SYNOPSIS
Exit with the primary status, adding a non-zero status if cleanup failed.

.DESCRIPTION
The primary failure is never replaced: when it is already non-zero it is used
as-is, and cleanup failures are reported alongside it. When the primary status
is zero but cleanup failed, the run must not report success, so a dedicated
cleanup exit code is used.

$script:CleanupOnlyExitCode = 4. The previous value, 3, was WRONG: psql documents
3 as "script error when ON_ERROR_STOP is set", and this script runs psql with
ON_ERROR_STOP=1, so a real psql failure could be indistinguishable from a
cleanup-only failure. 4 lies outside psql's documented 0-3 range.

A psql failure of any status -- including 3 -- is preserved exactly; cleanup
failure is reported alongside it and never substitutes a different status.
#>
function Set-ProbeFailure {
    param(
        [Parameter(Mandatory)] [int] $PrimaryStatus,
        [Parameter(Mandatory)] [string] $Context
    )
    if ($script:CleanupFailures.Count -eq 0) {
        exit $PrimaryStatus
    }
    $joined = $script:CleanupFailures -join '; '
    if ($PrimaryStatus -ne 0) {
        Write-Host "::error::$Context failed with exit code $PrimaryStatus, and sensitive-file cleanup also failed: $joined"
        exit $PrimaryStatus
    }
    Write-Host "::error::$Context succeeded but sensitive-file cleanup failed: $joined. Temporary sensitive material may remain on disk."
    exit $script:CleanupOnlyExitCode
}

function Remove-SensitivePath {
    param(
        [Parameter(Mandatory)][string] $Path,
        [Parameter(Mandatory)][string] $Label,
        [switch] $Recurse
    )
    try {
        if (Test-Path -LiteralPath $Path) {
            Remove-Item -LiteralPath $Path -Force -Recurse:$Recurse -ErrorAction Stop
        }
        if (Test-Path -LiteralPath $Path) {
            return "$Label still exists after deletion"
        }
        return $null
    } catch {
        return "$Label could not be deleted"
    }
}

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

# Past this point the run can still fail before psql produces a summary: the
# setup guard, credential loading, or a database-step exception. Record that this
# run started and did not complete, so the artifact can never be mistaken for a
# successful result.
try {
    New-Item -ItemType Directory -Force -Path (Split-Path $EvidenceFile) | Out-Null
    Set-Content -LiteralPath $EvidenceFile -Encoding ASCII -Value @(
        'evidence_schema=probe-role/v1'
        'evidence_status=not_run'
    )
} catch {
    throw 'BLOCKED: the sanitized evidence artifact could not be set to a not-run state, so this run cannot prove its own result.'
}

# The workflow may upload evidence only after this invocation has removed the
# previous artifact and initialized this run's sanitized not-run record. A
# local invocation has no GITHUB_OUTPUT and keeps its existing behavior.
$githubOutput = [System.Environment]::GetEnvironmentVariable('GITHUB_OUTPUT')
if ($null -ne $githubOutput) {
    try {
        [System.IO.File]::AppendAllText(
            $githubOutput,
            "evidence_ready=true`n",
            [System.Text.UTF8Encoding]::new($false)
        )
    } catch {
        throw 'BLOCKED: the sanitized evidence readiness output could not be written.'
    }
}

# psql's \password reads two lines from stdin and hashes client-side. Reject
# line breaks so the two prompts cannot consume different values.
if ($env:PROBE_PW -match '[\r\n\x00]') {
    throw 'Refusing to run: PROBE_PW must not contain line breaks or NUL.'
}

# Read the role password into this process only, then REMOVE it from the
# environment before any child process exists. Child processes inherit the
# environment, so leaving PROBE_PW set while Docker runs would hand the Docker
# CLI -- and anything it starts -- the password even though stdin is the intended
# delivery channel. Nothing below restores the variable; the local copy feeds the
# two stdin lines and is cleared in the finally block.
$ProbePw = $env:PROBE_PW
if ([string]::IsNullOrEmpty($ProbePw)) {
    throw 'Refusing to run: PROBE_PW must be set in the environment.'
}
Remove-Item Env:PROBE_PW -ErrorAction SilentlyContinue

# The administrator credential is only needed to seed PGPASSWORD for Docker's
# `-e PGPASSWORD` pass-through, which libpq inside the container reads. Read it
# into this process, then REMOVE PROBE_ADMIN_PASS from the environment before
# the Docker CLI exists: the CLI inherits the environment, and neither it nor
# anything it starts needs the credential under that name. PGPASSWORD itself
# must stay set because `-e PGPASSWORD` names a variable rather than carrying a
# value; that is the intended channel. The local copy is cleared with PGPASSWORD
# in the finally block.
$AdminPass = $env:PROBE_ADMIN_PASS
if ([string]::IsNullOrEmpty($AdminPass)) {
    throw 'Refusing to run: PROBE_ADMIN_PASS must be set in the environment.'
}
Remove-Item Env:PROBE_ADMIN_PASS -ErrorAction SilentlyContinue

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

$env:PGPASSWORD = $AdminPass
$code = 1

# The certificate directory was created just above, outside the protected region,
# and PGPASSWORD is now in the environment. If anything from here to the try
# below threw, the directory would be abandoned with no cleanup, so this guard
# covers that window.
try {
    if (-not (Test-Path -LiteralPath $CertDir)) {
        throw "temporary certificate directory is missing: $CertDir"
    }
} catch {
    $env:PGPASSWORD = $null
    $ProbePw = $null
    $AdminPass = $null
    [void](Clear-SensitivePaths -Targets @(
        @{ Path = $RawLog; Label = 'raw transcript' },
        @{ Path = $CertDir; Label = 'temporary certificate directory'; Recurse = $true }
    ))
    Write-Host "::error::probe setup failed before the database step"
    Set-ProbeFailure -PrimaryStatus 1 -Context 'setup'
}

# Set by the Docker catch block below when the invocation throws before returning
# a status. The exit happens after the finally block, so cleanup failures on that
# path still reach the exit status.
$dockerFailure = $null

try {
    # Create the transcript owner-only BEFORE its first byte. A permissive umask
    # on a shared Linux runner would otherwise leave the psql output (database,
    # role, schema and grant names) readable by other local users while it
    # exists. Out-File truncates this same file, so the mode survives every write.
    # On Windows, POSIX mode bits do not exist and the file inherits the private
    # per-user temp-directory ACL.
    $transcriptStream = [System.IO.File]::Open(
        $RawLog,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::None
    )
    $transcriptStream.Dispose()
    if ($IsLinux -or $IsMacOS) {
        & chmod 0600 $RawLog
        if ($LASTEXITCODE -ne 0) {
            throw 'BLOCKED: could not restrict permissions on the psql transcript, so the run is refused.'
        }
    }

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
    #
    # pos_probe_readonly_password.sql is `\password pos_probe_readonly`, which
    # reads TWO lines from psql's stdin. Docker runs with -i, so the inherited
    # stdin pipe is psql's stdin. Piping the local copy twice delivers exactly one
    # line per \password prompt and never puts it in the argument vector, in a
    # Docker -e value, or in the inherited environment (PROBE_PW was removed from
    # it above, and was rejected if it contained CR, LF or NUL, so no password
    # value can add or remove an input line).
    $ErrorActionPreference = 'Continue'
    @($ProbePw, $ProbePw) |
        & docker @dockerArgs 2>&1 |
        ForEach-Object {
            if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message }
            else { [string]$_ }
        } |
        Out-File -LiteralPath $RawLog -Encoding utf8
    $code = $LASTEXITCODE
} catch {
    # Docker threw before returning a status. Record it and let the finally
    # block finish cleanup; the exit happens AFTER it, so cleanup failures on
    # this path still reach the exit status. A bare throw here was a defect.
    [void](Clear-SensitivePaths -Targets @(
        @{ Path = $RawLog; Label = 'raw transcript' }
    ))
    $dockerFailure = $_
} finally {
    $ErrorActionPreference = 'Stop'
    $ProbePw = $null
    $AdminPass = $null
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    # Every sensitive path is attempted here, even if the transcript above
    # could not be removed.
    [void](Clear-SensitivePaths -Targets @(
        @{ Path = $CertDir; Label = 'temporary certificate directory'; Recurse = $true }
    ))
}

# Exit only after the finally block has run, so the status reflects cleanup.
# Docker never returned a psql status on this path; use the wrapper-specific
# status 4 after cleanup completes.
if ($null -ne $dockerFailure) {
    # Sanitized: the exception message can carry connection details.
    Write-Host "::error::the database step failed before returning an exit status"
    if ($script:CleanupFailures.Count -gt 0) {
        Write-Host "::error::sensitive-file cleanup also failed: $($script:CleanupFailures -join '; ')"
    }
    # psql never ran, so there is no database status to preserve. Both a
    # clean and a failed cleanup report 4: the dedicated "probe did not
    # complete" status, distinct from psql 1-3.
    exit $script:CleanupOnlyExitCode
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
    # Collected, not merely assigned: a result nobody checks is the defect this
    # fix removes.
    [void](Clear-SensitivePaths -Targets @(
        @{ Path = $RawLog; Label = 'raw transcript' }
    ))
}

if ($null -ne $sanitizerFailure) {
    # A refused transcript still leaves a controlled record, never raw output.
    Set-Content -LiteralPath $EvidenceFile -Encoding ASCII -Value @(
        'evidence_schema=probe-role/v1'
        'evidence_status=rejected'
        "psql_exit=$code"
    )
    # Report the reason BEFORE exiting: Set-ProbeFailure calls exit, so anything
    # after it would never run. The reason is a fixed category, never the
    # exception text -- a sanitizer exception can quote transcript content.
    Write-Host "::error::evidence reduction failed: the psql transcript could not be reduced to allowlisted evidence, so the run is refused and nothing raw was written to the log or uploaded."
    $reductionFailureStatus = if ($code -ne 0) { $code } else { 1 }
    Set-ProbeFailure -PrimaryStatus $reductionFailureStatus -Context 'evidence reduction'
}

Set-Content -LiteralPath $EvidenceFile -Encoding ASCII -Value $summary

# Preserve psql's own status: a failed run must never look like a green one, and
# sanitizing the output must not rewrite the code.
if ($code -ne 0) {
    Write-Host "::error::psql failed with exit code $code. Sanitized evidence: $EvidenceFile"
    # The primary status is preserved exactly; cleanup failures are added to the
    # message, never substituted for the status.
    Set-ProbeFailure -PrimaryStatus $code -Context 'psql'
}
if (-not ($summary -contains 'evidence_status=ok')) {
    Write-Host "::error::the psql run did not produce a verified evidence summary. See $EvidenceFile"
    Set-ProbeFailure -PrimaryStatus 1 -Context 'evidence summary'
}
Write-Host "Completed. Sanitized evidence: $EvidenceFile"
Set-ProbeFailure -PrimaryStatus 0 -Context 'probe'
