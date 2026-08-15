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

function Invoke-MigrationGate([string]$Module, [string]$Tests) {
    Push-Location $root
    try {
        & $mavenWrapper -B -pl $Module -am "-Dtest=$Tests" `
            '-Dsurefire.failIfNoSpecifiedTests=false' test
        if ($LASTEXITCODE -ne 0) { throw "$Module clean-migration gate failed." }
    } finally {
        Pop-Location
    }
}

Invoke-MigrationGate 'auth-service' 'AuthServiceApplicationTests'
Invoke-MigrationGate 'inventory-service' 'InventoryServiceApplicationTests'
Invoke-MigrationGate 'order-service' 'CheckoutRepositoryIntegrationTests,BusinessOrderReturnMySqlIntegrationTests'
Invoke-MigrationGate 'payment-service' 'PaymentIntentMySqlIntegrationTests'
Invoke-MigrationGate 'notification-service' 'CommerceNotificationMySqlIntegrationTests'

Write-Output 'Clean MySQL 8.4 migration gates passed independently for Auth, Inventory, Order, Payment, and Notification.'
