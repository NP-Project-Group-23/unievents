[CmdletBinding()]
param(
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$backendDirectory = Join-Path $repositoryRoot 'backend'
$mavenWrapper = Join-Path $backendDirectory 'mvnw.cmd'
$artifact = Join-Path $backendDirectory 'target\unievents-backend-0.0.1-SNAPSHOT.jar'

if (-not (Test-Path -LiteralPath $mavenWrapper)) {
    throw "Maven wrapper not found: $mavenWrapper"
}

Push-Location $backendDirectory
try {
    if (-not $SkipTests) {
        & $mavenWrapper test
        if ($LASTEXITCODE -ne 0) { throw "Backend tests failed with exit code $LASTEXITCODE." }
    }

    & $mavenWrapper -Plocal-desktop clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Release packaging failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $artifact)) {
    throw "Expected release artifact was not created: $artifact"
}

$file = Get-Item -LiteralPath $artifact
$hash = Get-FileHash -LiteralPath $artifact -Algorithm SHA256

[pscustomobject]@{
    Artifact = $file.FullName
    SizeBytes = $file.Length
    SHA256 = $hash.Hash
    LaunchCommand = "java -jar `"$($file.FullName)`""
}
