ALTER TABLE listings
    DROP CHECK chk_listings_status;

ALTER TABLE listings
    ADD CONSTRAINT chk_listings_status CHECK (
        status IN (
            'DRAFT',
            'PENDING_REVIEW',
            'ACTIVE',
            'PAUSED',
            'SOLD',
            'CLOSED',
            'REJECTED',
            'CHANGES_REQUESTED',
            'REMOVED_BY_ADMIN'
        )
    );

ALTER TABLE listing_moderation_decisions
    DROP CHECK chk_listing_moderation_decisions_decision;

ALTER TABLE listing_moderation_decisions
    ADD CONSTRAINT chk_listing_moderation_decisions_decision CHECK (
        decision IN (
            'APPROVE',
            'REJECT',
            'REQUEST_CHANGES',
            'ADMIN_EDIT',
            'ADMIN_REMOVE'
        )
    );
