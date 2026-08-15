package com.msb.ecom.product_service.search.hybrid;

final class ListingHybridSearchFeatureDisabledException extends RuntimeException {
}

final class ListingHybridSearchIdentityMismatchException extends RuntimeException {
}

final class ListingHybridSearchUnavailableException extends RuntimeException {
    private final ListingHybridSearchFailureKind kind;

    ListingHybridSearchUnavailableException() {
        this(ListingHybridSearchFailureKind.INTERNAL_UNAVAILABLE);
    }

    ListingHybridSearchUnavailableException(ListingHybridSearchFailureKind kind) {
        this.kind = kind == null
                ? ListingHybridSearchFailureKind.INTERNAL_UNAVAILABLE
                : kind;
    }

    ListingHybridSearchFailureKind kind() {
        return kind;
    }
}

enum ListingHybridSearchFailureKind {
    TRANSPORT_IO("transport_io"),
    TRANSPORT_INTERRUPTED("transport_interrupted"),
    HTTP_4XX("http_4xx"),
    HTTP_5XX("http_5xx"),
    RESPONSE_OVERSIZE_OR_MISSING("response_oversize_or_missing"),
    RESPONSE_JSON_INVALID("response_json_invalid"),
    HITS_SHAPE_INVALID("hits_shape_invalid"),
    HIT_ID_INVALID("hit_id_invalid"),
    INTERNAL_UNAVAILABLE("internal_unavailable");

    private final String logValue;

    ListingHybridSearchFailureKind(String logValue) {
        this.logValue = logValue;
    }

    String logValue() {
        return logValue;
    }
}
