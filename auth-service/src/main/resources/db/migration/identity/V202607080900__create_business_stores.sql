CREATE TABLE stores (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    slug VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NULL,
    logo_url VARCHAR(2048) NULL,
    banner_url VARCHAR(2048) NULL,
    support_email VARCHAR(320) NULL,
    support_phone VARCHAR(32) NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_stores_business UNIQUE (business_id),
    CONSTRAINT uk_stores_slug UNIQUE (slug),
    CONSTRAINT fk_stores_business FOREIGN KEY (business_id) REFERENCES businesses (id),
    CONSTRAINT chk_stores_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT chk_stores_slug CHECK (slug = lower(slug))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_stores_status_slug ON stores (status, slug);

INSERT INTO stores (
    id, business_id, slug, name, description, logo_url, banner_url,
    support_email, support_phone, status, version, created_at, updated_at
)
SELECT
    b.id,
    b.id,
    lower(concat('business-', b.id)),
    b.legal_name,
    ba.description,
    null,
    null,
    ba.contact_email,
    ba.contact_phone,
    'ACTIVE',
    0,
    current_timestamp(6),
    current_timestamp(6)
FROM businesses b
JOIN business_applications ba ON ba.id = b.application_id
WHERE NOT EXISTS (
    SELECT 1
    FROM stores s
    WHERE s.business_id = b.id
);
