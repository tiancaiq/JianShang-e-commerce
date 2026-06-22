CREATE TABLE business_applications (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    applicant_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_name VARCHAR(200) NOT NULL,
    business_type VARCHAR(64) NOT NULL,
    country VARCHAR(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    contact_email VARCHAR(320) NOT NULL,
    contact_phone VARCHAR(32) NULL,
    public_city VARCHAR(120) NOT NULL,
    public_region VARCHAR(120) NOT NULL,
    website_url VARCHAR(2048) NULL,
    description VARCHAR(1000) NULL,
    status VARCHAR(32) NOT NULL,
    submitted_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_business_applications_applicant FOREIGN KEY (applicant_user_id) REFERENCES users (id),
    CONSTRAINT chk_business_applications_status CHECK (
        status IN ('DRAFT', 'PENDING_VERIFICATION', 'UNDER_REVIEW', 'VERIFICATION_FAILED', 'APPROVED', 'REJECTED', 'INFORMATION_REQUESTED')
    ),
    CONSTRAINT chk_business_applications_country CHECK (country = upper(country))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_business_applications_applicant_status ON business_applications (applicant_user_id, status);
CREATE INDEX idx_business_applications_status_submitted ON business_applications (status, submitted_at);
