ALTER TABLE listing_images
    DROP CHECK chk_listing_images_moderation_status;

ALTER TABLE listing_images
    ADD CONSTRAINT chk_listing_images_moderation_status CHECK (
        moderation_status IN ('NOT_SUBMITTED', 'PENDING', 'APPROVED', 'REJECTED', 'CHANGES_REQUESTED')
    );

ALTER TABLE listing_media_objects
    DROP CHECK chk_listing_media_objects_moderation_status;

ALTER TABLE listing_media_objects
    ADD CONSTRAINT chk_listing_media_objects_moderation_status CHECK (
        moderation_status IN ('NOT_SUBMITTED', 'PENDING', 'APPROVED', 'REJECTED', 'CHANGES_REQUESTED')
    );
