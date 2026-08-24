ALTER TABLE listing_search_projection_work
    ADD COLUMN replay_sequence INT NOT NULL DEFAULT 0 AFTER listing_version;

CREATE TEMPORARY TABLE listing_search_projection_replay_rank AS
SELECT work_id,
       ROW_NUMBER() OVER (
           PARTITION BY listing_id, listing_version
           ORDER BY created_at, work_id
       ) - 1 AS replay_sequence
FROM listing_search_projection_work;

UPDATE listing_search_projection_work AS work
JOIN listing_search_projection_replay_rank AS ranked
  ON ranked.work_id = work.work_id
SET work.replay_sequence = ranked.replay_sequence;

DROP TEMPORARY TABLE listing_search_projection_replay_rank;

ALTER TABLE listing_search_projection_work
    ADD CONSTRAINT uk_listing_search_projection_version
        UNIQUE (listing_id, listing_version, replay_sequence),
    ADD CONSTRAINT chk_listing_search_projection_replay_sequence
        CHECK (replay_sequence >= 0);
