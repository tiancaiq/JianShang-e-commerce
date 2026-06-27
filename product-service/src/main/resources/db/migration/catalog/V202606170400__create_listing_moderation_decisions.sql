CREATE TABLE listing_moderation_decisions (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision VARCHAR(32) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    reviewer_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_listing_moderation_decisions_listing FOREIGN KEY (listing_id) REFERENCES listings (id),
    CONSTRAINT chk_listing_moderation_decisions_decision CHECK (
        decision IN ('APPROVE', 'REJECT', 'REQUEST_CHANGES')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_moderation_decisions_listing_created
    ON listing_moderation_decisions (listing_id, created_at);
