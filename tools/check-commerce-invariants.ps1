param()

$ErrorActionPreference = 'Stop'

function Invoke-CommerceSql([string]$Query) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Query))
    $result = docker exec msb-demo-mysql sh -lc `
        'echo "$1" | base64 -d | MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N' -- $encoded
    if ($LASTEXITCODE -ne 0) { throw 'Commerce invariant query failed.' }
    return ($result -join "`n").Trim()
}

function Assert-Zero([string]$Name, [string]$Query) {
    $count = [int64](Invoke-CommerceSql $Query)
    if ($count -ne 0) { throw "$Name invariant failed with $count violating row(s)." }
    Write-Output "$Name`: OK"
}

Assert-Zero 'Non-negative inventory balances' @"
SELECT COUNT(*) FROM inventory_service.inventory_items
WHERE on_hand < 0 OR reserved < 0 OR reserved > on_hand;
"@
Assert-Zero 'Cancelled fulfillment terminality' @"
SELECT COUNT(*) FROM order_service_checkout_runtime.business_orders
WHERE cancellation_status='CANCELLED' AND fulfillment_status<>'PENDING_ACCEPTANCE';
"@
Assert-Zero 'Shipment requires processing history' @"
SELECT COUNT(*) FROM order_service_checkout_runtime.shipments s
WHERE NOT EXISTS (
  SELECT 1 FROM order_service_checkout_runtime.business_order_status_history h
  WHERE h.business_order_id=s.business_order_id AND h.to_status='PROCESSING'
);
"@
Assert-Zero 'Return requires delivered fulfillment' @"
SELECT COUNT(*) FROM order_service_checkout_runtime.business_order_returns r
JOIN order_service_checkout_runtime.business_orders b ON b.id=r.business_order_id
WHERE b.fulfillment_status<>'DELIVERED';
"@
Assert-Zero 'Completed return requires succeeded refund evidence' @"
SELECT COUNT(*) FROM order_service_checkout_runtime.business_order_returns
WHERE status='RETURN_COMPLETED'
  AND (refund_status<>'SUCCEEDED' OR refund_id IS NULL OR refund_amount IS NULL OR completed_at IS NULL);
"@
Assert-Zero 'Business-group return amount bound' @"
SELECT COUNT(*) FROM order_service_checkout_runtime.business_order_returns r
JOIN order_service_checkout_runtime.business_orders b ON b.id=r.business_order_id
WHERE r.refund_amount IS NOT NULL AND r.refund_amount>b.subtotal;
"@
Assert-Zero 'Successful payment refund bound' @"
SELECT COUNT(*) FROM payment_service_checkout_runtime.payment_intents pi
WHERE COALESCE((SELECT SUM(r.amount) FROM payment_service_checkout_runtime.payment_refunds r
                WHERE r.payment_intent_id=pi.id),0)
    + COALESCE((SELECT SUM(r.amount) FROM payment_service_checkout_runtime.payment_return_refunds r
                WHERE r.payment_intent_id=pi.id),0) > pi.amount;
"@
Assert-Zero 'Return restock disposition match' @"
SELECT COUNT(*) FROM order_service_checkout_runtime.business_order_returns r
LEFT JOIN inventory_service.inventory_return_restocks i ON i.return_id=r.id
WHERE (r.inventory_disposition='RESTOCK_SELLABLE' AND r.status='RETURN_COMPLETED' AND i.id IS NULL)
   OR (r.inventory_disposition='DO_NOT_RESTOCK' AND i.id IS NOT NULL);
"@
Assert-Zero 'Notification source deduplication' @"
SELECT COUNT(*) FROM (
  SELECT consumer_name,source_event_id,COUNT(*) c
  FROM notification_service_checkout_runtime.notification_source_events
  GROUP BY consumer_name,source_event_id HAVING c>1
) duplicates;
"@

Write-Output 'Commerce state, inventory, refund, restock, and notification invariants are consistent.'
