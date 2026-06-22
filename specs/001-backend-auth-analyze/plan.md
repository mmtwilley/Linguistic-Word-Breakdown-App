# Implementation Plan: Backend Authentication & Text Analysis API

**Branch**: `001-backend-auth-analyze` | **Date**: 2026-06-21 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/001-backend-auth-analyze/spec.md`

## Summary

Build a Spring Boot REST API providing secure user authentication (register, login, token refresh) and a text analysis endpoint that runs a tiered pipeline — language detection → DeepL translation → per-language dictionary lookup → Claude morphological analysis (last resort) → local romanization — to produce per-token WordCard responses for Korean, Japanese, Chinese, and English input.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1.0
**Primary Dependencies**: Spring Security, Spring Data JPA (Hibernate), Flyway, JJWT 0.13.0, Resilience4j 2.4.0, Bucket4j + bucket4j-redis (to add to pom), DeepL Java SDK 1.16.0, Anthropic Java SDK 2.42.0, Apache Lucene Kuromoji 10.4.0, ICU4J 78.3, Pinyin4j 2.5.1, Logback + logstash-logback-encoder 9.0, Lombok
**Storage**: PostgreSQL 16 (primary data), Redis 7 (rate limiting, DeepL quota counter)
**Testing**: JUnit 5 + Mockito (unit), `@WebMvcTest` + MockMvc (controller), `@SpringBootTest` + Testcontainers (integration)
**Target Platform**: Linux server (Docker container); local: Docker Compose
**Project Type**: REST API / web-service
**Performance Goals**: Registration < 3s, login < 2s, analysis (10-word sentence) < 10s (SC-001–SC-003)
**Constraints**: 500-char max analysis input, 20 RPM per user (configurable via `RATE_LIMIT_RPM`), 15-min access token, 30-day refresh token
**Scale/Scope**: Phase 1 — single backend instance; no horizontal scaling configuration required

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Principle | Status |
|------|-----------|--------|
| Tiered pipeline implemented in strict order: Detection → DeepL → Dictionary → Claude → Romanization | I | ✅ PASS |
| `romanization` generated locally (ICU4J for Korean/Japanese, Pinyin4j for Chinese) — never by Claude | I | ✅ PASS |
| Claude tool schema is language-conditional (particles/endings fields only for Korean/Japanese) | I | ✅ PASS |
| DeepL quota tracked in Redis (monthly counter); falls back to Claude at 90% of monthly cap | I | ✅ PASS |
| All API keys (Claude, DeepL, Krdict) server-side via env vars only; never returned to client | II | ✅ PASS |
| JWT access token ≤ 15 min (`JWT_EXPIRY_SECONDS=900`) with refresh token rotation | II | ✅ PASS |
| Input sanitization (length, encoding, trim) before any tier call in pipeline | II | ✅ PASS |
| Rate limiting via Bucket4j executes before any pipeline step | II | ✅ PASS |
| `GlobalExceptionHandler` prevents stack traces and internal details reaching client | II | ✅ PASS |
| CORS allowed origins from env var; wildcard `*` PROHIBITED in production | II | ✅ PASS |
| All secrets in env vars (`${VAR}` pattern throughout `application.yaml`); no hardcoded credentials | III | ✅ PASS |
| Stateless Spring Boot instances; no per-request state stored in process memory | III | ✅ PASS |
| Docker Compose provides real PostgreSQL 16 + Redis 7 (no H2, no mock Redis) | III | ✅ PASS |
| Structured JSON logs to stdout (`LogstashEncoder` in prod profile of `logback-spring.xml`) | III | ✅ PASS |
| Each pipeline tier has an explicit fallback; no bare 500 when a downstream tier fails | IV | ✅ PASS |
| Resilience4j `@CircuitBreaker` + `@Retry` applied to `ClaudeStep` and `TranslationStep` | IV | ✅ PASS |
| All error responses use structured envelope `{"error":{"code","message","retryable"}}` | IV | ✅ PASS |
| No future-phase features implemented (SRS, flashcards, analysis persistence, overlay) | V | ✅ PASS |

**Pre-implementation issues**: All resolved.

## Project Structure

### Documentation (this feature)

```text
specs/001-backend-auth-analyze/
├── plan.md              # This file
├── research.md          # Phase 0 complete
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   ├── auth.md          # Auth endpoint contracts
│   └── analyze.md       # Analysis endpoint contract
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code

