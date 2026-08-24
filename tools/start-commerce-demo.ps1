param(
    [switch]$SkipPackage,
    [switch]$SkipFixtures,
    [string]$EnvironmentFile = '.env.demo'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..").Path
$mavenWrapper = if ($env:MAVEN_WRAPPER) {
    $env:MAVEN_WRAPPER
} elseif ($env:OS -eq 'Windows_NT') {
    Join-Path $root 'mvnw.cmd'
} else {
    Join-Path $root 'mvnw'
}
Push-Location $root
try {
    $environmentPath = if ([System.IO.Path]::IsPathRooted($EnvironmentFile)) {
        $EnvironmentFile
    } else {
        Join-Path $root $EnvironmentFile
    }
    if (-not (Test-Path -LiteralPath $environmentPath -PathType Leaf)) {
        throw "Commerce environment file '$EnvironmentFile' was not found."
    }
    if (-not $SkipPackage) {
        & $mavenWrapper -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Commerce backend packaging failed.' }
    }
    docker compose --env-file $environmentPath -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml up -d --build
    if ($LASTEXITCODE -ne 0) { throw 'Commerce Compose startup failed.' }
    & .\scripts\verify-commerce-runtime.ps1
    if (-not $SkipFixtures) { & .\tools\prepare-commerce-demo.ps1 }
} finally {
    Pop-Location
}
