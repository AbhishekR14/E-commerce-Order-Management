package com.ecommerce.oms.common.exception;

/**
 * Base class for all business errors. Carries an {@link ErrorCode}, which fixes the HTTP status and title;
 * the message becomes the ProblemDetail {@code detail}. Services throw the subclasses.
 */
public abstract class ApiException extends RuntimeException {

    private final ErrorCode code;

    protected ApiException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }

    /** Guards subclasses against being constructed with a code of the wrong status family. */
    protected static ErrorCode requireStatus(ErrorCode code, int expectedStatus) {
        if (code.status().value() != expectedStatus) {
            throw new IllegalArgumentException(code + " is not a " + expectedStatus + " error code");
        }
        return code;
    }
}
