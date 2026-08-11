ALTER TABLE order_outbox_events
    ADD COLUMN notification_attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN notification_next_attempt_at TIMESTAMP(6) NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN notification_published_at TIMESTAMP(6) NULL,
    ADD COLUMN notification_last_error_code VARCHAR(64) NULL;

UPDATE order_outbox_events
SET notification_next_attempt_at = created_at
WHERE (event_type = 'order.confirmed' AND event_version = 2)
   OR event_type IN (
       'business_order.accepted', 'business_order.processing_started',
       'business_order.shipped', 'business_order.delivered_demo',
       'order.cancellation_completed'
   );

CREATE INDEX idx_order_notification_outbox_due
    ON order_outbox_events (notification_published_at, notification_next_attempt_at, created_at, id);
