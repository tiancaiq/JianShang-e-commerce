param()

$ErrorActionPreference = 'Stop'

function Invoke-CommerceSql([string]$Query) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Query))
    $result = docker exec msb-demo-mysql sh -lc `
        'echo "$1" | base64 -d | MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N' -- $encoded
    if ($LASTEXITCODE -ne 0) { throw 'Commerce health query failed.' }
    return ($result -join "`n").Trim()
}

$order = Invoke-CommerceSql @"
SELECT
  SUM(notification_published_at IS NULL) pending,
  SUM(notification_published_at IS NULL AND notification_attempt_count > 0) retrying,
  COALESCE(MAX(CASE WHEN notification_published_at IS NULL THEN notification_attempt_count END),0) max_pending_attempts,
  COALESCE(MAX(CASE WHEN notification_published_at IS NULL THEN TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP(6)) END),0) oldest_pending_age_seconds,
  SUM(notification_last_error_code IS NOT NULL) last_failure_count,
  MAX(CASE WHEN notification_last_error_code IS NOT NULL THEN notification_next_attempt_at END) latest_failure_next_attempt_at
FROM order_service_checkout_runtime.order_outbox_events
WHERE (event_type='order.confirmed' AND event_version=2)
   OR event_type IN (
     'business_order.accepted','business_order.processing_started',
     'business_order.shipped','business_order.delivered_demo',
     'order.cancellation_completed','return.requested','return.authorized',
     'return.received','return.refund_completed'
   );
"@
$payment = Invoke-CommerceSql @"
SELECT
  SUM(published_at IS NULL AND terminal_failure_at IS NULL) pending,
  SUM(published_at IS NULL AND attempt_count > 0 AND terminal_failure_at IS NULL) retrying,
  SUM(terminal_failure_at IS NOT NULL) terminal,
  COALESCE(MAX(CASE WHEN published_at IS NULL THEN attempt_count END),0) max_pending_attempts,
  COALESCE(MAX(CASE WHEN published_at IS NULL THEN TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP(6)) END),0) oldest_pending_age_seconds,
  SUM(last_error_code IS NOT NULL) last_failure_count,
  MAX(CASE WHEN last_error_code IS NOT NULL THEN last_attempt_at END) latest_failure_at
FROM payment_service_checkout_runtime.payment_outbox_events
WHERE event_type IN ('payment.succeeded','payment.failed');
"@
$deferredPaymentRefunds = Invoke-CommerceSql @"
SELECT
  COUNT(*) deferred,
  COALESCE(MAX(attempt_count),0) historical_max_attempts,
  SUM(terminal_failure_at IS NOT NULL) historical_terminal
FROM payment_service_checkout_runtime.payment_outbox_events
WHERE event_type='payment.refunded' AND published_at IS NULL;
"@
$returns = Invoke-CommerceSql @"
SELECT
  SUM(status='RETURN_RECEIVED' AND refund_status<>'SUCCEEDED') pending,
  SUM(processing_attempt_count > 0 AND refund_status<>'SUCCEEDED') retrying,
  COALESCE(MAX(CASE WHEN refund_status<>'SUCCEEDED' THEN processing_attempt_count END),0) max_pending_attempts,
  COALESCE(MAX(CASE WHEN refund_status<>'SUCCEEDED' THEN TIMESTAMPDIFF(SECOND,updated_at,UTC_TIMESTAMP(6)) END),0) oldest_pending_age_seconds,
  SUM(processing_last_error_code IS NOT NULL) last_failure_count,
  MAX(CASE WHEN processing_last_error_code IS NOT NULL THEN updated_at END) latest_failure_at
FROM order_service_checkout_runtime.business_order_returns;
"@
$notifications = Invoke-CommerceSql @"
SELECT
  SUM(state='PROCESSING') processing,
  SUM(state='REJECTED') rejected,
  COALESCE(MAX(CASE WHEN state IN ('PROCESSING','REJECTED') THEN attempt_count END),0) max_active_attempts,
  COALESCE(MAX(CASE WHEN state IN ('PROCESSING','REJECTED') THEN TIMESTAMPDIFF(SECOND,updated_at,UTC_TIMESTAMP(6)) END),0) oldest_active_age_seconds,
  SUM(safe_error_code IS NOT NULL) failure_count,
  MAX(CASE WHEN safe_error_code IS NOT NULL THEN updated_at END) latest_failure_at
FROM notification_service_checkout_runtime.notification_source_events;
"@

Write-Output "Order notification outbox [pending retrying maxPendingAttempts oldestPendingAgeSeconds lastFailureCount latestFailureNextAttemptAt]: $order"
Write-Output "Payment outbox [pending retrying terminal maxPendingAttempts oldestPendingAgeSeconds lastFailureCount latestFailureAt]: $payment"
Write-Output "Deferred payment refund audit events [deferred historicalMaxAttempts historicalTerminal]: $deferredPaymentRefunds"
Write-Output "Return worker [pending retrying maxPendingAttempts oldestPendingAgeSeconds lastFailureCount latestFailureAt]: $returns"
Write-Output "Notification consumer [processing rejected maxActiveAttempts oldestActiveAgeSeconds failureCount latestFailureAt]: $notifications"
