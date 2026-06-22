package com.lingua_app.backend.security;

import com.lingua_app.backend.AppProperties;
import com.lingua_app.backend.entity.User;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService implements IJWTService {

    private final SecretKey key;
    private final int expirySeconds;

    // The signing key is derived once at startup from the Base64-encoded secret in config.
    // Keys.hmacShaKeyFor() ensures the key is long enough for HS256 (min 256 bits).
    // Storing it as a SecretKey (not a raw String) prevents accidental logging.
    public JwtService(AppProperties appProperties) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(appProperties.getJwt().getSecret()));
        this.expirySeconds = appProperties.getJwt().getExpirySeconds();
    }

    @Override
    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        // JJWT 0.12+ API: method names changed from setXxx() to xxx() (e.g. setSubject → subject).
        // subject() stores userId as the JWT "sub" claim — the standard field for identifying the principal.
        // email is added as a custom claim so JwtAuthFilter can load UserDetails without a DB round-trip.
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirySeconds)))
                .signWith(key) // JJWT infers HS256 from the SecretKey type automatically
                .compact();
    }

    @Override
    public boolean validateToken(String token) {
        try {
            // parseSignedClaims() verifies both the signature and the expiry in one call.
            // Any tampered, expired, or malformed token throws JwtException.
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public UUID extractUserId(String token) {
        String subject = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
        return UUID.fromString(subject);
    }

    @Override
    public String extractEmail(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("email", String.class); // get() with a type param handles the cast safely
    }
}
