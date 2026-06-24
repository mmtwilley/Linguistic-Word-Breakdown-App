package com.lingua_app.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingua_app.backend.dto.AuthResponse;
import com.lingua_app.backend.dto.LoginRequest;
import com.lingua_app.backend.dto.RefreshRequest;
import com.lingua_app.backend.dto.RegisterRequest;
import com.lingua_app.backend.exception.AppException;
import com.lingua_app.backend.security.JwtService;
import com.lingua_app.backend.security.UserDetailsServiceImpl;
import com.lingua_app.backend.service.IAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// @WebMvcTest loads only the web layer (controllers, filters, GlobalExceptionHandler).
// No database, no full Spring context — fast and isolated.
// We mock IAuthService so tests never touch real business logic or the DB.
@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // The service under test — mocked so we control what it returns or throws.
    @MockitoBean
    private IAuthService authService;

    // SecurityConfig wires in JwtAuthFilter, which depends on these two beans.
    // They must be mocked or @WebMvcTest will fail to build the application context.
    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    // --- T015 test cases ---

    @Test
    void register_newEmail_returns201WithMessage() throws Exception {
        // authService.register() is void and does nothing by default when mocked.
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("user@example.com", "password123"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Account created successfully."));
    }

    @Test
    void register_duplicateEmail_returns409EmailAlreadyExists() throws Exception {
        doThrow(new AppException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS",
                "An account with that email address already exists."))
                .when(authService).register(any());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("user@example.com", "password123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.error.retryable").value(false));
    }

    @Test
    void register_invalidEmailFormat_returns400ValidationError() throws Exception {
        // "not-an-email" fails @Email — Spring triggers MethodArgumentNotValidException
        // before the method body runs, so authService is never called.
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("not-an-email", "password123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void register_passwordTooShort_returns400ValidationError() throws Exception {
        // "short" is 5 chars — fails @Size(min = 8).
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("user@example.com", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // --- T021 test cases ---

    @Test
    void login_validCredentials_returns200WithAccessToken() throws Exception {
        when(authService.login(any())).thenReturn(new AuthResponse("access-token", "refresh-token", 900));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("user@example.com", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void login_wrongPassword_returns401InvalidCredentials() throws Exception {
        when(authService.login(any())).thenThrow(
                new AppException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password."));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("user@example.com", "wrongpassword"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.retryable").value(false));
    }

    @Test
    void refresh_expiredToken_returns401InvalidRefreshToken() throws Exception {
        when(authService.refresh(any())).thenThrow(
                new AppException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                        "Refresh token is invalid or expired. Please log in again."));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RefreshRequest("expired-refresh-token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"))
                .andExpect(jsonPath("$.error.retryable").value(false));
    }

    @Test
    void refresh_revokedToken_returns401InvalidRefreshToken() throws Exception {
        when(authService.refresh(any())).thenThrow(
                new AppException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                        "Refresh token is invalid or expired. Please log in again."));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RefreshRequest("revoked-refresh-token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"))
                .andExpect(jsonPath("$.error.retryable").value(false));
    }
}
