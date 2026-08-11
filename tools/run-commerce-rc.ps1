param([switch]$SkipFixtures)

$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\..\scripts\verify-commerce-runtime.ps1"
if (-not $SkipFixtures) { & "$PSScriptRoot\prepare-commerce-demo.ps1" }
node (Join-Path $PSScriptRoot 'commerce_rc_acceptance.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Commerce RC HTTP/restart acceptance failed.' }
& "$PSScriptRoot\check-commerce-invariants.ps1"
& "$PSScriptRoot\check-commerce-health.ps1"
Write-Output 'Commerce RC runtime acceptance, recovery, accounting, and diagnostics passed.'
