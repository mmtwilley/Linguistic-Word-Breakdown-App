package com.lingua_app.backend.exception;

import org.springframework.http.HttpStatus;

// AppException is the single custom exception type used throughout the service layer.
// Throwing it instead of generic exceptions lets GlobalExceptionHandler map it
// directly to the correct HTTP status and structured error response without any
// instanceof checks or catch-all fallbacks.
//
// It extends RuntimeException (unchecked) so callers don't need try-catch blocks —
// Spring's exception handler intercepts it automatically.
public class AppException extends RuntimeException {

    private final HttpStatus status;    // HTTP status code to return (e.g. 401, 404, 409)
    private final String errorCode;     // Machine-readable code for the client (e.g. "USER_NOT_FOUND")
    private final boolean retryable;    // Whether the client should retry the same request

    // Convenience constructor — most errors are not retryable
    public AppException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, false);
    }

    public AppException(HttpStatus status, String errorCode, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public HttpStatus getStatus()    { return status; }
    public String getErrorCode()     { return errorCode; }
    public boolean isRetryable()     { return retryable; }
}
