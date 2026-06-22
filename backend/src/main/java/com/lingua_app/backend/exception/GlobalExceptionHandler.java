package com.lingua_app.backend.exception;

import com.lingua_app.backend.dto.ErrorResponseDto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;
import java.util.stream.Collectors;

// @RestControllerAdvice applies globally across all @RestController classes.
// Any unhandled exception thrown inside a controller (or the service it calls)
// is intercepted here before Spring returns a response to the client.
// This is the single place where exception → HTTP response translation happens.
//
// Extending ResponseEntityExceptionHandler lets us override Spring MVC's own
// built-in handlers (e.g. for validation errors) so they also use our envelope format.
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // Handles all AppExceptions thrown by service layer code.
    // The response body is always {"error": {"code", "message", "retryable"}} —
    // stack traces and internal details never reach the client.
    @ExceptionHandler(AppException.class)
    public ResponseEntity<Map<String, ErrorResponseDto>> handleAppException(AppException ex) {
        var error = new ErrorResponseDto(ex.getErrorCode(), ex.getMessage(), ex.isRetryable());
        return ResponseEntity.status(ex.getStatus()).body(Map.of("error", error));
    }

    // Handles @Valid / @Validated failures on request DTOs (e.g. missing fields, bad format).
    // Collects all field errors into one semicolon-separated message so the client
    // sees everything wrong in a single response rather than fixing errors one at a time.
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        var error = new ErrorResponseDto("VALIDATION_ERROR", message, false);
        return ResponseEntity.badRequest().body(Map.of("error", error));
    }

    // Catch-all for any unexpected exception not handled above.
    // Returns a generic 500 with no internal detail — prevents accidental leakage
    // of database errors, NullPointerExceptions, or other implementation details.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, ErrorResponseDto>> handleGlobalException(Exception ex) {
        var error = new ErrorResponseDto("INTERNAL_ERROR", "An unexpected error occurred", false);
        return ResponseEntity.internalServerError().body(Map.of("error", error));
    }
}
