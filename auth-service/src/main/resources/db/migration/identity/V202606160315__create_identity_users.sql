CREATE TABLE users (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    keycloak_sub VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    email VARCHAR(320) NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    display_name VARCHAR(200) NULL,
    phone VARCHAR(32) NULL,
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
    avatar_url VARCHAR(2048) NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_keycloak_sub UNIQUE (keycloak_sub),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_users_email ON users (email);
CREATE INDEX idx_users_status ON users (status);
