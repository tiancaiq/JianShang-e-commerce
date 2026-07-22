package com.msb.ecom.product_service.reports;

final class ListingReportFeatureDisabledException extends RuntimeException {
}

final class ListingReportNotFoundException extends RuntimeException {
}

final class ListingReportInvalidRequestException extends RuntimeException {
}

final class ListingReportPayloadTooLargeException extends RuntimeException {
}

final class ListingReportIdempotencyConflictException extends RuntimeException {
}

final class ListingReportUnavailableException extends RuntimeException {
}

final class ListingReportRateLimitException extends RuntimeException {
    private final long retryAfterSeconds;

    ListingReportRateLimitException(long retryAfterSeconds) {
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
