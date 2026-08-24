CREATE INDEX idx_listings_analytics_created
    ON listings (created_at, category_id, status, id);

CREATE INDEX idx_moderation_cases_analytics_resolved
    ON moderation_cases (case_type, resolved_at, id);

CREATE INDEX idx_listing_moderation_decisions_analytics_created
    ON listing_moderation_decisions (created_at, decision, id);

CREATE INDEX idx_enforcement_actions_analytics_created
    ON enforcement_actions (created_at, action_type, id);

CREATE INDEX idx_enforcement_actions_analytics_active
    ON enforcement_actions (revoked_at, effective_at, expires_at, action_type, id);
