param()

$ErrorActionPreference = 'Stop'
$required = @(
    'KEYCLOAK_ADMIN_PASSWORD',
    'LOCAL_DEMO_BUYER_PASSWORD',
    'LOCAL_DEMO_BUYER_B_PASSWORD',
    'LOCAL_DEMO_SHEN_PASSWORD',
    'LOCAL_DEMO_HARBOR_PASSWORD',
    'COMMERCE_INTERNAL_SERVICE_TOKEN'
)
foreach ($name in $required) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "Set $name in the current process before preparing local commerce fixtures."
    }
}

& "$PSScriptRoot\..\scripts\verify-commerce-runtime.ps1"
& "$PSScriptRoot\ensure-local-fulfillment-sellers.ps1"

$adminUser = if ($env:KEYCLOAK_ADMIN) { $env:KEYCLOAK_ADMIN } else { 'admin' }
docker exec msb-demo-keycloak /opt/keycloak/bin/kcadm.sh config credentials `
    --server http://localhost:8080 --realm master --user $adminUser `
    --password $env:KEYCLOAK_ADMIN_PASSWORD | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Keycloak admin authentication failed while preparing the buyer fixture.' }

function Set-LocalFixturePassword([string]$Username, [string]$Password) {
    $userId = docker exec msb-demo-keycloak /opt/keycloak/bin/kcadm.sh get users `
        -r msb-local -q username=$Username --fields id --format csv --noquotes
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($userId)) {
        throw "The stable $Username realm fixture is missing."
    }
    docker exec msb-demo-keycloak /opt/keycloak/bin/kcadm.sh set-password -r msb-local `
        --userid $userId.Trim() --new-password $Password --temporary=false | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not set the runtime-supplied password for $Username." }
}

Set-LocalFixturePassword 'trade.buyer@msb.local' $env:LOCAL_DEMO_BUYER_PASSWORD
Set-LocalFixturePassword 'trade.seller@msb.local' $env:LOCAL_DEMO_BUYER_B_PASSWORD

node "$PSScriptRoot\cart_second_business_fixture.mjs"
if ($LASTEXITCODE -ne 0) { throw 'Deterministic commerce listing/inventory preparation failed.' }

Write-Output 'Reusable commerce fixtures are ready. Preserved evidence orders were not deleted or rewritten.'
