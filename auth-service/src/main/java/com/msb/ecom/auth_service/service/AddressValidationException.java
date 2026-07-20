package com.msb.ecom.auth_service.service;

public class AddressValidationException extends RuntimeException {

    private final String field;
    private final String fieldCode;

    public AddressValidationException(String field, String fieldCode) {
        this.field = field;
        this.fieldCode = fieldCode;
    }

    public String field() {
        return field;
    }

    public String fieldCode() {
        return fieldCode;
    }
}
