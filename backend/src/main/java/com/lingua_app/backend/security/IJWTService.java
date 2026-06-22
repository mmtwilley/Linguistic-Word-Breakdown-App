package com.lingua_app.backend.security;

import com.lingua_app.backend.entity.User;
import java.util.UUID;

// Defining JwtService behind an interface makes it easy to swap implementations
// (e.g. switch signing algorithm, add key rotation) and to mock it cleanly in unit tests
// without starting a real Spring context.
public interface IJWTService {

    // Signs a short-lived access token containing the user's id (subject) and email (claim).
    String generateAccessToken(User user);

    // Returns true if the token signature is valid and it has not expired.
    // Swallows JwtException internally — callers receive a simple boolean.
    boolean validateToken(String token);

    // Extracts the subject claim (userId stored as a string) and parses it back to UUID.
    UUID extractUserId(String token);

    // Extracts the custom "email" claim embedded at token generation time.
    // Used by JwtAuthFilter to load UserDetails without a separate userId → email query.
    String extractEmail(String token);
}
