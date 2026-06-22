package com.lingua_app.backend.dto;

// A Java record is an immutable data carrier — the compiler auto-generates
// the constructor, getters, equals(), hashCode(), and toString().
// Records are ideal for DTOs that are created once and only read.

// This record shapes every error response the API returns.
// Wrapping it in {"error": {...}} in GlobalExceptionHandler means clients
// always get a consistent envelope — they never see a raw stack trace or
// Spring's default HTML error page.
//   code      — machine-readable identifier (e.g. "VALIDATION_ERROR", "UNAUTHORIZED")
//   message   — human-readable explanation safe to display
//   retryable — tells the client whether retrying the same request might succeed
public record ErrorResponseDto(String code, String message, boolean retryable){}
