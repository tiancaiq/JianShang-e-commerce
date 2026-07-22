ALTER TABLE moderation_cases
    DROP CHECK chk_moderation_cases_type,
    DROP CHECK chk_moderation_cases_listing_review_shape,
    ADD COLUMN routing_queue VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER priority,
    ADD CONSTRAINT chk_moderation_cases_type
        CHECK (case_type IN ('LISTING_REVIEW', 'LISTING_REPORT')),
    ADD CONSTRAINT chk_moderation_cases_subject_shape
        CHECK (
            subject_listing_id IS NOT NULL
            AND (
                (case_type = 'LISTING_REVIEW' AND routing_queue IS NULL)
                OR
                (case_type = 'LISTING_REPORT' AND routing_queue IN (
                    'TRUST_SAFETY_LEGAL',
                    'TRUST_SAFETY',
                    'MARKETPLACE_INTEGRITY',
                    'LEGAL_IP',
                    'PRIVACY'
                ))
            )
        ),
    ADD COLUMN active_listing_report_key VARCHAR(72) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (
            CASE
                WHEN case_type = 'LISTING_REPORT' AND status IN ('OPEN', 'CLAIMED')
                    THEN CONCAT(subject_listing_id, ':', routing_queue)
                ELSE NULL
            END
        ) STORED;

CREATE UNIQUE INDEX uk_moderation_cases_active_listing_report
    ON moderation_cases (active_listing_report_key);

CREATE INDEX idx_moderation_cases_open_listing_reports
    ON moderation_cases (case_type, routing_queue, status, priority, created_at, id);

