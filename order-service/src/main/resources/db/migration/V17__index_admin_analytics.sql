CREATE INDEX idx_orders_analytics_confirmed
    ON orders (confirmed_at, id);

CREATE INDEX idx_order_disputes_analytics_created
    ON order_disputes (created_at, id);

CREATE INDEX idx_order_disputes_analytics_resolved
    ON order_disputes (resolved_at, status, id);

CREATE INDEX idx_order_disputes_analytics_backlog
    ON order_disputes (status, assigned_admin_id, created_at, id);
