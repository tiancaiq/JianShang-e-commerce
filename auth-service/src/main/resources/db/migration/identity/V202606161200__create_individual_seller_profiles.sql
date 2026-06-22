CREATE TABLE roles (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_roles_name UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO roles (id, name, description)
VALUES
    ('BUYER', 'BUYER', 'Can use buyer account features'),
    ('INDIVIDUAL_SELLER', 'INDIVIDUAL_SELLER', 'Can use individual seller features')
ON DUPLICATE KEY UPDATE name = name;

CREATE TABLE user_roles (
    user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    granted_by CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    granted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id),
    CONSTRAINT fk_user_roles_granted_by FOREIGN KEY (granted_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_user_roles_role ON user_roles (role_id);

CREATE TABLE individual_seller_profiles (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    public_city VARCHAR(120) NOT NULL,
    public_region VARCHAR(120) NOT NULL,
    terms_version VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    completed_sales_count BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_individual_seller_profiles_user UNIQUE (user_id),
    CONSTRAINT fk_individual_seller_profiles_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_individual_seller_profiles_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT chk_individual_seller_profiles_completed_sales CHECK (completed_sales_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_individual_seller_profiles_status ON individual_seller_profiles (status);
