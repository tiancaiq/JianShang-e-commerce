CREATE TABLE moderation_cases (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_type VARCHAR(32) NOT NULL,
    subject_listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_seller_type VARCHAR(32) NOT NULL,
    subject_individual_seller_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    subject_business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    submitted_by_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) NOT NULL,
    priority VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    assigned_admin_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_moderation_cases_listing FOREIGN KEY (subject_listing_id) REFERENCES listings (id),
    CONSTRAINT chk_moderation_cases_type CHECK (case_type IN ('LISTING_REVIEW')),
    CONSTRAINT chk_moderation_cases_seller_type CHECK (subject_seller_type IN ('INDIVIDUAL', 'BUSINESS')),
    CONSTRAINT chk_moderation_cases_status CHECK (status IN ('OPEN', 'CLAIMED', 'RESOLVED')),
    CONSTRAINT chk_moderation_cases_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH')),
    CONSTRAINT chk_moderation_cases_listing_review_shape CHECK (
        case_type = 'LISTING_REVIEW'
        AND subject_listing_id IS NOT NULL
    ),
    CONSTRAINT chk_moderation_cases_seller_snapshot CHECK (
        (
            subject_seller_type = 'INDIVIDUAL'
            AND subject_individual_seller_user_id IS NOT NULL
            AND subject_business_id IS NULL
        )
        OR
        (
            subject_seller_type = 'BUSINESS'
            AND subject_individual_seller_user_id IS NULL
            AND subject_business_id IS NOT NULL
        )
    ),
    CONSTRAINT chk_moderation_cases_status_assignment CHECK (
        (
            status = 'OPEN'
            AND assigned_admin_user_id IS NULL
            AND resolved_at IS NULL
        )
        OR
        (
            status = 'CLAIMED'
            AND assigned_admin_user_id IS NOT NULL
            AND resolved_at IS NULL
        )
        OR
        (
            status = 'RESOLVED'
            AND resolved_at IS NOT NULL
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_moderation_cases_open_listing_reviews
    ON moderation_cases (case_type, status, priority, created_at, id);

CREATE INDEX idx_moderation_cases_assigned_admin
    ON moderation_cases (assigned_admin_user_id, status, updated_at);

CREATE INDEX idx_moderation_cases_listing_created
    ON moderation_cases (subject_listing_id, created_at);
