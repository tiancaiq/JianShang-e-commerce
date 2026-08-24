param(
    [string]$GatewayBaseUrl = 'http://127.0.0.1:9000',
    [string]$FrontendBaseUrl = 'http://127.0.0.1:4200'
)

$ErrorActionPreference = 'Stop'
$profile = 'V2_COMMERCE_RC_01'

function Get-ContainerEnvironment([string]$Container) {
    $state = docker inspect --format '{{.State.Status}}' $Container 2>$null
    if ($LASTEXITCODE -ne 0 -or $state -ne 'running') {
        throw "Required commerce container '$Container' is not running. Use .\tools\start-commerce-demo.ps1."
    }
    $values = docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' $Container
    $environment = @{}
    foreach ($line in $values) {
        $separator = $line.IndexOf('=')
        if ($separator -gt 0) {
            $environment[$line.Substring(0, $separator)] = $line.Substring($separator + 1)
        }
    }
    return $environment
}

function Assert-Setting($Environment, [string]$Name, [string]$Expected, [string]$Container) {
    if ($Environment[$Name] -ne $Expected) {
        throw "Commerce runtime mismatch in '$Container': $Name must be '$Expected'. Use .\tools\start-commerce-demo.ps1."
    }
}

function Assert-Profile([string]$Container) {
    $environment = Get-ContainerEnvironment $Container
    Assert-Setting $environment 'COMMERCE_RUNTIME_PROFILE' $profile $Container
    return $environment
}

function Get-HttpStatus([string]$Uri, [string]$Method = 'GET') {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -MaximumRedirection 0 -Method $Method -Uri $Uri
        return [int]$response.StatusCode
    } catch {
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            return [int]$_.Exception.Response.StatusCode
        }
        throw "Capability probe failed for $Uri`: $($_.Exception.Message)"
    }
}

function Assert-Healthy([string]$Name, [string]$Uri) {
    $status = Get-HttpStatus $Uri
    if ($status -ne 200) { throw "$Name health probe failed with HTTP $status ($Uri)." }
}

function Assert-ProtectedCapability([string]$Name, [string]$Path, [string]$Method = 'GET') {
    $status = Get-HttpStatus "$GatewayBaseUrl$Path" $Method
    if ($status -eq 404 -or $status -ge 500) {
        throw "$Name capability is missing or unhealthy: HTTP $status for $Path."
    }
}

$gateway = Assert-Profile 'msb-demo-api-gateway'
$order = Assert-Profile 'msb-cart-runtime-order'
$inventory = Assert-Profile 'msb-cart-runtime-inventory'
$payment = Assert-Profile 'msb-cart-runtime-payment'
$notification = Assert-Profile 'msb-cart-runtime-notification'
$auth = Assert-Profile 'msb-demo-auth-service'
$product = Assert-Profile 'msb-demo-product-service'
$frontend = Assert-Profile 'msb-demo-frontend'

Assert-Setting $product 'LISTING_MEDIA_STORAGE' 's3' 'msb-demo-product-service'

Assert-Setting $gateway 'ORDER_SERVICE_URL' 'http://order-service:8081' 'msb-demo-api-gateway'
Assert-Setting $gateway 'GATEWAY_FEATURE_CART' 'true' 'msb-demo-api-gateway'
Assert-Setting $gateway 'GATEWAY_FEATURE_CHECKOUT' 'true' 'msb-demo-api-gateway'
Assert-Setting $gateway 'GATEWAY_FEATURE_BUSINESS_ORDERS' 'true' 'msb-demo-api-gateway'
Assert-Setting $gateway 'GATEWAY_FEATURE_BUSINESS_ORDER_FULFILLMENT' 'true' 'msb-demo-api-gateway'
Assert-Setting $gateway 'GATEWAY_FEATURE_ORDER_CANCELLATION' 'true' 'msb-demo-api-gateway'
Assert-Setting $gateway 'GATEWAY_FEATURE_NOTIFICATIONS' 'true' 'msb-demo-api-gateway'
Assert-Setting $gateway 'GATEWAY_FEATURE_RETURNS' 'true' 'msb-demo-api-gateway'
Assert-Setting $gateway 'NOTIFICATION_SERVICE_URL' 'http://notification-service:8083' 'msb-demo-api-gateway'

