param([switch]$Fresh)

$ErrorActionPreference = 'Stop'

# Import only runtime-supplied disposable credentials. Never echo or persist them.
foreach ($name in @('LOCAL_DEMO_BUYER_PASSWORD', 'LOCAL_DEMO_BUYER_B_PASSWORD', 'LOCAL_DEMO_HARBOR_PASSWORD')) {
    $value = [Environment]::GetEnvironmentVariable($name, 'Process')
    if ([string]::IsNullOrWhiteSpace($value)) {
        $value = [Environment]::GetEnvironmentVariable($name, 'User')
    }
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Set the disposable $name environment variable before preparing RC-FIXTURE-01."
    }
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}

$env:KEYCLOAK_GATEWAY_ADMIN_CLIENT_ID = (docker exec msb-demo-api-gateway `
    printenv KEYCLOAK_GATEWAY_ADMIN_CLIENT_ID).Trim()
$env:KEYCLOAK_GATEWAY_ADMIN_CLIENT_SECRET = (docker exec msb-demo-api-gateway `
    printenv KEYCLOAK_GATEWAY_ADMIN_CLIENT_SECRET).Trim()
if ([string]::IsNullOrWhiteSpace($env:KEYCLOAK_GATEWAY_ADMIN_CLIENT_ID) -or
    [string]::IsNullOrWhiteSpace($env:KEYCLOAK_GATEWAY_ADMIN_CLIENT_SECRET)) {
    throw 'Gateway local identity-fixture service account is unavailable.'
}
node (Join-Path $PSScriptRoot 'ensure-customer-agent-rc-identities.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Could not prepare distinct disposable customer identities.' }

# Keep the Product media fallback scoped to this disposable fixture run. The
# normal S3 configuration and all .env files stay untouched.
$composeFiles = @(
    '-f', 'docker-compose.demo.yml',
    '-f', 'docker-compose.cart-runtime.yml',
    '-f', 'docker-compose.large-catalog-seed.yml',
    '-f', 'docker-compose.demo-ai-main.yml',
    '-f', 'docker-compose.customer-agent-rc-fixture.yml'
)
docker compose --env-file .env.local @composeFiles build frontend | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not build the combined customer Agent/Orders RC frontend.' }
$gatewayIdBefore = docker inspect msb-demo-api-gateway --format '{{.Id}}' 2>$null
$frontendIdBefore = docker inspect msb-demo-frontend --format '{{.Id}}' 2>$null
docker compose --env-file .env.local @composeFiles up -d --no-deps --no-build `
    auth-service product-service api-gateway frontend | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not enable the combined Agent, cart, and fixture runtime.' }
# nginx resolves the Gateway container address at startup; a Gateway recreate
# can otherwise leave a healthy frontend proxying a stale target.
$gatewayIdAfter = docker inspect msb-demo-api-gateway --format '{{.Id}}'
$frontendIdAfter = docker inspect msb-demo-frontend --format '{{.Id}}'
if ($gatewayIdBefore -ne $gatewayIdAfter -and $frontendIdBefore -eq $frontendIdAfter) {
    docker restart msb-demo-frontend | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not refresh frontend Gateway resolution.' }
}
for ($attempt = 0; $attempt -lt 40; $attempt++) {
    $health = docker inspect msb-demo-product-service --format '{{.State.Health.Status}}'
    $media = docker inspect msb-demo-product-service --format '{{range .Config.Env}}{{println .}}{{end}}' |
        Where-Object { $_ -eq 'LISTING_MEDIA_STORAGE=local-demo' }
    if ($health -eq 'healthy' -and $media) { break }
    Start-Sleep -Seconds 3
}
if ($health -ne 'healthy' -or -not $media) {
    throw 'Product did not become healthy in local-demo mode; another Compose run may have replaced the fixture override.'
}
$authHealth = $null
for ($attempt = 0; $attempt -lt 40; $attempt++) {
    $authHealth = docker inspect msb-demo-auth-service --format '{{.State.Health.Status}}'
    if ($authHealth -eq 'healthy') { break }
    Start-Sleep -Seconds 3
}
if ($authHealth -ne 'healthy') { throw 'Auth did not become healthy with the local-only fixture route.' }
foreach ($container in @('msb-demo-api-gateway', 'msb-demo-frontend', 'msb-demo-agent-service')) {
    $serviceHealth = $null
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        $serviceHealth = docker inspect $container --format '{{.State.Health.Status}}'
        if ($serviceHealth -eq 'healthy') { break }
        Start-Sleep -Seconds 3
    }
    if ($serviceHealth -ne 'healthy') { throw "$container did not become healthy." }
}
$gatewaySettings = @(docker inspect msb-demo-api-gateway --format '{{range .Config.Env}}{{println .}}{{end}}')
if ('GATEWAY_FEATURE_AGENT=true' -notin $gatewaySettings -or
    'GATEWAY_FEATURE_CART=true' -notin $gatewaySettings) {
    throw 'The combined Gateway Agent/cart routes are not enabled.'
}
$agentSettings = @(docker inspect msb-demo-agent-service --format '{{range .Config.Env}}{{println .}}{{end}}')
foreach ($name in @('CHECKOUT', 'ORDER_MUTATIONS', 'RETURN_REQUESTS')) {
    if ("AGENT_MARKETPLACE_V2_${name}_ENABLED=false" -notin $agentSettings) {
        throw "High-risk Agent capability $name is not at its safe default-off state."
    }
}

$env:KEYCLOAK_MARKETPLACE_CLIENT_SECRET = (docker exec msb-demo-api-gateway `
    printenv KEYCLOAK_MARKETPLACE_CLIENT_SECRET).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($env:KEYCLOAK_MARKETPLACE_CLIENT_SECRET)) {
    throw 'Gateway marketplace client secret is unavailable in the disposable runtime.'
}
$env:COMMERCE_INTERNAL_SERVICE_TOKEN = (docker exec msb-demo-auth-service `
    printenv COMMERCE_INTERNAL_SERVICE_TOKEN).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($env:COMMERCE_INTERNAL_SERVICE_TOKEN)) {
    throw 'Auth local fixture service token is unavailable.'
}
$args = @((Join-Path $PSScriptRoot 'prepare-customer-agent-rc-fixture.mjs'))
if ($Fresh) { $args += '--fresh' }
node @args
if ($LASTEXITCODE -ne 0) { throw 'RC-FIXTURE-01 domain workflow did not complete.' }
