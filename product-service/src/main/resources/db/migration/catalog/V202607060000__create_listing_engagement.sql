CREATE TABLE listing_visits (
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (listing_id, user_id),
    CONSTRAINT fk_listing_visits_listing FOREIGN KEY (listing_id) REFERENCES listings (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_visits_user_created ON listing_visits (user_id, created_at);

CREATE TABLE listing_likes (
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (listing_id, user_id),
    CONSTRAINT fk_listing_likes_listing FOREIGN KEY (listing_id) REFERENCES listings (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_likes_user_active_updated ON listing_likes (user_id, active, updated_at);
CREATE INDEX idx_listing_likes_listing_active ON listing_likes (listing_id, active);

CREATE TABLE listing_engagement_stats (
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    visit_count BIGINT NOT NULL DEFAULT 0,
    like_count BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (listing_id),
    CONSTRAINT fk_listing_engagement_stats_listing FOREIGN KEY (listing_id) REFERENCES listings (id),
    CONSTRAINT chk_listing_engagement_stats_visit_count CHECK (visit_count >= 0),
    CONSTRAINT chk_listing_engagement_stats_like_count CHECK (like_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
