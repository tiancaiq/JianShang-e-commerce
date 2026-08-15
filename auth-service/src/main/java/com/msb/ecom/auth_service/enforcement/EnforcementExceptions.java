package com.msb.ecom.auth_service.enforcement;

public final class EnforcementExceptions {

    private EnforcementExceptions() {
    }

    public static class Validation extends RuntimeException {
        public Validation(String message) { super(message); }
    }

    public static class NotFound extends RuntimeException {
        public NotFound(String message) { super(message); }
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }
}
