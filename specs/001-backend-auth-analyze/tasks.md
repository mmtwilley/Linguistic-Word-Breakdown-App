# Tasks: Backend Authentication & Text Analysis API

**Input**: Design documents from `/specs/001-backend-auth-analyze/`
**Prerequisites**: plan.md ✅ research.md ✅ data-model.md ✅ contracts/ ✅ quickstart.md ✅

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story?] Description — file path`

- **[P]**: Can run in parallel (different files, no incomplete dependencies)
- **[Story]**: User story this task belongs to (US1, US2, US3)

---

## Phase 1: Setup

**Purpose**: Complete the two entity stubs that every subsequent phase depends on.

- [x] T001 [P] Complete `Users.java` JPA entity with all fields per data-model.md (`id` UUID PK, `email` CITEXT, `passwordHash`, `createdAt` non-updatable, `active` boolean) — `backend/src/main/java/com/lingua_app/backend/entity/Users.java`
- [x] T002 [P] Create `RefreshToken.java` JPA entity with `id`, `@ManyToOne(lazy) Users user`, `tokenHash`, `issuedAt` non-updatable, `expiresAt`, nullable `revokedAt` per data-model.md — `backend/src/main/java/com/lingua_app/backend/entity/RefreshToken.java`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Error handling, repositories, JWT, and security filter chain. All user story work is blocked until this phase is complete.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [x] T003 [P] Create `AppException.java` — runtime exception holding `HttpStatus status` and `String errorCode` fields; used by `GlobalExceptionHandler` to emit the structured envelope — `backend/src/main/java/com/lingua_app/backend/exception/AppException.java`
- [x] T004 [P] Create `GlobalExceptionHandler.java` with `@RestControllerAdvice`; handle `AppException` → structured envelope `{"error":{"code","message","retryable"}}`; handle `MethodArgumentNotValidException` → `VALIDATION_ERROR` envelope (400); handle uncaught `Exception` → `INTERNAL_ERROR` (500, no stack trace) — `backend/src/main/java/com/lingua_app/backend/exception/GlobalExceptionHandler.java`
- [x] T005 [P] Create `UserRepository.java` extending `JpaRepository<Users, UUID>` with `Optional<Users> findByEmail(String email)` and `boolean existsByEmail(String email)` — `backend/src/main/java/com/lingua_app/backend/repository/UserRepository.java`
- [x] T006 [P] Create `RefreshTokenRepository.java` extending `JpaRepository<RefreshToken, UUID>` with `Optional<RefreshToken> findByTokenHashAndRevokedAtIsNull(String tokenHash)` and `@Modifying @Query` to set `revokedAt = NOW()` for all active tokens belonging to a user — `backend/src/main/java/com/lingua_app/backend/repository/RefreshTokenRepository.java`
- [x] T007 [P] Create `JwtService.java`: `generateAccessToken(Users)` signs HS256 JWT with `userId` + `email` claims and `JWT_EXPIRY_SECONDS` TTL using `AppProperties.jwt`; `validateToken(String)` returns boolean; `extractUserId(String)` returns UUID — `backend/src/main/java/com/lingua_app/backend/security/JwtService.java`
- [x] T008 [P] Create `UserDetailsServiceImpl.java` implementing `UserDetailsService`; `loadUserByUsername(email)` calls `UserRepository.findByEmail()`, throws `UsernameNotFoundException` if absent — `backend/src/main/java/com/lingua_app/backend/security/UserDetailsServiceImpl.java`
- [x] T009 Create `JwtAuthFilter.java` extending `OncePerRequestFilter`: extract `Authorization: Bearer` header, call `JwtService.validateToken()`, set `UsernamePasswordAuthenticationToken` in `SecurityContextHolder` on success; no-op (not reject) on missing/invalid token (Spring Security handles 401 downstream) — `backend/src/main/java/com/lingua_app/backend/security/JwtAuthFilter.java`
- [x] T010 Create `SecurityConfig.java` with `@EnableWebSecurity`: stateless `SessionCreationPolicy.STATELESS`; permit `POST /api/auth/**`; authenticate all other requests; add `JwtAuthFilter` before `UsernamePasswordAuthenticationFilter`; expose `PasswordEncoder` bean (BCrypt, strength 12); expose `AuthenticationManager` bean — `backend/src/main/java/com/lingua_app/backend/config/SecurityConfig.java`

**Checkpoint**: Build passes (`./mvnw compile`). Entities map to DB schema. Security filter chain rejects unauthenticated requests to `/api/analyze`.

---

## Phase 3: User Story 1 — Account Registration (Priority: P1) 🎯 MVP

**Goal**: New users can register with a unique email and password and receive confirmation.

**Independent Test**: `POST /api/auth/register` with a new email returns 201; same email a second time returns 409; invalid email format returns 400; password under 8 chars returns 400. *(See quickstart.md §4 for curl commands.)*

- [x] T011 [P] [US1] Create `RegisterRequest.java` record with `@Email @NotBlank String email` and `@Size(min=8,max=128) @NotBlank String password` — `backend/src/main/java/com/lingua_app/backend/dto/RegisterRequest.java`
- [x] T012 [P] [US1] Create `AuthResponse.java` record with `String accessToken`, `String refreshToken`, `int expiresIn` — `backend/src/main/java/com/lingua_app/backend/dto/AuthResponse.java`
- [x] T013 [US1] Create `AuthService.java`; implement `register(RegisterRequest)`: normalize email to lowercase, call `UserRepository.existsByEmail()` → throw `AppException(409, EMAIL_ALREADY_EXISTS)` if taken, BCrypt-encode password, persist `Users` with `createdAt = Instant.now()`, return `void` (201, no token at registration per spec) — `backend/src/main/java/com/lingua_app/backend/service/AuthService.java`
- [x] T014 [US1] Create `AuthController.java` with `POST /api/auth/register` calling `AuthService.register()`; return `ResponseEntity<Map<String,String>>` with `{"message":"Account created successfully."}` and status 201 — `backend/src/main/java/com/lingua_app/backend/controller/AuthController.java`
- [x] T015 [US1] Write `@WebMvcTest(AuthController.class)` covering: new email → 201 + message; duplicate email → 409 `EMAIL_ALREADY_EXISTS`; invalid email format → 400 `VALIDATION_ERROR`; password < 8 chars → 400 `VALIDATION_ERROR` — `backend/src/test/java/com/lingua_app/backend/controller/AuthControllerTest.java`

**Checkpoint**: `POST /api/auth/register` is fully functional and independently testable.

---

## Phase 4: User Story 2 — Login and Session Management (Priority: P1)

**Goal**: Registered users can log in to receive tokens, refresh them without re-entering credentials, and have revoked/expired tokens rejected.

**Independent Test**: Login with valid credentials returns 200 with `accessToken` + `refreshToken`; present `refreshToken` to `/refresh` returns new token pair and old token is rejected; wrong password returns 401; revoked token returns 401.

- [x] T016 [P] [US2] Create `LoginRequest.java` record with `@NotBlank String email` and `@NotBlank String password` — `backend/src/main/java/com/lingua_app/backend/dto/LoginRequest.java`
- [x] T017 [P] [US2] Create `RefreshRequest.java` record with `@NotBlank String refreshToken` — `backend/src/main/java/com/lingua_app/backend/dto/RefreshRequest.java`
- [x] T018 [US2] Add `login(LoginRequest)` to `AuthService.java`: normalize email, load user via `UserDetailsServiceImpl`, verify BCrypt password → throw `AppException(401, INVALID_CREDENTIALS)` on mismatch, call `JwtService.generateAccessToken()`, generate opaque refresh UUID, store SHA-256 hash in `refresh_tokens` with `expiresAt = now + REFRESH_TOKEN_EXPIRY_DAYS`, return `AuthResponse` — `backend/src/main/java/com/lingua_app/backend/service/AuthService.java`
- [x] T019 [US2] Add `refresh(RefreshRequest)` to `AuthService.java`: SHA-256 hash the presented token, call `RefreshTokenRepository.findByTokenHashAndRevokedAtIsNull()` → throw `AppException(401, INVALID_REFRESH_TOKEN)` if absent or `expiresAt` past, set `revokedAt = Instant.now()` on current record, issue new refresh token and new JWT atomically, return `AuthResponse` — `backend/src/main/java/com/lingua_app/backend/service/AuthService.java`
- [x] T020 [US2] Add `POST /api/auth/login` and `POST /api/auth/refresh` to `AuthController.java` calling `AuthService.login()` and `AuthService.refresh()` respectively; both return 200 `AuthResponse` — `backend/src/main/java/com/lingua_app/backend/controller/AuthController.java`
- [x] T021 [US2] Add login/refresh test cases to `AuthControllerTest.java`: valid credentials → 200 + `accessToken`; wrong password → 401 `INVALID_CREDENTIALS`; expired refresh token → 401 `INVALID_REFRESH_TOKEN`; revoked token → 401 `INVALID_REFRESH_TOKEN` — `backend/src/test/java/com/lingua_app/backend/controller/AuthControllerTest.java`
- [x] T022 [US2] Write `AuthFlowIT.java` Testcontainers integration test (`@SpringBootTest` + PostgreSQL container): register → login → call a protected stub endpoint with `accessToken` → confirm 200; present old `refreshToken` after rotation → confirm 401 — `backend/src/test/java/com/lingua_app/backend/integration/AuthFlowIT.java`

**Checkpoint**: Full auth flow works end-to-end against a real database. `./mvnw test -Dtest=AuthFlowIT` passes.

---

## Phase 5: User Story 3 — Text Analysis for Authenticated Users (Priority: P2)

**Goal**: Authenticated users submit text and receive language detection, full English translation, and per-token WordCards with romanization for non-Latin scripts.

**Independent Test**: `POST /api/analyze` with `Authorization: Bearer <token>` and `{"text":"오늘 날씨가 정말 좋네요"}` returns 200 with `language=kor`, non-null `translation`, and a `words` array where each entry has non-null `surface`, `lemma`, `pos`, `gloss`, and `romanization`.

### DTOs and Domain Objects

- [x] T023 [P] [US3] Create `AnalysisRequest.java` record with `@NotBlank @Size(max=500) String text` and `String language` (optional ISO 639-3 hint from client) — `backend/src/main/java/com/lingua_app/backend/dto/AnalysisRequest.java`
- [x] T024 [P] [US3] Create `WordCardDto.java` record with `String surface, lemma, pos, gloss, romanization, ipa` (all nullable except surface) — `backend/src/main/java/com/lingua_app/backend/dto/WordCardDto.java`
- [x] T025 [P] [US3] Create `AnalysisResponse.java` record with `String language`, `String translation`, `List<WordCardDto> words` — `backend/src/main/java/com/lingua_app/backend/dto/AnalysisResponse.java`
- [x] T026 [P] [US3] Create `WordCard.java` mutable domain object with `surface, lemma, pos, gloss, romanization, ipa, error` fields — `backend/src/main/java/com/lingua_app/backend/analysis/pipeline/WordCard.java`
- [x] T027 [P] [US3] Create `AnalysisContext.java` with `String text`, `String detectedLanguage`, `String translation`, `List<WordCard> words`, `Map<String,String> partialErrors` — `backend/src/main/java/com/lingua_app/backend/analysis/pipeline/AnalysisContext.java`

### Config

- [x] T028 [P] [US3] Create `RateLimitConfig.java`: build a Bucket4j `ProxyManager` backed by `RedisTemplate`; `getBucket(String userId)` returns a bucket with capacity `app.rate-limit.rpm` tokens refilling per 60 seconds — `backend/src/main/java/com/lingua_app/backend/config/RateLimitConfig.java`
- [x] T029 [P] [US3] Create `ResilienceConfig.java`: define `CircuitBreakerConfig` bean (5 failures in 60s → open; 30s half-open wait) and `RetryConfig` bean (3 attempts, exponential backoff from 500ms, on `IOException` and HTTP 5xx) — `backend/src/main/java/com/lingua_app/backend/config/ResilienceConfig.java`

### Pipeline Steps

- [x] T030 [P] [US3] Create `DetectionStep.java`: implement Unicode block range counting algorithm from `research.md Decision 3` (kor/jpn/cmn/lat/und); if client-supplied `language` hint is valid and not `und`, accept it; otherwise run detection; throw `AppException(400, LANGUAGE_UNDETECTABLE)` if result is `und` — `backend/src/main/java/com/lingua_app/backend/analysis/step/DetectionStep.java`
- [x] T031 [US3] Create `TranslationStep.java`: call DeepL Java SDK `translator.translateText()`; on `QuotaExceededException`, 5xx, or timeout fall back to calling Claude with a simple translation prompt; annotate the DeepL call with `@Retry(name="deepl")` and `@CircuitBreaker(name="deepl")`; set `AnalysisContext.translation` — `backend/src/main/java/com/lingua_app/backend/analysis/step/TranslationStep.java`
- [x] T041 [US3] Add DeepL quota Redis counter to `TranslationStep.java`: increment a Redis monthly counter (key: `deepl:quota:YYYY-MM`) on each successful DeepL character translation; when counter exceeds 90% of 500 000, skip DeepL and go directly to Claude fallback (constitution Principle I — MUST be active before Phase 5 deployment) — `backend/src/main/java/com/lingua_app/backend/analysis/step/TranslationStep.java`
- [x] T032 [US3] Create `DictionaryStep.java`: dispatch by `AnalysisContext.detectedLanguage` — Japanese: `LuceneJapaneseTokenizer` (Kuromoji) to extract surface, base form, POS, katakana reading; Chinese: parse CC-CEDICT file bundled at `src/main/resources/dict/cedict_ts.u8` (loaded into `Map<String,CedictEntry>` at startup via `@PostConstruct`); Korean: Krdict REST API using `AppProperties.api.verdictKey`; English: Free Dictionary API (`api.dictionaryapi.dev`). Populate `AnalysisContext.words` for all resolved tokens; leave unresolved tokens absent for `ClaudeStep` — `backend/src/main/java/com/lingua_app/backend/analysis/step/DictionaryStep.java`
- [x] T033 [US3] Create `ClaudeStep.java`: build `analyze_words` tool schema from Anthropic Java SDK; include `particles`/`endings` fields only for `kor`/`jpn`; exclude `romanization` (generated locally); pass `translation`, `knownWords`, and unresolved surfaces in prompt; parse tool-use response into `WordCard` objects; annotate with `@Retry(name="claude")` and `@CircuitBreaker(name="claude")`; on circuit open, set `partialErrors` on `AnalysisContext` rather than throwing — `backend/src/main/java/com/lingua_app/backend/analysis/step/ClaudeStep.java`
- [x] T034 [US3] Create `RomanizationStep.java`: Korean → `ICU4J Transliterator.getInstance("Hangul-Latin/BGN")`; Japanese → convert Kuromoji katakana reading via ICU4J `Katakana-Latin`; Chinese → Pinyin4j `PinyinHelper.toHanyuPinyinStringArray()`; Latin (English) → no-op. Set `WordCard.romanization` in-place on each word in `AnalysisContext.words` — `backend/src/main/java/com/lingua_app/backend/analysis/step/RomanizationStep.java`

### Pipeline, Service, Controller

- [x] T035 [US3] Create `AnalysisPipeline.java`: inject all five steps; run Detection → Translation → Dictionary → Claude → Romanization in order; wrap each step call in try-catch — step failure populates `AnalysisContext.partialErrors` and continues (no bare propagation); return completed `AnalysisContext` — `backend/src/main/java/com/lingua_app/backend/analysis/pipeline/AnalysisPipeline.java`
- [x] T036 [US3] Create `AnalysisService.java`: trim `request.text()`; throw `AppException(400, INVALID_INPUT)` if blank or > 500 chars; consume one token from `RateLimitConfig.getBucket(userId)` → throw `AppException(429, RATE_LIMIT_EXCEEDED)` if empty; dispatch to `AnalysisPipeline.run()`; map `AnalysisContext` → `AnalysisResponse` — for each entry in `AnalysisContext.partialErrors`, set the corresponding `WordCardDto.error` field to the error code with `surface` populated and `lemma`/`pos`/`gloss`/`romanization` all null — `backend/src/main/java/com/lingua_app/backend/service/AnalysisService.java`
- [x] T037 [US3] Create `AnalysisController.java` with `POST /api/analyze` (authenticated); extract authenticated `userId` from `SecurityContextHolder`; call `AnalysisService.analyze(userId, request)`; return 200 `AnalysisResponse` — `backend/src/main/java/com/lingua_app/backend/controller/AnalysisController.java`

### Tests

- [x] T038 [P] [US3] Write `@WebMvcTest(AnalysisController.class)` covering: missing token → 401; empty text → 400 `INVALID_INPUT`; text > 500 chars → 400 `INVALID_INPUT`; rate limited (mock bucket exhausted) → 429 `RATE_LIMIT_EXCEEDED`; valid Korean text (mock pipeline) → 200 with `language=kor` and non-empty `words` — `backend/src/test/java/com/lingua_app/backend/controller/AnalysisControllerTest.java`
- [x] T039 [P] [US3] Write `AnalysisPipelineIT.java` (`@SpringBootTest` + Testcontainers PostgreSQL + mocked DeepL/Claude via `@MockBean`): authenticate user, submit `"오늘 날씨가 정말 좋네요"`, assert `language=kor`, non-null `translation`, all `words[].romanization` non-null; submit English sentence, assert `words[].romanization` all null — `backend/src/test/java/com/lingua_app/backend/integration/AnalysisPipelineIT.java`

**Checkpoint**: `POST /api/analyze` with a valid token and Korean text returns a full `AnalysisResponse`. `./mvnw test -Dtest=AnalysisPipelineIT` passes.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Constitution compliance items that touch multiple components.

- [x] T040 [P] Configure CORS in `SecurityConfig.java`: read allowed origins from `ALLOWED_ORIGINS` env var (comma-separated); wildcard `*` MUST NOT be used in production; reject preflight from unknown origins — `backend/src/main/java/com/lingua_app/backend/config/SecurityConfig.java`
- [x] T042 [P] Add structured request-scoped logging: in `GlobalExceptionHandler.java` log external API failures at ERROR with `requestId`, `tier`, and `latencyMs` (never log user text at ERROR); add `X-Request-Id` response header in a `HandlerInterceptor` or filter — `backend/src/main/java/com/lingua_app/backend/exception/GlobalExceptionHandler.java`
- [x] T043 Run Docker Compose smoke test: `docker compose up -d`, execute all curl commands from `quickstart.md §4`, confirm register returns 201, login returns `accessToken`, analyze returns `words` array; shut down cleanly
- [ ] T044 OWASP Top 10 review: verify no raw SQL (all queries via JPA/`@Query` with bind params), no stack trace leakage (`GlobalExceptionHandler` catches all), no IDOR (analyze uses authenticated `userId`, not a request param), no hardcoded secrets in committed files, refresh token stored as SHA-256 hash not plaintext; document findings in a comment on the PR
- [ ] T045 Configure HTTPS enforcement: add `server.ssl.*` properties to `application.yaml` bound to env vars (`SSL_KEY_STORE`, `SSL_KEY_STORE_PASSWORD`, `SSL_KEY_STORE_TYPE`); in `SecurityConfig.java` add `requiresSecure()` channel rule so HTTP requests are rejected with 400 in non-local profiles; document `SPRING_PROFILES_ACTIVE=prod` requirement in `quickstart.md` (constitution Principle II MUST) — `backend/src/main/resources/application.yaml`, `backend/src/main/java/com/lingua_app/backend/config/SecurityConfig.java`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Requires Phase 1 entities — **blocks all user stories**
- **US1 (Phase 3)**: Requires Phase 2 completion
- **US2 (Phase 4)**: Requires Phase 2 completion; extends Phase 3's `AuthService` and `AuthController`
- **US3 (Phase 5)**: Requires Phase 2 completion; independent of US1/US2 beyond needing a valid token for tests
- **Polish (Phase 6)**: Requires all story phases complete

### User Story Dependencies

- **US1 (Registration)**: Standalone after Phase 2
- **US2 (Login/Refresh)**: Extends the same `AuthService` and `AuthController` created in US1 — implement immediately after US1
- **US3 (Analysis)**: Independent of US1/US2 logic; only needs a JWT-authenticated request context from Phase 2

### Parallel Opportunities Within Phases

- **Phase 1**: T001 ‖ T002
- **Phase 2**: T003 ‖ T004 ‖ T005 ‖ T006 ‖ T007 ‖ T008 → then T009 → then T010
- **Phase 3**: T011 ‖ T012 → then T013 → T014 → T015
- **Phase 4**: T016 ‖ T017 → T018 → T019 → T020 → T021 ‖ T022
- **Phase 5**: T023 ‖ T024 ‖ T025 ‖ T026 ‖ T027 ‖ T028 ‖ T029 → T030 ‖ T031 ‖ T032 ‖ T033 ‖ T034 → T041 → T035 → T036 → T037 → T038 ‖ T039
- **Phase 6**: T040 ‖ T042 → T043 → T044 → T045

---

## Parallel Execution Examples

```
# Phase 2 — start all independent foundation tasks together:
T003 AppException.java
T004 GlobalExceptionHandler.java
T005 UserRepository.java
T006 RefreshTokenRepository.java
T007 JwtService.java
T008 UserDetailsServiceImpl.java

# Phase 5 — start all DTOs and config together:
T023 AnalysisRequest.java
T024 WordCardDto.java
T025 AnalysisResponse.java
T026 WordCard.java
T027 AnalysisContext.java
T028 RateLimitConfig.java
T029 ResilienceConfig.java

# Phase 5 — then all pipeline steps in parallel:
T030 DetectionStep.java
T031 TranslationStep.java
T032 DictionaryStep.java
T033 ClaudeStep.java
T034 RomanizationStep.java
```

---

## Implementation Strategy

### MVP First (US1 + US2 — Auth only)

1. Phase 1: Setup (T001–T002)
2. Phase 2: Foundational (T003–T010)
3. Phase 3: US1 Registration (T011–T015)
4. Phase 4: US2 Login/Refresh (T016–T022)
5. **STOP and VALIDATE**: Full auth flow works, `AuthFlowIT` passes
6. Demo / deploy auth-only backend

### Full Delivery (Add US3)

7. Phase 5: US3 Text Analysis (T023–T039, T041)
8. **VALIDATE**: `AnalysisPipelineIT` passes, quickstart smoke test passes
9. Phase 6: Polish (T040, T042–T045)

---

## Summary

| Phase | Tasks | Story | Parallel Opportunities |
|-------|-------|-------|----------------------|
| 1 — Setup | T001–T002 | — | T001 ‖ T002 |
| 2 — Foundational | T003–T010 | — | T003–T008 in parallel |
| 3 — US1 Registration | T011–T015 | US1 (P1) | T011 ‖ T012 |
| 4 — US2 Login/Refresh | T016–T022 | US2 (P1) | T016 ‖ T017; T021 ‖ T022 |
| 5 — US3 Analysis | T023–T039, T041 | US3 (P2) | T023–T029; T030–T034 |
| 6 — Polish | T040, T042–T045 | — | T040 ‖ T042 |
| **Total** | **45 tasks** | | |
