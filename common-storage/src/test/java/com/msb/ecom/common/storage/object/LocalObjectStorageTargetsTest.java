package com.msb.ecom.common.storage.object;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalObjectStorageTargetsTest {

    @Test
    void createsLocalDemoUploadTarget() {
        ObjectStorageUploadTarget target = LocalObjectStorageTargets.localDemoTarget(
                "listing-media-local",
                "listings/01L/media.png");

        assertEquals("listing-media-local", target.bucket());
        assertEquals("listings/01L/media.png", target.objectKey());
        assertEquals("LOCAL_DEMO", target.uploadMethod());
        assertEquals("local-demo://listing-media-local/listings/01L/media.png", target.uploadUrl());
    }
}
