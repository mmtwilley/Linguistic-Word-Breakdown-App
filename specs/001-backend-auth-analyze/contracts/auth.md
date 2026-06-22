# API Contract: Authentication Endpoints

**Base path**: `/api/auth`
**Content-Type**: `application/json`
**Authentication**: None required on these endpoints.
**Error envelope**: All errors use `{"error":{"code":"...","message":"...","retryable":true|false}}`.

---

## POST /api/auth/register

Register a new user account.

### Request body

```json
{
  "email": "user@example.com",
  "password": "minimum8chars"
}
```

| Field | Type | Constraints |
|-------|------|-------------|
| `email` | string | Valid email format, max 254 chars |
| `password` | string | 8–128 chars |

Email is normalized to lowercase before uniqueness check and storage (FR-015).

### Responses

**201 Created**
```json
{
  "message": "Account created successfully."
}
```

**400 Bad Request** — validation failure (FR-003)
```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "email: must be a valid email address",
    "retryable": false
  }
}
```

**409 Conflict** — email already registered (FR-002)
```json
{
  "error": {
    "code": "EMAIL_ALREADY_EXISTS",
    "message": "An account with that email address already exists.",
    "retryable": false
  }
}
```

---

## POST /api/auth/login

Authenticate with credentials. Returns a short-lived access token and a long-lived refresh token (FR-004).

### Request body

```json
{
  "email": "user@example.com",
  "password": "minimum8chars"
}
```

### Responses

**200 OK**
```json
{
  "accessToken": "<signed-JWT>",
  "refreshToken": "<opaque-UUID>",
  "expiresIn": 900
}
```

| Field | Notes |
|-------|-------|
| `accessToken` | Signed JWT. Include as `Authorization: Bearer <token>` on protected requests. |
| `refreshToken` | Opaque UUID. Present to `/api/auth/refresh` to obtain new tokens. |
| `expiresIn` | Access token lifetime in seconds (`JWT_EXPIRY_SECONDS`, default 900). |

**401 Unauthorized** — wrong email or password (FR-006)
```json
{
  "error": {
    "code": "INVALID_CREDENTIALS",
    "message": "Invalid email or password.",
    "retryable": false
  }
}
```

> The error message does not indicate which field was wrong (FR-006: no enumeration of valid emails).

---

## POST /api/auth/refresh

Exchange a valid refresh token for a new access + refresh token pair. The presented token is revoked immediately (rotation) (FR-005).

### Request body

```json
{
  "refreshToken": "<opaque-UUID>"
}
```

### Responses

**200 OK** — same shape as login; old refresh token is revoked atomically.
```json
{
  "accessToken": "<new-signed-JWT>",
  "refreshToken": "<new-opaque-UUID>",
  "expiresIn": 900
}
```

**401 Unauthorized** — expired, revoked, or unknown token (FR-007 / SC-004)
```json
{
  "error": {
    "code": "INVALID_REFRESH_TOKEN",
    "message": "Refresh token is invalid or expired. Please log in again.",
    "retryable": false
  }
}
```
