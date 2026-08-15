package com.msb.ecom.product_service.search.embedding;

public class ListingEmbeddingRequestBackfillException extends RuntimeException {
    private final Kind kind;
    private final String code;

    public ListingEmbeddingRequestBackfillException(Kind kind, String code) {
        super(code);
        this.kind = kind;
        this.code = code;
    }

    public ListingEmbeddingRequestBackfillException(
            Kind kind,
            String code,
            Throwable cause) {
        super(code, cause);
        this.kind = kind;
        this.code = code;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public enum Kind {
        DISABLED,
        NOT_FOUND,
        CONFLICT,
        UNAVAILABLE
    }
}
