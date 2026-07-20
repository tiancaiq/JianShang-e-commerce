CREATE TABLE agent_listing_proposals (
    proposal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_listing_version INT UNSIGNED NOT NULL,
    client_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    media_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(20) NOT NULL,
    proposal_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    schema_version VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_media_evidence_json JSON NULL,
    proposal_json JSON NULL,
    result_metadata_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    dismissed_at DATETIME(6) NULL,
    content_purged_at DATETIME(6) NULL,
    tombstone_expires_at DATETIME(6) NOT NULL,
    optimistic_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (proposal_id),
    UNIQUE KEY uq_agent_listing_proposals_actor_client (
        actor_user_id,
        client_request_id
    ),
    UNIQUE KEY uq_agent_listing_proposals_id_actor (
        proposal_id,
        actor_user_id
    ),
    KEY idx_agent_listing_proposals_expiry (
        status,
        expires_at,
        proposal_id
    ),
    KEY idx_agent_listing_proposals_tombstone (
        tombstone_expires_at,
        proposal_id
    ),
    CONSTRAINT chk_agent_listing_proposals_status
        CHECK (status IN ('READY', 'DISMISSED', 'EXPIRED')),
    CONSTRAINT chk_agent_listing_proposals_schema
        CHECK (schema_version = 'LISTING_PROPOSAL_V1'),
    CONSTRAINT chk_agent_listing_proposals_request_hash
        CHECK (request_hash REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_agent_listing_proposals_media_fingerprint
        CHECK (media_fingerprint REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_agent_listing_proposals_version
        CHECK (source_listing_version BETWEEN 1 AND 2147483647),
    CONSTRAINT chk_agent_listing_proposals_content_shape
        CHECK (
            (
                status = 'READY'
                AND dismissed_at IS NULL
                AND content_purged_at IS NULL
                AND source_media_evidence_json IS NOT NULL
                AND proposal_json IS NOT NULL
                AND result_metadata_json IS NOT NULL
            )
            OR (
                status IN ('DISMISSED', 'EXPIRED')
                AND content_purged_at IS NOT NULL
                AND source_media_evidence_json IS NULL
                AND proposal_json IS NULL
                AND result_metadata_json IS NULL
            )
        ),
    CONSTRAINT chk_agent_listing_proposals_dismissed_shape
        CHECK (
            (status = 'DISMISSED' AND dismissed_at IS NOT NULL)
            OR (status <> 'DISMISSED' AND dismissed_at IS NULL)
        ),
    CONSTRAINT chk_agent_listing_proposals_retention
        CHECK (
            expires_at = created_at + INTERVAL 24 HOUR
            AND tombstone_expires_at = created_at + INTERVAL 90 DAY
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE agent_listing_proposal_claims (
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claim_token CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claimed_at DATETIME(6) NOT NULL,
    claim_expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (actor_user_id, client_request_id),
    KEY idx_agent_listing_proposal_claims_expiry (
        claim_expires_at,
        actor_user_id,
        client_request_id
    ),
    CONSTRAINT chk_agent_listing_proposal_claim_hash
        CHECK (request_hash REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_agent_listing_proposal_claim_token
        CHECK (claim_token REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_agent_listing_proposal_claim_expiry
        CHECK (claim_expires_at > claimed_at)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE agent_listing_proposal_dismissals (
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    proposal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (actor_user_id, proposal_id, idempotency_key),
    CONSTRAINT fk_agent_listing_proposal_dismissals_owner
        FOREIGN KEY (proposal_id, actor_user_id)
        REFERENCES agent_listing_proposals (proposal_id, actor_user_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_agent_listing_proposal_dismissal_hash
        CHECK (request_hash REGEXP '^[0-9a-f]{64}$')
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
