package com.lingua_app.backend.service;

import com.lingua_app.backend.dto.AuthResponse;
import com.lingua_app.backend.dto.LoginRequest;
import com.lingua_app.backend.dto.RefreshRequest;
import com.lingua_app.backend.dto.RegisterRequest;

public interface IAuthService {

    // Creates a new account. Throws AppException(409) if the email is already taken.
    void register(RegisterRequest request);

    // Verifies credentials and returns a new access + refresh token pair.
    AuthResponse login(LoginRequest request);

    // Rotates the refresh token: revokes the presented token and issues a new pair.
    AuthResponse refresh(RefreshRequest request);
}
