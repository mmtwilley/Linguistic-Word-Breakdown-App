package com.lingua_app.backend.dto;

        


public record AuthResponse(
        String accessToken,
        String refreshToken,
        int expiresIn
) {
}
