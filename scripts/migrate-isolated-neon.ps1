param(
    [string]$EnvFile = ".env.local",
    [switch]$ConfirmIsolatedBranch
)

$ErrorActionPreference = "Stop"
if (-not $ConfirmIsolatedBranch) {
    throw "Pass -ConfirmIsolatedBranch only after confirming this is a disposable Neon branch, not production."
}

$root = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot "verify-neon-config.ps1") -EnvFile $EnvFile -RequireMigration

$values = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $root $EnvFile)) {
    if ($line -match '^\s*#' -or [string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line -split '=', 2
    if ($parts.Count -eq 2) { $values[$parts[0].Trim()] = $parts[1].Trim() }
}

$env:DB_MIGRATION_URL = $values.DB_MIGRATION_URL
$env:DB_USERNAME = $values.DB_USERNAME
$env:DB_PASSWORD = $values.DB_PASSWORD

Push-Location (Join-Path $root "backend")
try {
    .\mvnw.cmd flyway:info
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    .\mvnw.cmd flyway:migrate
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    .\mvnw.cmd flyway:validate
    exit $LASTEXITCODE
} finally {
    Pop-Location
    Remove-Item Env:DB_MIGRATION_URL -ErrorAction SilentlyContinue
    Remove-Item Env:DB_USERNAME -ErrorAction SilentlyContinue
    Remove-Item Env:DB_PASSWORD -ErrorAction SilentlyContinue
}
