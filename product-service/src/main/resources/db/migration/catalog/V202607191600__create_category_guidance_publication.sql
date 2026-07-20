CREATE TABLE category_guidance_versions (
    category_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    language VARCHAR(35) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version BIGINT NOT NULL,
    supersedes_version BIGINT NULL,
    lifecycle VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    visibility VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    category_slug VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NULL,
    category_name VARCHAR(160) NULL,
    title VARCHAR(180) NULL,
    body TEXT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    effective_from DATETIME(6) NULL,
    invalidated_at DATETIME(6) NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correlation_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (category_id, language, source_version),
    CONSTRAINT fk_category_guidance_category
        FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT chk_category_guidance_version_positive CHECK (source_version > 0),
    CONSTRAINT chk_category_guidance_supersedes CHECK (
        supersedes_version IS NULL OR supersedes_version < source_version
    ),
    CONSTRAINT chk_category_guidance_lifecycle CHECK (lifecycle IN ('ACTIVE', 'INVALIDATED')),
    CONSTRAINT chk_category_guidance_visibility CHECK (visibility = 'PUBLIC'),
    CONSTRAINT chk_category_guidance_language CHECK (
        char_length(language) BETWEEN 2 AND 35 AND language = lower(language)
    ),
    CONSTRAINT chk_category_guidance_shape CHECK (
        (
            lifecycle = 'ACTIVE'
            AND category_slug IS NOT NULL
            AND category_name IS NOT NULL
            AND title IS NOT NULL
            AND body IS NOT NULL
            AND content_hash IS NOT NULL
            AND effective_from IS NOT NULL
            AND invalidated_at IS NULL
        )
        OR
        (
            lifecycle = 'INVALIDATED'
            AND supersedes_version IS NOT NULL
            AND category_slug IS NULL
            AND category_name IS NULL
            AND title IS NULL
            AND body IS NULL
            AND content_hash IS NULL
            AND effective_from IS NULL
            AND invalidated_at IS NOT NULL
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_category_guidance_latest
    ON category_guidance_versions (category_id, language, source_version);
CREATE INDEX idx_category_guidance_active_export
    ON category_guidance_versions (lifecycle, category_id, language, source_version);
CREATE INDEX idx_category_guidance_created
    ON category_guidance_versions (created_at, category_id, language, source_version);