CREATE TABLE listing_report_subject_snapshots (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_version BIGINT NOT NULL,
    seller_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    individual_seller_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    policy_version VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    public_snapshot_json JSON NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    captured_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_listing_report_snapshot_listing FOREIGN KEY (listing_id) REFERENCES listings (id),
    CONSTRAINT chk_listing_report_snapshot_version CHECK (listing_version >= 0),
    CONSTRAINT chk_listing_report_snapshot_seller_type CHECK (seller_type IN ('INDIVIDUAL', 'BUSINESS')),
    CONSTRAINT chk_listing_report_snapshot_owner CHECK (
        (seller_type = 'INDIVIDUAL' AND individual_seller_user_id IS NOT NULL AND business_id IS NULL)
        OR
        (seller_type = 'BUSINESS' AND individual_seller_user_id IS NULL AND business_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_report_snapshots_listing_version
    ON listing_report_subject_snapshots (listing_id, listing_version, captured_at);

CREATE TABLE listing_report_subject_media_snapshots (
    subject_snapshot_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_image_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    media_object_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    display_order INT NOT NULL,
    content_type VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size_bytes BIGINT NOT NULL,
    source_checksum_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (subject_snapshot_id, listing_image_id),
    CONSTRAINT fk_listing_report_media_snapshot
        FOREIGN KEY (subject_snapshot_id) REFERENCES listing_report_subject_snapshots (id),
    CONSTRAINT chk_listing_report_media_snapshot_order CHECK (display_order >= 0),
    CONSTRAINT chk_listing_report_media_snapshot_size CHECK (size_bytes >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE listing_reports (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reporter_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    routing_queue VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    priority VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_version VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_snapshot_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    moderation_case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_expires_at DATETIME(6) NOT NULL,
    metadata_expires_at DATETIME(6) NOT NULL,
    legal_review_required BOOLEAN NOT NULL DEFAULT TRUE,
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_listing_reports_listing FOREIGN KEY (listing_id) REFERENCES listings (id),
    CONSTRAINT fk_listing_reports_snapshot FOREIGN KEY (subject_snapshot_id)
        REFERENCES listing_report_subject_snapshots (id),
    CONSTRAINT fk_listing_reports_case FOREIGN KEY (moderation_case_id) REFERENCES moderation_cases (id),
    CONSTRAINT chk_listing_reports_reason CHECK (reason_code IN (
        'PROHIBITED_OR_REGULATED_ITEM',
        'DANGEROUS_OR_UNSAFE_ITEM',
        'FRAUD_OR_MISREPRESENTATION',
        'COUNTERFEIT_OR_IP_CONCERN',
        'HATE_HARASSMENT_OR_THREAT',
        'SEXUAL_OR_EXPLOITATIVE_CONTENT',
        'PRIVACY_OR_PERSONAL_DATA',
        'DUPLICATE_OR_SPAM',
        'OTHER_POLICY_CONCERN'
    )),
    CONSTRAINT chk_listing_reports_route CHECK (routing_queue IN (
        'TRUST_SAFETY_LEGAL',
        'TRUST_SAFETY',
        'MARKETPLACE_INTEGRITY',
        'LEGAL_IP',
        'PRIVACY'
    )),
    CONSTRAINT chk_listing_reports_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH')),
    CONSTRAINT chk_listing_reports_status CHECK (status IN ('RECEIVED', 'UNDER_REVIEW', 'RESOLVED')),
    CONSTRAINT chk_listing_reports_retention CHECK (
        content_expires_at > created_at AND metadata_expires_at > content_expires_at
    ),
    CONSTRAINT chk_listing_reports_resolution CHECK (
        (status IN ('RECEIVED', 'UNDER_REVIEW') AND resolved_at IS NULL)
        OR (status = 'RESOLVED' AND resolved_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_reports_owner_created
    ON listing_reports (reporter_user_id, created_at, id);
CREATE INDEX idx_listing_reports_semantic_duplicate
    ON listing_reports (reporter_user_id, listing_id, reason_code, created_at, id);
CREATE INDEX idx_listing_reports_case_created
    ON listing_reports (moderation_case_id, created_at, id);
CREATE INDEX idx_listing_reports_retention
    ON listing_reports (legal_hold, content_expires_at, metadata_expires_at, id);

CREATE TABLE listing_report_evidence (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    statement_text TEXT NULL,
    listing_image_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    media_object_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    content_type VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NULL,
    size_bytes BIGINT NULL,
    evidence_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_listing_report_evidence_report FOREIGN KEY (report_id) REFERENCES listing_reports (id),
    CONSTRAINT chk_listing_report_evidence_type CHECK (evidence_type IN ('STATEMENT', 'LISTING_MEDIA_REF')),
    CONSTRAINT chk_listing_report_evidence_ordinal CHECK (ordinal >= 0),
    CONSTRAINT chk_listing_report_evidence_shape CHECK (
        (
            evidence_type = 'STATEMENT'
            AND statement_text IS NOT NULL
            AND listing_image_id IS NULL
            AND media_object_id IS NULL
            AND content_type IS NULL
            AND size_bytes IS NULL
        )
        OR
        (
            evidence_type = 'LISTING_MEDIA_REF'
            AND statement_text IS NULL
            AND listing_image_id IS NOT NULL
            AND media_object_id IS NOT NULL
            AND content_type IS NOT NULL
            AND size_bytes IS NOT NULL
            AND size_bytes >= 0
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE UNIQUE INDEX uk_listing_report_evidence_ordinal
    ON listing_report_evidence (report_id, ordinal);

CREATE TABLE listing_report_idempotency (
    reporter_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (reporter_user_id, idempotency_key),
    CONSTRAINT fk_listing_report_idempotency_report FOREIGN KEY (report_id) REFERENCES listing_reports (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_report_idempotency_expiry
    ON listing_report_idempotency (expires_at, reporter_user_id, idempotency_key);

CREATE TABLE listing_report_duplicate_windows (
    reporter_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (reporter_user_id, listing_id, reason_code),
    CONSTRAINT fk_listing_report_duplicate_listing FOREIGN KEY (listing_id) REFERENCES listings (id),
    CONSTRAINT fk_listing_report_duplicate_report FOREIGN KEY (report_id) REFERENCES listing_reports (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_report_duplicate_expiry
    ON listing_report_duplicate_windows (expires_at, reporter_user_id, listing_id);

CREATE TABLE listing_report_history (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_version VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_listing_report_history_report FOREIGN KEY (report_id) REFERENCES listing_reports (id),
    CONSTRAINT chk_listing_report_history_event CHECK (event_type IN ('RECEIVED')),
    CONSTRAINT chk_listing_report_history_status CHECK (to_status IN ('RECEIVED', 'UNDER_REVIEW', 'RESOLVED')),
    CONSTRAINT chk_listing_report_history_actor CHECK (actor_type IN ('REPORTER', 'SYSTEM', 'ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_report_history_report_time
    ON listing_report_history (report_id, occurred_at, id);

CREATE TABLE listing_report_rate_limit_buckets (
    abuse_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bucket_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bucket_start DATETIME(6) NOT NULL,
    bucket_end DATETIME(6) NOT NULL,
    accepted_count INT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (abuse_key_hash, bucket_type, bucket_start),
    CONSTRAINT chk_listing_report_rate_bucket_type CHECK (bucket_type IN ('HOUR', 'DAY')),
    CONSTRAINT chk_listing_report_rate_bucket_count CHECK (accepted_count >= 0),
    CONSTRAINT chk_listing_report_rate_bucket_time CHECK (
        bucket_end > bucket_start AND expires_at >= bucket_end
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_report_rate_bucket_expiry
    ON listing_report_rate_limit_buckets (expires_at, abuse_key_hash);
