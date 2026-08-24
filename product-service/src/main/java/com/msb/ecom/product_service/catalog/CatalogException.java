package com.msb.ecom.product_service.catalog;

import org.springframework.http.HttpStatus;

public final class CatalogException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public CatalogException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }

    static CatalogException notFound(String resource) {
        String code = switch (resource) {
            case "Category attribute" -> "CATEGORY_ATTRIBUTE_NOT_FOUND";
            case "Attribute option" -> "CATEGORY_ATTRIBUTE_OPTION_NOT_FOUND";
            case "Seller guidance" -> "CATEGORY_GUIDANCE_NOT_FOUND";
            default -> "CATEGORY_NOT_FOUND";
        };
        return new CatalogException(HttpStatus.NOT_FOUND,
                code, resource + " was not found.");
    }

    static CatalogException conflict(String code, String message) {
        return new CatalogException(HttpStatus.CONFLICT, code, message);
    }

    static CatalogException invalid(String code, String message) {
        return new CatalogException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
