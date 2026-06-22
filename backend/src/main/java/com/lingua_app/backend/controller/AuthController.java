package com.lingua_app.backend.controller;

import com.lingua_app.backend.dto.AuthResponse;
import com.lingua_app.backend.dto.LoginRequest;
import com.lingua_app.backend.dto.RefreshRequest;
import com.lingua_app.backend.dto.RegisterRequest;
import com.lingua_app.backend.service.IAuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// @RestController = @Controller + @ResponseBody on every method.
// All return values are serialised to JSON automatically.
@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final IAuthService authService;

    public AuthController(IAuthService authService) {
        this.authService = authService;
    }

    // T014 — POST /api/auth/register
    // @Valid triggers bean validation on RegisterRequest (@Email, @Size, @NotBlank).
    // Validation failures throw MethodArgumentNotValidException, caught by GlobalExceptionHandler → 400.
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(Map.of("message", "Account created successfully."));
    }

    // T020 — POST /api/auth/login
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    // T020 — POST /api/auth/refresh
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }
}
