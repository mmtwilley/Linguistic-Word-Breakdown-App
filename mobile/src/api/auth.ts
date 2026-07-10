/**
 * Auth endpoints (contract §1). Thin typed functions over the client —
 * no state here; AuthContext owns session state and token persistence.
 */
import { request, type ApiResult } from "./client";
import type { AuthResponse, LoginRequest, RefreshRequest, RegisterRequest } from "./types";

export function register(body: RegisterRequest): Promise<ApiResult<AuthResponse>> {
  return request<AuthResponse>("/api/auth/register", { method: "POST", body });
}

export function login(body: LoginRequest): Promise<ApiResult<AuthResponse>> {
  return request<AuthResponse>("/api/auth/login", { method: "POST", body });
}

/**
 * Rotates the token pair: on success the submitted refresh token is invalid
 * and the returned pair must be persisted immediately (see tokenStorage).
 */
export function refresh(body: RefreshRequest): Promise<ApiResult<AuthResponse>> {
  return request<AuthResponse>("/api/auth/refresh", { method: "POST", body });
}
