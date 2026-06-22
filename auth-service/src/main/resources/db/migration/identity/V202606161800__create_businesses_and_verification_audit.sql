INSERT INTO roles (id, name, description)
VALUES
    ('PLATFORM_ADMIN', 'PLATFORM_ADMIN', 'Can review platform administration queues')
ON DUPLICATE KEY UPDATE name = name;

ALTER TABLE business_applications
    ADD COLUMN reviewer_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER submitted_at,
    ADD COLUMN approved_business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER reviewer_user_id,
    ADD COLUMN decision_reason VARCHAR(1000) NULL AFTER approved_business_id,
    ADD COLUMN decided_at DATETIME(6) NULL AFTER decision_reason;

CREATE TABLE businesses (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    application_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_name VARCHAR(200) NOT NULL,
    business_type VARCHAR(64) NOT NULL,
    country VARCHAR(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) NOT NULL,
    approved_at DATETIME(6) NOT NULL,
    approved_by CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_businesses_application UNIQUE (application_id),
    CONSTRAINT fk_businesses_application FOREIGN KEY (application_id) REFERENCES business_applications (id),
    CONSTRAINT fk_businesses_approved_by FOREIGN KEY (approved_by) REFERENCES users (id),
    CONSTRAINT chk_businesses_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT chk_businesses_country CHECK (country = upper(country))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_businesses_status ON businesses (status);

CREATE TABLE business_memberships (
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) NOT NULL,
    invited_by CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (business_id, user_id),
    CONSTRAINT fk_business_memberships_business FOREIGN KEY (business_id) REFERENCES businesses (id),
    CONSTRAINT fk_business_memberships_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_business_memberships_invited_by FOREIGN KEY (invited_by) REFERENCES users (id),
    CONSTRAINT chk_business_memberships_role CHECK (role IN ('OWNER', 'MANAGER', 'STAFF')),
    CONSTRAINT chk_business_memberships_status CHECK (status IN ('INVITED', 'ACTIVE', 'REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_business_memberships_user_status ON business_memberships (user_id, status);

CREATE TABLE business_verification_events (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    application_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source VARCHAR(32) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    outcome VARCHAR(64) NOT NULL,
    reason VARCHAR(1000) NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_business_verification_events_provider UNIQUE (provider_event_id),
    CONSTRAINT fk_business_verification_events_application FOREIGN KEY (application_id) REFERENCES business_applications (id),
    CONSTRAINT fk_business_verification_events_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT chk_business_verification_events_source CHECK (source IN ('PROVIDER', 'ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_business_verification_events_application ON business_verification_events (application_id, created_at);

ALTER TABLE business_applications
    ADD CONSTRAINT fk_business_applications_reviewer FOREIGN KEY (reviewer_user_id) REFERENCES users (id),
    ADD CONSTRAINT fk_business_applications_approved_business FOREIGN KEY (approved_business_id) REFERENCES businesses (id);
