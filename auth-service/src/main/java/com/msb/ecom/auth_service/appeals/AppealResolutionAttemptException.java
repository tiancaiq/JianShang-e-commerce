package com.msb.ecom.auth_service.appeals;

/** Marks a failure after an authorized appeal resolution reached its owner-execution boundary. */
final class AppealResolutionAttemptException extends RuntimeException {

    AppealResolutionAttemptException(RuntimeException cause) {
        super(cause);
    }

    RuntimeException original() {
        return (RuntimeException) getCause();
    }
}
