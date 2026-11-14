# Strict, allowlisted evidence for the role-creation path.
#
# The psql transcript is sensitive even when no password is printed: it can carry
# the database name, the executing role, schema/table/function names and grant
# details. It is written to a private temp file, reduced by
# ConvertTo-ProbeRoleEvidence, and deleted whether the reduction succeeds or
# fails. Only the returned summary may be uploaded.
#
# The summary is built from parsed fields, never by copying transcript lines, so
# no raw text can reach the artifact. A line that matches none of the allowed
# patterns aborts the reduction (the wrapper then fails the job): unexpected
# output is rejected, not filtered and forwarded.
#
# Allowed line shapes are exactly the ones the three reviewed SQL files emit with
# `psql -q -A -t`: labelled boolean/count evidence lines, the two \password
# prompts, the three notice lines, and psql's own error prefixes. The error
# prefixes are recognised so a failed run still yields evidence, but their text
# is never copied -- it can name the host, role or function.

function ConvertTo-ProbeRoleEvidence {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string] $Path,
        [Parameter(Mandatory)][int] $ExitCode
    )

    $allowedPatterns = @(
        '^$',
        '^project_identity=(confirmed|mismatch)$',
        '^(auth_schema_usage|public_schema_usage|auth_users_select|auth_identities_select|profiles_select|customers_select|sales_select|payments_select|migration_statements_select|public_create|elevated_attribute|can_login)=(true|false)$',
        '^membership_count=[0-9]+$',
        '^table_grant_count=[0-9]+$',
        '^Enter new password for user "pos_probe_readonly": ?$',
        '^Enter it again: ?$',
        '^NOTICE:  pos_probe_readonly preflight passed: [0-9]+ reviewed SECURITY DEFINER function\(s\) executable$',
        '^NOTICE:  SECURITY DEFINER functions reviewed for pos_probe_readonly: [0-9]+$',
        '^NOTICE:  pos_probe_readonly assertions passed$',
        '^psql: error: .+$',
        '^ERROR:  pos_probe_readonly (preflight refused|assertions failed|project identity refused).*$'
    )

    $fields = [ordered]@{}
    $reviewedCount = $null
    $assertionsPassed = $false
    $lineNumber = 0

    foreach ($line in (Get-Content -LiteralPath $Path)) {
        $lineNumber++
        # Trailing padding only; leading spaces would make a line unrecognisable,
        # which is the intended strictness.
        $text = ([string]$line).TrimEnd()
        $matched = $false
        foreach ($pattern in $allowedPatterns) {
            if ($text -match $pattern) { $matched = $true; break }
        }
        if (-not $matched) {
            # The line itself is metadata: never include it in the failure message.
            throw "the psql transcript contains a line that is not an allowed evidence field (line $lineNumber)."
        }
        if ($text -match '^([a-z_]+)=(true|false|[0-9]+|confirmed|mismatch)$') {
            $fields[$Matches[1]] = $Matches[2]
        }
        if ($text -match '^NOTICE:  pos_probe_readonly preflight passed: ([0-9]+) reviewed') {
            $reviewedCount = [int]$Matches[1]
        }
        if ($text -match '^NOTICE:  SECURITY DEFINER functions reviewed for pos_probe_readonly: ([0-9]+)$') {
            $reviewedCount = [int]$Matches[1]
        }
        if ($text -eq 'NOTICE:  pos_probe_readonly assertions passed') {
            $assertionsPassed = $true
        }
    }

    $requiredFields = @(
        'project_identity',
        'auth_schema_usage',
        'public_schema_usage',
        'auth_users_select',
        'auth_identities_select',
        'profiles_select',
        'customers_select',
        'sales_select',
        'payments_select',
        'migration_statements_select',
        'public_create',
        'elevated_attribute',
        'can_login',
        'membership_count',
        'table_grant_count'
    )

    $complete = $true
    foreach ($key in $requiredFields) {
        if (-not $fields.Contains($key)) { $complete = $false }
    }

    # `ok` is the only status a workflow may treat as success: psql exited 0, the
    # role's own assertions passed, the session was the designated administrator,
    # and every evidence field was present.
    $status = if ($ExitCode -eq 0 -and $complete -and $assertionsPassed -and
        $fields['project_identity'] -eq 'confirmed') { 'ok' } else { 'incomplete' }

    $summary = New-Object System.Collections.Generic.List[string]
    $summary.Add('evidence_schema=probe-role/v1')
    $summary.Add("psql_exit=$ExitCode")
    foreach ($key in $requiredFields) {
        if ($fields.Contains($key)) {
            $summary.Add("$key=$($fields[$key])")
        } else {
            $summary.Add("$key=unreported")
        }
    }
    if ($null -eq $reviewedCount) {
        $summary.Add('security_definer_reviewed_count=unreported')
    } else {
        $summary.Add("security_definer_reviewed_count=$reviewedCount")
    }
    $summary.Add("assertions_passed=$($assertionsPassed.ToString().ToLowerInvariant())")
    $summary.Add("evidence_status=$status")

    return $summary.ToArray()
}
