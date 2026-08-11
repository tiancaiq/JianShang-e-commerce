CREATE INDEX idx_business_order_cancellation_queue
    ON business_orders (business_id, cancellation_status, created_at, id);
