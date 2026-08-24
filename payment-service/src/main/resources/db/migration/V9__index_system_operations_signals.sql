CREATE INDEX idx_payment_refund_reconciliation
    ON payment_refunds (reconciliation_state, status, updated_at DESC, id DESC);
