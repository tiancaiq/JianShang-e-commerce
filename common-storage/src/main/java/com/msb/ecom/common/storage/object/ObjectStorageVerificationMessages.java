package com.msb.ecom.common.storage.object;

public record ObjectStorageVerificationMessages(
        String sizeMismatch,
        String contentTypeMismatch,
        String notFound,
        String unavailable
) {
}
