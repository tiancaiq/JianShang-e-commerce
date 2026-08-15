param()

$ErrorActionPreference = 'Stop'

$realm = 'msb-local'
$keycloakContainer = 'msb-demo-keycloak'
$adminUser = if ($env:KEYCLOAK_ADMIN) { $env:KEYCLOAK_ADMIN } else { 'admin' }
$adminPassword = $env:KEYCLOAK_ADMIN_PASSWORD
$shenPassword = $env:LOCAL_DEMO_SHEN_PASSWORD
$harborPassword = $env:LOCAL_DEMO_HARBOR_PASSWORD
$commerceToken = $env:COMMERCE_INTERNAL_SERVICE_TOKEN

if ([string]::IsNullOrWhiteSpace($adminPassword) -or
    [string]::IsNullOrWhiteSpace($shenPassword) -or
    [string]::IsNullOrWhiteSpace($harborPassword) -or
    [string]::IsNullOrWhiteSpace($commerceToken)) {
    throw 'Set KEYCLOAK_ADMIN_PASSWORD, LOCAL_DEMO_SHEN_PASSWORD, LOCAL_DEMO_HARBOR_PASSWORD, and COMMERCE_INTERNAL_SERVICE_TOKEN before running this local-only fixture command.'
}

docker exec $keycloakContainer /opt/keycloak/bin/kcadm.sh config credentials `
    --server http://localhost:8080 --realm master --user $adminUser `
    --password $adminPassword | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Keycloak admin authentication failed.'
}

function Ensure-LocalSeller {
    param(
        [string]$UserId,
        [string]$Username,
        [string]$FirstName,
        [string]$LastName,
        [string]$Password
    )

    $existing = docker exec $keycloakContainer /opt/keycloak/bin/kcadm.sh get users `
        -r $realm -q username=$Username --fields id --format csv --noquotes
    if ($LASTEXITCODE -ne 0) {
        throw "Could not query local seller identity $Username."
    }
    if ([string]::IsNullOrWhiteSpace($existing)) {
        docker exec $keycloakContainer /opt/keycloak/bin/kcadm.sh create users -r $realm `
            -s id=$UserId -s username=$Username -s email=$Username `
            -s enabled=true -s emailVerified=true -s firstName=$FirstName `
            -s lastName=$LastName | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Could not create local seller identity $Username."
        }
        $existing = docker exec $keycloakContainer /opt/keycloak/bin/kcadm.sh get users `
            -r $realm -q username=$Username --fields id --format csv --noquotes
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($existing)) {
            throw "Could not resolve the created local seller identity $Username."
        }
    }

    docker exec $keycloakContainer /opt/keycloak/bin/kcadm.sh update users/$existing -r $realm `
        -s enabled=true -s emailVerified=true | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not update local seller identity $Username."
    }
    docker exec $keycloakContainer /opt/keycloak/bin/kcadm.sh set-password -r $realm `
        --userid $existing --new-password $Password --temporary=false | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not set the local password for $Username."
    }
    foreach ($role in @('BUYER', 'BUSINESS_USER')) {
        docker exec $keycloakContainer /opt/keycloak/bin/kcadm.sh add-roles -r $realm `
            --uusername $Username --rolename $role | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Could not grant local role $role to $Username."
        }
    }
    return $existing.Trim()
}

$shenSubject = Ensure-LocalSeller -UserId 'dc7dcfe2-cc93-441b-af78-f1afc1f21081' `
    -Username 'shen.ban2@mycnmipss.org' -FirstName 'Shen' -LastName 'Ban' `
    -Password $shenPassword
$harborSubject = Ensure-LocalSeller -UserId '33333333-3333-4333-8333-333333333333' `
    -Username 'harbor.seller@msb.local' -FirstName 'Harbor' -LastName 'Seller' `
    -Password $harborPassword

# Keep the primary buyer separate from both business-owner identities.
docker exec $keycloakContainer /opt/keycloak/bin/kcadm.sh remove-roles -r $realm `
    --uusername 'trade.seller@msb.local' --rolename 'BUSINESS_USER' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Could not restore the primary buyer realm-role boundary.'
}

Invoke-RestMethod -Method Post `
    -Uri 'http://127.0.0.1:8085/api/v1/internal/demo-fixtures/cart/second-business' `
    -Headers @{
        'X-Internal-Service-Token' = $commerceToken
        'X-Local-Demo-Owner-Subject' = $harborSubject
        'X-Local-Demo-Shen-Owner-Subject' = $shenSubject
    } | Out-Null

Write-Output 'Local fulfillment seller identities are ready.'
