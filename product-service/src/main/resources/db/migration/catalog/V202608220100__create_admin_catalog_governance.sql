ALTER TABLE categories
    DROP CHECK chk_categories_status;

UPDATE categories SET status = 'DISABLED' WHERE status = 'INACTIVE';

ALTER TABLE categories
    ADD COLUMN description VARCHAR(1000) NULL AFTER name,
    ADD COLUMN seller_eligibility VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'BOTH' AFTER status,
    ADD COLUMN listing_creation_allowed BOOLEAN NOT NULL DEFAULT TRUE AFTER seller_eligibility,
    ADD COLUMN listing_submission_allowed BOOLEAN NOT NULL DEFAULT TRUE AFTER listing_creation_allowed,
    ADD COLUMN replacement_category_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER listing_submission_allowed,
    ADD COLUMN current_rule_version BIGINT NOT NULL DEFAULT 1 AFTER display_order,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER current_rule_version,
    ADD CONSTRAINT chk_categories_status CHECK (status IN ('ACTIVE', 'DISABLED', 'DEPRECATED')),
    ADD CONSTRAINT chk_categories_seller_eligibility CHECK (seller_eligibility IN ('INDIVIDUAL', 'BUSINESS', 'BOTH', 'NONE')),
    ADD CONSTRAINT fk_categories_replacement FOREIGN KEY (replacement_category_id) REFERENCES categories (id);

CREATE INDEX idx_categories_status_eligibility_order
    ON categories (status, seller_eligibility, display_order, id);

ALTER TABLE category_attribute_definitions
    DROP CHECK chk_category_attribute_definitions_data_type,
    DROP CHECK chk_category_attribute_definitions_status;

UPDATE category_attribute_definitions SET data_type = 'TEXT' WHERE data_type = 'STRING';
UPDATE category_attribute_definitions SET status = 'DISABLED' WHERE status = 'INACTIVE';

ALTER TABLE category_attribute_definitions
    ADD COLUMN description VARCHAR(1000) NULL AFTER label,
    ADD COLUMN searchable BOOLEAN NOT NULL DEFAULT FALSE AFTER `required`,
    ADD COLUMN filterable BOOLEAN NOT NULL DEFAULT FALSE AFTER searchable,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER status,
    ADD CONSTRAINT chk_category_attribute_definitions_data_type CHECK (
        data_type IN ('TEXT', 'NUMBER', 'BOOLEAN', 'ENUM', 'MULTI_ENUM')
    ),
    ADD CONSTRAINT chk_category_attribute_definitions_status CHECK (
        status IN ('ACTIVE', 'DISABLED', 'DEPRECATED')
    );

CREATE TABLE category_attribute_options (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attribute_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    option_value VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    label VARCHAR(160) NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_category_attribute_option_value UNIQUE (attribute_id, option_value),
    CONSTRAINT fk_category_attribute_option_attribute FOREIGN KEY (attribute_id)
        REFERENCES category_attribute_definitions (id),
    CONSTRAINT chk_category_attribute_option_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT chk_category_attribute_option_value CHECK (option_value = lower(option_value))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_category_attribute_options_attribute_status_order
    ON category_attribute_options (attribute_id, status, display_order, id);

CREATE TABLE category_rule_versions (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    category_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number BIGINT NOT NULL,
    configuration_json JSON NOT NULL,
    configuration_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    created_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_category_rule_version UNIQUE (category_id, version_number),
    CONSTRAINT fk_category_rule_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT chk_category_rule_version_positive CHECK (version_number > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO category_rule_versions (
    id, category_id, version_number, configuration_json, configuration_hash,
    reason, created_by_admin_id, created_at
)
SELECT
    CONCAT('01CATRUL', RIGHT(CONCAT(REPEAT('0', 18), ROW_NUMBER() OVER (ORDER BY id)), 18)),
    id,
    1,
    JSON_OBJECT(
        'status', status,
        'sellerEligibility', seller_eligibility,
        'listingCreationAllowed', listing_creation_allowed,
        'listingSubmissionAllowed', listing_submission_allowed,
        'attributes', JSON_ARRAY()
    ),
    SHA2(CONCAT_WS('|', id, status, seller_eligibility, listing_creation_allowed, listing_submission_allowed), 256),
    'Bootstrap existing catalog policy',
    NULL,
    CURRENT_TIMESTAMP(6)
FROM categories;

ALTER TABLE listings
    ADD COLUMN category_rule_version BIGINT NOT NULL DEFAULT 1 AFTER category_id;

CREATE INDEX idx_listings_category_rule_status
    ON listings (category_id, category_rule_version, status);

CREATE TABLE category_seller_guidance (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    category_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    guidance_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(180) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_category_seller_guidance_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT chk_category_seller_guidance_type CHECK (
        guidance_type IN ('TIP', 'WARNING', 'BEST_PRACTICE', 'POLICY_NOTICE')
    ),
    CONSTRAINT chk_category_seller_guidance_status CHECK (
        status IN ('ACTIVE', 'DISABLED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_category_seller_guidance_category_status_order
    ON category_seller_guidance (category_id, status, display_order, id);

CREATE TABLE catalog_events (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    category_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_state_json JSON NULL,
    new_state_json JSON NULL,
    reason VARCHAR(1000) NULL,
    correlation_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NULL,
    safe_metadata_json JSON NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_catalog_event_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT chk_catalog_event_actor CHECK (actor_type = 'HUMAN_ADMIN'),
    CONSTRAINT chk_catalog_event_target CHECK (
        target_type IN ('CATEGORY', 'ATTRIBUTE', 'ATTRIBUTE_OPTION', 'RULE_VERSION', 'SELLER_GUIDANCE')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_catalog_events_category_time
    ON catalog_events (category_id, occurred_at DESC, id DESC);

CREATE TABLE catalog_command_idempotency (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_catalog_command_idempotency UNIQUE (actor_user_id, operation, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