Assert-Setting $order 'CHECKOUT_POLICY_VERSION' 'LOCAL_DEMO_CANCELLATION_V1' 'msb-cart-runtime-order'
Assert-Setting $order 'ORDER_CANCELLATION_REQUESTS_ENABLED' 'true' 'msb-cart-runtime-order'
Assert-Setting $order 'ORDER_CANCELLATION_PROCESSING_ENABLED' 'true' 'msb-cart-runtime-order'
Assert-Setting $order 'ORDER_NOTIFICATION_OUTBOX_ENABLED' 'true' 'msb-cart-runtime-order'
Assert-Setting $order 'ORDER_NOTIFICATION_OUTBOX_WORKER_ENABLED' 'true' 'msb-cart-runtime-order'
Assert-Setting $order 'BUSINESS_ORDERS_RETURNS_ENABLED' 'true' 'msb-cart-runtime-order'
Assert-Setting $order 'BUSINESS_ORDERS_RETURN_PROCESSING_ENABLED' 'true' 'msb-cart-runtime-order'
Assert-Setting $order 'BUSINESS_ORDERS_RETURN_WINDOW' 'P30D' 'msb-cart-runtime-order'
Assert-Setting $inventory 'INVENTORY_CANCELLATION_RESTOCK_ENABLED' 'true' 'msb-cart-runtime-inventory'
Assert-Setting $inventory 'INVENTORY_RETURN_RESTOCK_ENABLED' 'true' 'msb-cart-runtime-inventory'
Assert-Setting $payment 'PAYMENT_REFUNDS_ENABLED' 'true' 'msb-cart-runtime-payment'
Assert-Setting $notification 'NOTIFICATION_COMMERCE_EVENTS_ENABLED' 'true' 'msb-cart-runtime-notification'
Assert-Setting $notification 'NOTIFICATION_READ_API_ENABLED' 'true' 'msb-cart-runtime-notification'
Assert-Setting $notification 'NOTIFICATION_FLYWAY_ENABLED' 'true' 'msb-cart-runtime-notification'

$mysqlImage = docker inspect --format '{{.Config.Image}}' msb-demo-mysql
if ($mysqlImage -ne 'mysql:8.4') { throw "Commerce MySQL must be mysql:8.4; found $mysqlImage." }
$redisImage = docker inspect --format '{{.Config.Image}}' msb-cart-runtime-redis
if ($redisImage -ne 'redis:7.4-alpine') { throw "Commerce Redis must be redis:7.4-alpine; found $redisImage." }

Assert-Healthy 'Gateway' "$GatewayBaseUrl/actuator/health"
Assert-Healthy 'Frontend' "$FrontendBaseUrl/"
Assert-Healthy 'Auth Service' 'http://127.0.0.1:8085/actuator/health'
Assert-Healthy 'Product Service' 'http://127.0.0.1:8091/actuator/health'
Assert-Healthy 'Listing media storage' 'http://127.0.0.1:8091/actuator/health/listingMedia'
Assert-Healthy 'Inventory Service' 'http://127.0.0.1:8082/actuator/health'
Assert-Healthy 'Order Service' 'http://127.0.0.1:8081/actuator/health'
Assert-Healthy 'Payment Service' 'http://127.0.0.1:8084/actuator/health'
Assert-Healthy 'Notification Service' 'http://127.0.0.1:8083/actuator/health'
Assert-Healthy 'Keycloak realm' 'http://127.0.0.1:8181/realms/msb-local/.well-known/openid-configuration'

$frontendMarker = Invoke-RestMethod -Method Get -Uri "$FrontendBaseUrl/commerce-runtime.json"
if ($frontendMarker.buildConfiguration -ne 'demo-checkout') {
    throw "Frontend was not built with demo-checkout; found '$($frontendMarker.buildConfiguration)'."
}

Assert-ProtectedCapability 'Cart' '/api/v1/cart'
Assert-ProtectedCapability 'Checkout' '/api/v1/checkouts/rc-probe'
Assert-ProtectedCapability 'Buyer orders' '/api/v1/orders'
Assert-ProtectedCapability 'Seller orders' '/api/v1/businesses/rc-probe/orders'
Assert-ProtectedCapability 'Fulfillment' '/api/v1/businesses/rc-probe/orders/rc-probe/accept' 'POST'
Assert-ProtectedCapability 'Cancellation' '/api/v1/orders/rc-probe/cancellation-requests' 'POST'
Assert-ProtectedCapability 'Returns' '/api/v1/orders/rc-probe/groups/rc-probe/return'
Assert-ProtectedCapability 'Notifications' '/api/v1/notifications'
Assert-ProtectedCapability 'Buyer addresses' '/api/v1/users/me/addresses'

$redisPong = docker exec msb-cart-runtime-redis redis-cli ping
if ($LASTEXITCODE -ne 0 -or $redisPong.Trim() -ne 'PONG') { throw 'Commerce Redis did not answer PING.' }
$mysqlVersion = docker exec msb-demo-mysql sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e SELECT@@version' 2>$null
if ($LASTEXITCODE -ne 0 -or -not $mysqlVersion.Trim().StartsWith('8.4.')) {
    throw "Commerce MySQL did not report an 8.4 server version."
}

Write-Output 'Commerce RC runtime verified: profile, frontend build, routes, services, listing media storage, Redis, MySQL 8.4, and Keycloak are aligned.'
