# Data Model: Backend Authentication & Text Analysis API

**Feature**: 001-backend-auth-analyze
**Date**: 2026-06-21
**Schema source**: `backend/src/main/resources/db/migration/V1__initial_schema.sql`

---

## Entity: Users

**Table**: `users`
**JPA Entity**: `com.lingua_app.backend.entity.Users`

| Column | PostgreSQL Type | Constraints | Notes |
|--------|----------------|-------------|-------|
| `id` | UUID | PK, `DEFAULT gen_random_uuid()` | `@GeneratedValue(strategy = UUID)` |
| `email` | CITEXT | NOT NULL, UNIQUE | Case-insensitive. Normalized to lowercase in service layer before persistence. |
| `password_hash` | VARCHAR(255) | NOT NULL | BCrypt output. Never returned to client. |
| `created_at` | TIMESTAMP | NOT NULL, `DEFAULT NOW()` | Set by service; `@Column(updatable=false)`. |
| `is_active` | BOOLEAN | NOT NULL, `DEFAULT TRUE` | Soft-disable without deletion. |

**Validation** (enforced in `RegisterRequest` DTO, not in entity):
- `email`: valid RFC 5322 format, max 254 chars
- `password`: 8–128 chars (only checked at registration)

**JPA Mapping**:
```java
@Entity
@Table(name = "users")
public class Users {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "is_active", nullable = false)
    private boolean active;
}
```

---

## Entity: RefreshToken

**Table**: `refresh_tokens`
**JPA Entity**: `com.lingua_app.backend.entity.RefreshToken`

| Column | PostgreSQL Type | Constraints | Notes |
|--------|----------------|-------------|-------|
| `id` | UUID | PK, `DEFAULT gen_random_uuid()` | |
| `user_id` | UUID | FK → `users.id`, ON DELETE CASCADE | Loaded lazily. |
| `token_hash` | VARCHAR(255) | NOT NULL, UNIQUE | SHA-256 of the opaque random UUID sent to the client. Raw token is returned once, never stored. |
| `issued_at` | TIMESTAMP | NOT NULL, `DEFAULT NOW()` | `@Column(updatable=false)` |
| `expires_at` | TIMESTAMP | NOT NULL | `NOW() + REFRESH_TOKEN_EXPIRY_DAYS` at issue time. |
| `revoked_at` | TIMESTAMP | nullable | Set on rotation (use) or logout. Active tokens: `revoked_at IS NULL AND expires_at > NOW()`. |

**Token lifecycle**:
```
ACTIVE   (revoked_at IS NULL, expires_at > NOW())
  ├─→ USED     (revoked_at set; new token issued atomically — rotation)
  └─→ EXPIRED  (expires_at < NOW())
```

**Rotation**: On successful refresh, the current token's `revoked_at` is set and a new token is issued in the same transaction. Presenting a revoked (already-used) token returns 401 — the client must re-login.

**Indexes** (already in V1 migration):
- `idx_refresh_tokens_user_id` — fast lookup by user
- `idx_refresh_tokens_active` — partial index on `token_hash WHERE revoked_at IS NULL` (active token validation)
- `idx_refresh_tokens_expires_at` — supports future cleanup job (Phase 5+)

**JPA Mapping**:
```java
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
```

---

## Value Objects (not persisted)

### WordCard

Internal domain object produced by the analysis pipeline. Mapped to `WordCardDto` before serialization.

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `surface` | String | Yes | Token as it appears in input text |
| `lemma` | String | Yes | Dictionary base form |
| `pos` | String | Yes | Part of speech tag (NOUN, VERB, ADJ, etc.) |
| `gloss` | String | Yes | English meaning |
| `romanization` | String | No | Null for Latin-script tokens; populated by `RomanizationStep` |
| `ipa` | String | No | Not produced in Phase 1 |

### AnalysisContext

Mutable context object passed between pipeline steps. Not serialized.

| Field | Type | Notes |
|-------|------|-------|
| `text` | String | Validated, trimmed input text |
| `detectedLanguage` | String | ISO 639-3 code: `kor` / `jpn` / `cmn` / `lat` / `und` |
| `translation` | String | Set by `TranslationStep`; null until that step completes |
| `words` | List\<WordCard\> | Populated progressively by `DictionaryStep` and `ClaudeStep` |
| `partialErrors` | Map\<String, String\> | Per-word error messages when a step partially fails |

---

## No BaseEntity

`Users` and `RefreshToken` share no fields worth abstracting:
- `Users.created_at` is a business field (account creation timestamp), not a generic audit column.
- `RefreshToken.issued_at` / `expires_at` are token lifecycle fields with different semantics.
- Both entities have `id` (UUID PK), but annotating `@Id` directly on each is clearer than an abstract superclass for two entities.

---

## Database Notes

- `CITEXT` requires `CREATE EXTENSION IF NOT EXISTS citext;` (present in V1 migration line 1).
- ⚠️ **V1 migration bug**: Line 5 uses `ITEXT` — must be corrected to `CITEXT` before first startup.
- Analysis results are **not persisted** in Phase 1; `AnalysisContext` and `WordCard` are transient.
- No `updated_at` column on either table — `Users` is append-and-toggle only; `RefreshToken` is set-once then revoke.
