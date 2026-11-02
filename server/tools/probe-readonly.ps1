# Creates/verifies the pos_probe_readonly role on the DEVELOPMENT project only.
# Dry run by default. Pass -Apply to execute. Nothing is run without it.
#
# Required environment (never put these in a file or in Git):
#   PROBE_ADMIN_USER  e.g. postgres.<ref>   (admin user for the dev project)
#   PROBE_ADMIN_PASS  admin password
#   PROBE_PW          password to set for pos_probe_readonly

param([switch]$Apply)

$ErrorActionPreference = 'Stop'
$ApprovedRef = 'uemdrtrzlvhdtonccczt'
$ServerDir = Split-Path -Parent $PSScriptRoot
$Sql = Join-Path $PSScriptRoot 'pos_probe_readonly.sql'
$Assert = Join-Path $PSScriptRoot 'pos_probe_readonly_assert.sql'
$HostName = 'aws-0-eu-west-2.pooler.supabase.com'

# Fail closed: the admin user must belong to the approved development project.
if ($env:PROBE_ADMIN_USER -ne "postgres.$ApprovedRef") {
    throw "Refusing to run: PROBE_ADMIN_USER must be exactly postgres.$ApprovedRef"
}
if (-not $env:PROBE_ADMIN_PASS -or -not $env:PROBE_PW) {
    throw 'Refusing to run: PROBE_ADMIN_PASS and PROBE_PW must be set in the environment.'
}

$Log = Join-Path $ServerDir "build/probe-readonly-$(Get-Date -Format yyyyMMdd-HHmmss).log"
New-Item -ItemType Directory -Force -Path (Split-Path $Log) | Out-Null

Write-Host "Target host: $HostName  admin user ends with: .$ApprovedRef  database: postgres"
Write-Host "Scripts: $Sql , $Assert"
Write-Host "Log: $Log"

if (-not $Apply) {
    Write-Host 'DRY RUN. Re-run with -Apply to execute against the development project.'
    exit 0
}

# Step 0 of the SQL prints the executing account. Password is passed as a psql
# variable, not written into the SQL file.
$uri = "host=$HostName port=5432 dbname=postgres user=$($env:PROBE_ADMIN_USER) sslmode=require"
$dockerArgs = @(
    'run', '--rm', '-i',
    '-e', 'PGPASSWORD', '-e', 'PROBE_PW',
    '-v', "$PSScriptRoot`:/work:ro",
    'postgres:17',
    'sh', '-c',
    "psql '$uri' -v ON_ERROR_STOP=1 -v probe_pw=`"`$PROBE_PW`" -f /work/pos_probe_readonly.sql -f /work/pos_probe_readonly_password.sql -f /work/pos_probe_readonly_assert.sql"
)
$env:PGPASSWORD = $env:PROBE_ADMIN_PASS
& docker @dockerArgs 2>&1 | Tee-Object -FilePath $Log
$code = $LASTEXITCODE
Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
if ($code -ne 0) { throw "psql failed with exit code $code. See $Log" }
Write-Host "Completed. Log: $Log"
