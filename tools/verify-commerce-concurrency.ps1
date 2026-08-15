param()

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..").Path
$mavenWrapper = if ($env:MAVEN_WRAPPER) {
    $env:MAVEN_WRAPPER
} elseif ($env:OS -eq 'Windows_NT') {
    Join-Path $root 'mvnw.cmd'
} else {
    Join-Path $root 'mvnw'
}

function Invoke-ConcurrencyGate([string]$Module, [string]$Tests) {
    Push-Location $root
    try {
        & $mavenWrapper -B -pl $Module -am "-Dtest=$Tests" `
            '-Dsurefire.failIfNoSpecifiedTests=false' test
        if ($LASTEXITCODE -ne 0) { throw "$Module concurrency/idempotency gate failed." }
    } finally {
        Pop-Location
    }
}

# Run whole integration classes so newly-added lifecycle races cannot be omitted
# by a stale method-level allow-list.
Invoke-ConcurrencyGate 'inventory-service' 'InventoryServiceApplicationTests'
Invoke-ConcurrencyGate 'order-service' 'RedisCartRepositoryTests,CheckoutRepositoryIntegrationTests,CheckoutServiceTests,PaymentSucceededOrderConfirmationMySqlIntegrationTests,BusinessOrderAcceptanceMySqlIntegrationTests,BusinessOrderFulfillmentMySqlIntegrationTests,OrderCancellationMySqlIntegrationTests,BusinessOrderReturnMySqlIntegrationTests'
Invoke-ConcurrencyGate 'payment-service' 'PaymentIntentMySqlIntegrationTests,PaymentOutboxDispatcherMySqlIntegrationTests'
Invoke-ConcurrencyGate 'notification-service' 'CommerceNotificationMySqlIntegrationTests,CommerceNotificationConsumerTests'

Write-Output 'Commerce MySQL/Redis concurrency, replay, and idempotency gates passed.'
