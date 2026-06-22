package com.lingua_app.backend.service.impl;

import com.lingua_app.backend.AppProperties;
import com.lingua_app.backend.dto.AuthResponse;
import com.lingua_app.backend.dto.LoginRequest;
import com.lingua_app.backend.dto.RefreshRequest;
import com.lingua_app.backend.dto.RegisterRequest;
import com.lingua_app.backend.entity.RefreshToken;
import com.lingua_app.backend.entity.User;
import com.lingua_app.backend.exception.AppException;
import com.lingua_app.backend.repository.RefreshTokenRepository;
import com.lingua_app.backend.repository.UserRepository;
import com.lingua_app.backend.security.JwtService;
import com.lingua_app.backend.service.IAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService implements IAuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties appProperties;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AppProperties appProperties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.appProperties = appProperties;
    }

    // --- T013: Registration ---

    @Override
    @Transactional
    public void register(RegisterRequest request) {
        // Normalize to lowercase so "User@Example.com" and "user@example.com" are the same account.
        String email = request.email().toLowerCase();

        if (userRepository.existsByEmail(email)) {
            // 409 Conflict — do not indicate whether the password was wrong or the email taken,
            // but for registration it is safe to reveal the email is taken (not a security leak).
            throw new AppException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS",
                    "An account with that email address already exists.");
        }

        User user = new User();
        user.setEmail(email);
        // BCrypt hashes the raw password — the plaintext is never stored or logged.
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setCreatedAt(Instant.now());
        user.setActive(true);
        userRepository.save(user);
        // Returns void — spec says 201 with a message body, no tokens at registration.
    }

    // --- T018: Login ---

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = request.email().toLowerCase();

        // Look up user first; if not found, throw the same error as a wrong password.
        // Using an identical error message prevents email enumeration attacks
        // (attacker cannot tell whether the email exists or the password was wrong).
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                        "Invalid email or password."));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                    "Invalid email or password.");
        }

        return issueTokenPair(user);
    }

    // --- T019: Token refresh ---

    @Override
    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        // Hash the opaque token the client presented so we can look it up in the DB.
        // The raw token is never stored — only its SHA-256 hash.
        String tokenHash = sha256(request.refreshToken());

        RefreshToken existing = refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(tokenHash)
                .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                        "Refresh token is invalid or expired. Please log in again."));

        // Guard against tokens that are in the DB but past their expiry date.
        if (existing.getExpiresAt().isBefore(Instant.now())) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                    "Refresh token is invalid or expired. Please log in again.");
        }

        // Revoke the old token before issuing new ones (refresh token rotation).
        // If an attacker replays the old token after rotation, the lookup above will
        // find revokedAt is set and reject the request.
        existing.setRevokedAt(Instant.now());
        refreshTokenRepository.save(existing);

        // FetchType.LAZY means getUser() triggers a DB query here — acceptable because
        // we are inside a @Transactional method so the session is still open.
        return issueTokenPair(existing.getUser());
    }

    // Shared helper: generate a JWT + store a new refresh token record, return AuthResponse.
    private AuthResponse issueTokenPair(User user) {
        String accessToken = jwtService.generateAccessToken(user);

        // Refresh token is a random UUID — opaque to the client, unpredictable, non-sequential.
        String rawRefreshToken = UUID.randomUUID().toString();
        long refreshExpirySeconds = (long) appProperties.getJwt().getRefreshExpiryDays() * 86_400;

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(sha256(rawRefreshToken));
        refreshToken.setIssuedAt(Instant.now());
        refreshToken.setExpiresAt(Instant.now().plusSeconds(refreshExpirySeconds));
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(accessToken, rawRefreshToken, appProperties.getJwt().getExpirySeconds());
    }

    // SHA-256 is guaranteed available in every JVM by the Java spec (no install needed).
    // HexFormat (Java 17+) converts the byte array to a lowercase hex string.
    private String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
