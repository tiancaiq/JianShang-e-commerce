ALTER TABLE business_order_returns
    ADD CONSTRAINT chk_business_order_return_receipt_fields CHECK (
        (status IN ('RETURN_REQUESTED','RETURN_AUTHORIZED','RETURN_IN_TRANSIT')
            AND inventory_disposition IS NULL AND received_at IS NULL)
        OR
        (status IN ('RETURN_RECEIVED','RETURN_COMPLETED')
            AND inventory_disposition IS NOT NULL AND received_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_business_order_return_completion_fields CHECK (
        (status <> 'RETURN_COMPLETED' AND completed_at IS NULL)
        OR
        (status = 'RETURN_COMPLETED'
            AND refund_status = 'SUCCEEDED'
            AND refund_id IS NOT NULL
            AND refund_amount IS NOT NULL
            AND completed_at IS NOT NULL)
    );
