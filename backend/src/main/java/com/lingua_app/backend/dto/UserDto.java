package com.lingua_app.backend.dto;

import java.time.Instant;
import java.util.UUID;

// UserDto is the safe representation of a User that can leave the service layer.
// passwordHash is intentionally excluded — it must never be serialised into a response
// or passed to untrusted code, even in hashed form.
//
// Instant is used instead of LocalDateTime because it is timezone-unambiguous.
// All timestamps are stored and transferred as UTC epoch millis.
public record UserDto(
    UUID id,
    String email,
    Instant createdAt,
    boolean active
) {}
