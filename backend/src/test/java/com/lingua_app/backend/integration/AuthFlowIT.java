package com.lingua_app.backend.integration;

import com.lingua_app.backend.dto.AuthResponse;
import com.lingua_app.backend.dto.LoginRequest;
import com.lingua_app.backend.dto.RefreshRequest;
import com.lingua_app.backend.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

// Full end-to-end auth flow against a real PostgreSQL database.
// Redis is not involved in the auth flow — the health indicator is disabled
// so /actuator/health returns 200 (UP) rather than 503 (DOWN) during tests.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "JWT_SECRET=c3VwZXJzZWNyZXRrZXkxMjM0NTY3ODkwYWJjZGVmZ2hpams=",
                "JWT_EXPIRY_SECONDS=900",
                "REFRESH_TOKEN_EXPIRY_DAYS=1",
                "CLAUDE_API_KEY=test-key",
                "DEEPL_API_KEY=test-key",
                "VERDICT_API_KEY=test-key",
                "management.health.redis.enabled=false"
        }
)
@Testcontainers
class AuthFlowIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("lingua_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void overrideDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", postgres::getJdbcUrl);
        registry.add("DATABASE_USERNAME", postgres::getUsername);
        registry.add("DATABASE_PASSWORD", postgres::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void registerLoginAndAccessProtectedEndpoint() {
        // Register a new user
        ResponseEntity<Void> registerResponse = restTemplate.postForEntity(
                "/api/auth/register",
                new RegisterRequest("flowtest@example.com", "Password123!"),
                Void.class);
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Login and receive tokens
        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                "/api/auth/login",
                new LoginRequest("flowtest@example.com", "Password123!"),
                AuthResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).isNotNull();
        String accessToken = loginResponse.getBody().accessToken();
        assertThat(accessToken).isNotBlank();

        // Access a protected endpoint using the JWT — unauthenticated would get 401
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> protectedResponse = restTemplate.exchange(
                "/actuator/health",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        assertThat(protectedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void refreshTokenRotation_oldTokenRejectedAfterRefresh() {
        // Register and login
        restTemplate.postForEntity(
                "/api/auth/register",
                new RegisterRequest("rotatetest@example.com", "Password123!"),
                Void.class);
        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                "/api/auth/login",
                new LoginRequest("rotatetest@example.com", "Password123!"),
                AuthResponse.class);
        String oldRefreshToken = loginResponse.getBody().refreshToken();

        // Rotate: exchange old refresh token for a new token pair
        ResponseEntity<AuthResponse> refreshResponse = restTemplate.postForEntity(
                "/api/auth/refresh",
                new RefreshRequest(oldRefreshToken),
                AuthResponse.class);
        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshResponse.getBody().refreshToken()).isNotEqualTo(oldRefreshToken);

        // Replaying the old (now revoked) refresh token must be rejected
        ResponseEntity<String> replayResponse = restTemplate.postForEntity(
                "/api/auth/refresh",
                new RefreshRequest(oldRefreshToken),
                String.class);
        assertThat(replayResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
