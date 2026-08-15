UPDATE business_orders bo
JOIN orders o ON o.id = bo.order_id
JOIN checkout_policy_snapshots policy
  ON policy.checkout_id = o.checkout_id
 AND policy.business_id = bo.business_id
SET bo.cancellation_cutoff_at = '2037-01-01 00:00:00.000000'
WHERE policy.version_code = 'LOCAL_DEMO_CANCELLATION_V1'
  AND policy.paid_order_cancellation_mode = 'BEFORE_FULFILLMENT'
  AND o.status = 'CONFIRMED'
  AND bo.fulfillment_status = 'PENDING_ACCEPTANCE'
  AND bo.cancellation_status = 'NONE'
  AND bo.cancellation_cutoff_at IS NULL;
