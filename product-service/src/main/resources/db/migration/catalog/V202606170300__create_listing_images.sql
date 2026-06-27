CREATE TABLE listing_images (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    media_object_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    display_order INT NOT NULL,
    alt_text VARCHAR(250) NULL,
    moderation_status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_listing_images_listing_media UNIQUE (listing_id, media_object_id),
    CONSTRAINT uk_listing_images_listing_order UNIQUE (listing_id, display_order),
    CONSTRAINT fk_listing_images_listing FOREIGN KEY (listing_id) REFERENCES listings (id),
    CONSTRAINT fk_listing_images_media FOREIGN KEY (media_object_id) REFERENCES listing_media_objects (id),
    CONSTRAINT chk_listing_images_display_order CHECK (display_order >= 0),
    CONSTRAINT chk_listing_images_moderation_status CHECK (
        moderation_status IN ('NOT_SUBMITTED', 'PENDING', 'APPROVED', 'REJECTED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_images_listing_order
    ON listing_images (listing_id, display_order);
