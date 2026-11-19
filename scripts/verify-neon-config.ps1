param(
    [string]$EnvFile = ".env.local",
    [switch]$RequireMigration
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$path = Join-Path $root $EnvFile
if (-not (Test-Path -LiteralPath $path)) { throw "Missing $EnvFile. Copy .env.example and add rotated credentials." }

$values = @{}
foreach ($line in Get-Content -LiteralPath $path) {
    if ($line -match '^\s*#' -or [string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line -split '=', 2
    if ($parts.Count -eq 2) { $values[$parts[0].Trim()] = $parts[1].Trim() }
}

foreach ($key in @("DB_URL", "DB_USERNAME", "DB_PASSWORD")) {
    if (-not $values.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($values[$key])) { throw "Missing $key in $EnvFile." }
}
if (-not $values.DB_URL.StartsWith("jdbc:postgresql://")) { throw "DB_URL must be a JDBC PostgreSQL URL." }
if ($values.DB_URL -notmatch '-pooler\.') { throw "DB_URL must use the pooled Neon hostname for normal application traffic." }

if ($RequireMigration) {
    foreach ($key in @("DB_MIGRATION_URL", "NEON_BRANCH")) {
        if (-not $values.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($values[$key])) { throw "Missing $key in $EnvFile." }
    }
    if (-not $values.DB_MIGRATION_URL.StartsWith("jdbc:postgresql://")) { throw "DB_MIGRATION_URL must be a JDBC PostgreSQL URL." }
    if ($values.DB_MIGRATION_URL -match '-pooler\.') { throw "DB_MIGRATION_URL must use the direct, non-pooler endpoint." }
    if ($values.NEON_BRANCH -eq "production") { throw "Refusing migration validation for the production branch. Use an isolated Neon branch." }
}

Write-Host "Neon configuration structure is valid. No credential values were printed."
