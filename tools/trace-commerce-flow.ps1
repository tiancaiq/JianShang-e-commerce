param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9A-Z]{26}$')]
    [string]$OrderId
)

$ErrorActionPreference = 'Stop'

function Invoke-SafeQuery([string]$Database, [string]$Query) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Query))
    docker exec msb-demo-mysql sh -lc 'echo "$1" | base64 -d | MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N "$2"' -- $encoded $Database
    if ($LASTEXITCODE -ne 0) { throw "Safe trace query failed for $Database." }
}

Write-Output 'ORDER (order_id, buyer_id, checkout_id, payment_intent_id, state)'
Invoke-SafeQuery 'order_service_checkout_runtime' "SELECT id,buyer_id,checkout_id,payment_intent_id,status FROM orders WHERE id='$OrderId'"
Write-Output 'GROUPS (business_order_id, business_id, state)'
Invoke-SafeQuery 'order_service_checkout_runtime' "SELECT id,business_id,CONCAT(fulfillment_status,'/',cancellation_status) FROM business_orders WHERE order_id='$OrderId' ORDER BY id"
Write-Output 'CANCELLATION (cancellation_id, state, inventory_state, refund_state)'
Invoke-SafeQuery 'order_service_checkout_runtime' "SELECT r.id,r.status,c.inventory_status,c.refund_status FROM order_cancellation_requests r LEFT JOIN order_cancellation_compensations c ON c.cancellation_request_id=r.id WHERE r.order_id='$OrderId'"
Write-Output 'RETURNS (return_id, business_order_id, state, refund_state, source_event_id)'
Invoke-SafeQuery 'order_service_checkout_runtime' "SELECT id,business_order_id,status,refund_status,COALESCE(refund_id,'-') FROM business_order_returns WHERE order_id='$OrderId' ORDER BY id"
Write-Output 'ORDER OUTBOX (event_id, event_type, delivery_state, attempts)'
Invoke-SafeQuery 'order_service_checkout_runtime' "SELECT id,event_type,IF(notification_published_at IS NULL,'PENDING','PUBLISHED'),notification_attempt_count FROM order_outbox_events WHERE aggregate_id='$OrderId' OR JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.orderId'))='$OrderId' ORDER BY created_at"
Write-Output 'PAYMENTS (payment_intent_id, state, amount, refunded_total)'
Invoke-SafeQuery 'payment_service_checkout_runtime' "SELECT p.id,p.status,p.amount,((SELECT COALESCE(SUM(r.amount),0) FROM payment_refunds r WHERE r.payment_intent_id=p.id AND r.status='SUCCEEDED')+(SELECT COALESCE(SUM(rr.amount),0) FROM payment_return_refunds rr WHERE rr.payment_intent_id=p.id AND rr.status='SUCCEEDED')) FROM payment_intents p WHERE p.checkout_id=(SELECT checkout_id FROM order_service_checkout_runtime.orders WHERE id='$OrderId')"

Write-Output 'Trace output intentionally excludes addresses, tokens, provider payloads, and outbox payloads.'
