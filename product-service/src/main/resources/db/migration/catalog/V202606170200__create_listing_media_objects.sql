CREATE TABLE listing_media_objects (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seller_type VARCHAR(32) NOT NULL,
    individual_seller_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    object_bucket VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_file_name VARCHAR(255) NULL,
    content_type VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    upload_status VARCHAR(32) NOT NULL,
    moderation_status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_listing_media_objects_key UNIQUE (object_bucket, object_key),
    CONSTRAINT fk_listing_media_objects_listing FOREIGN KEY (listing_id) REFERENCES listings (id),
    CONSTRAINT chk_listing_media_objects_seller_type CHECK (seller_type IN ('INDIVIDUAL', 'BUSINESS')),
    CONSTRAINT chk_listing_media_objects_owner_shape CHECK (
        (
            seller_type = 'INDIVIDUAL'
            AND individual_seller_user_id IS NOT NULL
            AND business_id IS NULL
        )
        OR
        (
            seller_type = 'BUSINESS'
            AND individual_seller_user_id IS NULL
            AND business_id IS NOT NULL
        )
    ),
    CONSTRAINT chk_listing_media_objects_upload_status CHECK (
        upload_status IN ('PENDING_UPLOAD', 'UPLOADED', 'FAILED')
    ),
    CONSTRAINT chk_listing_media_objects_moderation_status CHECK (
        moderation_status IN ('NOT_SUBMITTED', 'PENDING', 'APPROVED', 'REJECTED')
    ),
    CONSTRAINT chk_listing_media_objects_size CHECK (size_bytes > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_media_objects_listing_status_order
    ON listing_media_objects (listing_id, upload_status, created_at);

CREATE INDEX idx_listing_media_objects_individual_owner
    ON listing_media_objects (individual_seller_user_id, created_at);

CREATE INDEX idx_listing_media_objects_business_owner
    ON listing_media_objects (business_id, created_at);
