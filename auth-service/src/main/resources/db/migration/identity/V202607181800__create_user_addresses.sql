CREATE TABLE addresses (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    label VARCHAR(40) NULL,
    recipient_name VARCHAR(120) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    line1 VARCHAR(200) NOT NULL,
    line2 VARCHAR(200) NULL,
    city VARCHAR(100) NOT NULL,
    region VARCHAR(100) NOT NULL,
    postal_code VARCHAR(32) NOT NULL,
    country_code CHAR(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    default_marker TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN is_default = TRUE THEN 1 ELSE NULL END
        ) STORED,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_addresses_one_default_per_user
        UNIQUE (user_id, default_marker),
    CONSTRAINT chk_addresses_country_code
        CHECK (country_code REGEXP '^[A-Z]{2}$'),
    CONSTRAINT chk_addresses_recipient_name
        CHECK (CHAR_LENGTH(TRIM(recipient_name)) > 0),
    CONSTRAINT chk_addresses_phone
        CHECK (phone REGEXP '^\\+[1-9][0-9]{7,14}$'),
    CONSTRAINT chk_addresses_line1
        CHECK (CHAR_LENGTH(TRIM(line1)) > 0),
    CONSTRAINT chk_addresses_city
        CHECK (CHAR_LENGTH(TRIM(city)) > 0),
    CONSTRAINT chk_addresses_region
        CHECK (CHAR_LENGTH(TRIM(region)) > 0),
    CONSTRAINT chk_addresses_postal_code
        CHECK (CHAR_LENGTH(TRIM(postal_code)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_addresses_user_list
    ON addresses (user_id, is_default, updated_at, id);

CREATE INDEX idx_addresses_default_promotion
    ON addresses (user_id, created_at, id);

ALTER TABLE addresses
    ADD CONSTRAINT fk_addresses_user
        FOREIGN KEY (user_id) REFERENCES users (id);
