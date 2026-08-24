CREATE INDEX idx_payment_status_analytics
    ON payment_status_history (to_status, created_at, payment_intent_id);

CREATE INDEX idx_payment_refund_created_analytics
    ON payment_refunds (created_at, id);

CREATE INDEX idx_payment_refund_status_analytics
    ON payment_refund_status_history (to_status, created_at, refund_id);

CREATE INDEX idx_payment_return_refund_analytics
    ON payment_return_refunds (completed_at, id);
