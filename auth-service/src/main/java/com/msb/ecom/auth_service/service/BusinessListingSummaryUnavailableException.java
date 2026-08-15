package com.msb.ecom.auth_service.service;

public class BusinessListingSummaryUnavailableException extends RuntimeException {
    public BusinessListingSummaryUnavailableException() {
        super("Business listing summary is temporarily unavailable.");
    }
}
