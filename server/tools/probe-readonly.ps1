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
#   PROBE_ADMIN_USER         e.g. postgres.<ref> (must match the reference)
#   PROBE_ADMIN_PASS         admin password
#   PROBE_PW                 password to set for pos_probe_readonly

param([switch]$Apply)

$ErrorActionPreference = 'Stop'
$ServerDir = Split-Path -Parent $PSScriptRoot
$Sql = Join-Path $PSScriptRoot 'pos_probe_readonly.sql'
$Assert = Join-Path $PSScriptRoot 'pos_probe_readonly_assert.sql'
$HostName = 'aws-0-eu-west-2.pooler.supabase.com'

# Fail closed: the staging project must be designated explicitly. Known
# non-staging projects (the documented development project) are refused even if
# they are named here, so a development database can never be probed as staging.
$StagingRef = $env:POS_STAGING_PROJECT_REF
if ([string]::IsNullOrWhiteSpace($StagingRef)) {
    throw 'BLOCKED: POS_STAGING_PROJECT_REF has not been explicitly configured. The staging project must be designated by the project owner.'
}
$StagingRef = $StagingRef.Trim()
if ($StagingRef -notmatch '^[a-z0-9]{20}$') {
    throw 'BLOCKED: POS_STAGING_PROJECT_REF is not a valid Supabase project reference.'
}
# Known non-staging projects: development and the former verification project.
# Neither may ever be designated as staging, even by name.
$KnownNonStaging = @('uemdrtrzlvhdtonccczt', 'numwzjogaanfhlrfgrqk')
if ($KnownNonStaging -contains $StagingRef) {
    throw 'BLOCKED: POS_STAGING_PROJECT_REF names a known non-staging project. Set the dedicated staging reference.'
}
if ($env:PROBE_ADMIN_USER -ne "postgres.$StagingRef") {
    throw 'BLOCKED: PROBE_ADMIN_USER does not belong to the designated staging project. Expected postgres.<POS_STAGING_PROJECT_REF>.'
}
if (-not $env:PROBE_ADMIN_PASS -or -not $env:PROBE_PW) {
    throw 'Refusing to run: PROBE_ADMIN_PASS and PROBE_PW must be set in the environment.'
}

$Log = Join-Path $ServerDir "build/probe-readonly-$(Get-Date -Format yyyyMMdd-HHmmss).log"
New-Item -ItemType Directory -Force -Path (Split-Path $Log) | Out-Null

Write-Host "Target host: $HostName  staging project: $StagingRef  database: postgres"
Write-Host "Scripts: $Sql , $Assert"
Write-Host "Log: $Log"

if (-not $Apply) {
    Write-Host 'DRY RUN. Re-run with -Apply to execute against the designated staging project.'
    exit 0
}

# psql's \password reads two lines from stdin and hashes client-side. Reject
# line breaks so the two prompts cannot consume different values.
if ($env:PROBE_PW -match '[\r\n\x00]') {
    throw 'Refusing to run: PROBE_PW must not contain line breaks or NUL.'
}
$uri = "host=$HostName port=5432 dbname=postgres user=$($env:PROBE_ADMIN_USER) sslmode=require"
$dockerArgs = @(
    'run', '--rm', '-i',
    '-e', 'PGPASSWORD',
    '-v', "$PSScriptRoot`:/work:ro",
    'postgres:17',
    'psql', $uri, '--single-transaction', '-v', 'ON_ERROR_STOP=1',
    '-c', "SET password_encryption = 'scram-sha-256'",
    '-f', '/work/pos_probe_readonly.sql',
    '-f', '/work/pos_probe_readonly_password.sql',
    '-f', '/work/pos_probe_readonly_assert.sql'
)
$env:PGPASSWORD = $env:PROBE_ADMIN_PASS
try {
    # Windows PowerShell turns native stderr (including psql's prompts) into
    # errors. Keep it non-terminating and check the native exit code explicitly.
    $ErrorActionPreference = 'Continue'
    @($env:PROBE_PW, $env:PROBE_PW) | & docker @dockerArgs 2>&1 | Tee-Object -FilePath $Log
    $code = $LASTEXITCODE
} finally {
    $ErrorActionPreference = 'Stop'
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}
if ($code -ne 0) { throw "psql failed with exit code $code. See $Log" }
Write-Host "Completed. Log: $Log"