```text
backend/
├── src/main/java/com/lingua_app/backend/
│   ├── analysis/
│   │   ├── pipeline/
│   │   │   ├── AnalysisPipeline.java      # Orchestrates steps in tier order
│   │   │   ├── AnalysisContext.java       # Mutable context passed between steps
│   │   │   └── WordCard.java              # Internal domain object (maps to DTO)
│   │   └── step/
│   │       ├── DetectionStep.java         # Unicode range script detection
│   │       ├── TranslationStep.java       # DeepL → Claude fallback
│   │       ├── DictionaryStep.java        # Per-language dictionary lookup
│   │       ├── ClaudeStep.java            # Claude tool-use morphological analysis
│   │       └── RomanizationStep.java      # ICU4J (kor/jpn), Pinyin4j (cmn)
│   ├── config/
│   │   ├── SecurityConfig.java
│   │   ├── RateLimitConfig.java           # Bucket4j + Redis bucket factory
│   │   └── ResilienceConfig.java          # Circuit breaker + retry bean config
│   ├── controller/
│   │   ├── AuthController.java            # /api/auth/**
│   │   └── AnalysisController.java        # /api/analyze
│   ├── dto/
│   │   ├── RegisterRequest.java
│   │   ├── LoginRequest.java
│   │   ├── RefreshRequest.java
│   │   ├── AuthResponse.java
│   │   ├── AnalysisRequest.java
│   │   ├── AnalysisResponse.java
│   │   └── WordCardDto.java
│   ├── entity/
│   │   ├── Users.java
│   │   └── RefreshToken.java
│   ├── exception/
│   │   ├── AppException.java              # Runtime exception with HTTP status + error code
│   │   └── GlobalExceptionHandler.java    # @RestControllerAdvice; emits structured envelope
│   ├── repository/
│   │   ├── UserRepository.java
│   │   └── RefreshTokenRepository.java
│   ├── security/
│   │   ├── JwtService.java
│   │   ├── JwtAuthFilter.java             # OncePerRequestFilter; validates Bearer token
│   │   └── UserDetailsServiceImpl.java
│   ├── service/
│   │   ├── AuthService.java               # Register, login, refresh, revoke
│   │   └── AnalysisService.java           # Input validation + rate limit + pipeline dispatch
│   ├── AppProperties.java
│   └── BackendApplication.java
├── src/main/resources/
│   ├── db/migration/V1__initial_schema.sql
│   ├── application.yaml
│   ├── application-dev.yaml
│   └── logback-spring.xml
└── src/test/java/com/lingua_app/backend/
    ├── controller/
    │   ├── AuthControllerTest.java        # @WebMvcTest
    │   └── AnalysisControllerTest.java
    ├── integration/
    │   ├── AuthFlowIT.java                # Full register → login → refresh; Testcontainers
    │   └── AnalysisPipelineIT.java
    └── service/
        └── AuthServiceTest.java
```

**Structure Decision**: Backend-only Maven module under `backend/`. No frontend directory in Phase 1.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| 5-step analysis pipeline | Constitution Principle I mandates tiered cost optimization in strict order | Single Claude call satisfies requirements but violates NON-NEGOTIABLE cost-optimization mandate |
| Redis for rate limiting + DeepL quota | Principles II + III: rate limiting required; stateless instances cannot share in-process state | In-memory bucket is per-instance and lost on restart; not viable for stateless multi-instance deployment |
