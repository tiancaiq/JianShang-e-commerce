ALTER TABLE notifications
    MODIFY recipient_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN recipient_scope_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER id,
    ADD COLUMN recipient_scope_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER recipient_scope_type,
    ADD COLUMN business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER recipient_user_id,
    ADD COLUMN business_order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER business_id;

UPDATE notifications
SET recipient_scope_type = 'USER', recipient_scope_id = recipient_user_id
WHERE recipient_scope_type IS NULL;

ALTER TABLE notifications
    DROP CHECK chk_notification_type,
    DROP CHECK chk_notification_message_key,
    DROP CHECK chk_notification_route,
    DROP CHECK chk_notification_projection_source_version,
    DROP CHECK chk_notification_args,
    ADD UNIQUE KEY uk_notification_scope_source_type (
        recipient_scope_type, recipient_scope_id, source_event_id, type
    ),
    ADD KEY idx_notification_scope_created (
        recipient_scope_type, recipient_scope_id, created_at DESC, id DESC
    ),
    ADD KEY idx_notification_scope_unread (
        recipient_scope_type, recipient_scope_id, read_at, created_at DESC, id DESC
    ),
    ADD CONSTRAINT chk_notification_scope CHECK (
        (recipient_scope_type IS NULL AND recipient_scope_id IS NULL
            AND recipient_user_id IS NOT NULL AND business_id IS NULL)
        OR
        (recipient_scope_type = 'USER' AND recipient_user_id = recipient_scope_id
            AND business_id IS NULL)
        OR
        (recipient_scope_type = 'BUSINESS' AND recipient_user_id IS NULL
            AND business_id = recipient_scope_id)
    ),
    ADD CONSTRAINT chk_notification_type CHECK (type IN (
        'BUYER_ORDER_CONFIRMED', 'BUYER_ORDER_CANCELLED', 'BUYER_REFUND_COMPLETED',
        'BUYER_ORDER_ACCEPTED', 'BUYER_ORDER_PROCESSING', 'BUYER_ORDER_SHIPPED',
        'BUYER_ORDER_DELIVERED', 'SELLER_NEW_ORDER', 'SELLER_ORDER_CANCELLED',
        'ORDER_CONFIRMED'
    )),
    ADD CONSTRAINT chk_notification_message_key CHECK (message_key IN (
        'BUYER_ORDER_CONFIRMED_V1', 'BUYER_ORDER_CANCELLED_V1',
        'BUYER_REFUND_COMPLETED_V1', 'BUYER_ORDER_ACCEPTED_V1',
        'BUYER_ORDER_PROCESSING_V1', 'BUYER_ORDER_SHIPPED_V1',
        'BUYER_ORDER_DELIVERED_V1', 'SELLER_NEW_ORDER_V1',
        'SELLER_ORDER_CANCELLED_V1', 'ORDER_CONFIRMED_V1'
    )),
    ADD CONSTRAINT chk_notification_route CHECK (
        route REGEXP '^/account/orders/[0-7][0-9A-HJKMNP-TV-Z]{25}$'
        OR route REGEXP '^/seller/orders/[0-7][0-9A-HJKMNP-TV-Z]{25}$'
        OR route = '/account'
    ),
    ADD CONSTRAINT chk_notification_projection_source_version CHECK (source_event_version > 0),
    ADD CONSTRAINT chk_notification_args CHECK (
        JSON_TYPE(message_args_json) = 'OBJECT'
        AND JSON_LENGTH(message_args_json) BETWEEN 1 AND 3
        AND JSON_UNQUOTE(JSON_EXTRACT(message_args_json, '$.orderId')) IS NOT NULL
    );
