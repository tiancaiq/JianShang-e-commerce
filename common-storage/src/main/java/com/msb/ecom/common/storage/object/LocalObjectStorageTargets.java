package com.msb.ecom.common.storage.object;

public final class LocalObjectStorageTargets {

    private LocalObjectStorageTargets() {
    }

    // Creates the common local-demo URI used when a service records upload metadata without external storage.
    public static ObjectStorageUploadTarget localDemoTarget(String bucket, String objectKey) {
        return new ObjectStorageUploadTarget(bucket, objectKey, "LOCAL_DEMO", "local-demo://" + bucket + "/" + objectKey);
    }
}
