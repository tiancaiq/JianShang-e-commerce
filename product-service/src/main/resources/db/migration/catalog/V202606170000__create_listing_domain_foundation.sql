CREATE TABLE categories (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parent_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    slug VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_categories_slug UNIQUE (slug),
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories (id),
    CONSTRAINT chk_categories_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT chk_categories_slug CHECK (slug = lower(slug))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_categories_parent_status_order ON categories (parent_id, status, display_order);

CREATE TABLE category_attribute_definitions (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    category_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attribute_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    label VARCHAR(160) NOT NULL,
    data_type VARCHAR(32) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT FALSE,
    allowed_values_json JSON NULL,
    validation_json JSON NULL,
    display_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_category_attribute_definitions_key UNIQUE (category_id, attribute_key),
    CONSTRAINT fk_category_attribute_definitions_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT chk_category_attribute_definitions_data_type CHECK (
        data_type IN ('STRING', 'NUMBER', 'BOOLEAN', 'DATE', 'ENUM')
    ),
    CONSTRAINT chk_category_attribute_definitions_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT chk_category_attribute_definitions_key CHECK (attribute_key = lower(attribute_key))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_category_attribute_definitions_category_status_order
    ON category_attribute_definitions (category_id, status, display_order);

CREATE TABLE listings (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seller_type VARCHAR(32) NOT NULL,
    individual_seller_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    store_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    category_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(180) NOT NULL,
    description TEXT NOT NULL,
    condition_code VARCHAR(32) NOT NULL,
    condition_notes VARCHAR(1000) NULL,
    price_amount DECIMAL(19, 4) NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    negotiable BOOLEAN NOT NULL DEFAULT FALSE,
    sku VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NULL,
    quantity INT NOT NULL,
    public_city VARCHAR(120) NULL,
    public_region VARCHAR(120) NULL,
    payment_preferences_json JSON NULL,
    delivery_preferences_json JSON NULL,
    status VARCHAR(32) NOT NULL,
    moderation_status VARCHAR(32) NOT NULL,
    published_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_listings_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT chk_listings_seller_type CHECK (seller_type IN ('INDIVIDUAL', 'BUSINESS')),
    CONSTRAINT chk_listings_owner_shape CHECK (
        (
            seller_type = 'INDIVIDUAL'
            AND individual_seller_user_id IS NOT NULL
            AND business_id IS NULL
            AND store_id IS NULL
        )
        OR
        (
            seller_type = 'BUSINESS'
            AND individual_seller_user_id IS NULL
            AND business_id IS NOT NULL
        )
    ),
    CONSTRAINT chk_listings_condition CHECK (
        condition_code IN ('NEW', 'OPEN_BOX', 'LIKE_NEW', 'GOOD', 'FAIR', 'FOR_PARTS')
    ),
    CONSTRAINT chk_listings_status CHECK (
        status IN ('DRAFT', 'PENDING_REVIEW', 'ACTIVE', 'PAUSED', 'SOLD', 'CLOSED', 'REJECTED', 'CHANGES_REQUESTED')
    ),
    CONSTRAINT chk_listings_moderation_status CHECK (
        moderation_status IN ('NOT_SUBMITTED', 'PENDING', 'APPROVED', 'REJECTED', 'CHANGES_REQUESTED')
    ),
    CONSTRAINT chk_listings_individual_rules CHECK (
        seller_type <> 'INDIVIDUAL'
        OR (
            quantity = 1
            AND sku IS NULL
            AND public_city IS NOT NULL
            AND public_region IS NOT NULL
        )
    ),
    CONSTRAINT chk_listings_business_rules CHECK (
        seller_type <> 'BUSINESS'
        OR (
            quantity >= 0
            AND negotiable = FALSE
        )
    ),
    CONSTRAINT chk_listings_price CHECK (price_amount >= 0),
    CONSTRAINT chk_listings_currency CHECK (currency = upper(currency))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE UNIQUE INDEX uk_listings_business_sku ON listings (business_id, sku);
CREATE INDEX idx_listings_status_seller_published ON listings (status, seller_type, published_at);
CREATE INDEX idx_listings_business_status_updated ON listings (business_id, status, updated_at);
CREATE INDEX idx_listings_individual_status_updated ON listings (individual_seller_user_id, status, updated_at);
CREATE INDEX idx_listings_category_status_published ON listings (category_id, status, published_at);

CREATE TABLE listing_attributes (
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attribute_definition_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    string_value VARCHAR(1000) NULL,
    number_value DECIMAL(19, 4) NULL,
    boolean_value BOOLEAN NULL,
    date_value DATE NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (listing_id, attribute_definition_id),
    CONSTRAINT fk_listing_attributes_listing FOREIGN KEY (listing_id) REFERENCES listings (id),
    CONSTRAINT fk_listing_attributes_definition FOREIGN KEY (attribute_definition_id)
        REFERENCES category_attribute_definitions (id),
    CONSTRAINT chk_listing_attributes_single_value CHECK (
        (string_value IS NOT NULL) + (number_value IS NOT NULL)
        + (boolean_value IS NOT NULL) + (date_value IS NOT NULL) = 1
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
