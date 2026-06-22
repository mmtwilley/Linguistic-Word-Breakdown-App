<!--
SYNC IMPACT REPORT
==================
Version change: 1.0.0 → 1.1.0 (MINOR)

Rationale for MINOR bump:
  New rows added to Technology Constraints (Kuromoji, ICU4J, Pinyin4j);
  language detection technology corrected from franc/lingua-js to custom
  Unicode range algorithm per research.md Decision 3.

Changed sections:
  - III. Twelve-Factor App — updated required environment variable registry;
    added DATABASE_USERNAME, DATABASE_PASSWORD, VERDICT_API_KEY,
    REFRESH_TOKEN_EXPIRY_DAYS, RATE_LIMIT_RPM; replaced REDIS_URL (not used)
    with REDIS_HOST + REDIS_PORT (actual Spring bindings)
  - Technology Constraints — corrected Language detection row (franc/lingua-js
    → Unicode script-range detection); added Tokenization (Japanese) row
    (Lucene Kuromoji); added Romanization row (ICU4J + Pinyin4j)

Added sections: none
Removed sections: none

Templates requiring updates:
  ✅ .specify/templates/plan-template.md — Constitution Check gates unchanged; compatible
  ✅ .specify/templates/spec-template.md — no structural changes required
  ✅ .specify/templates/tasks-template.md — no structural changes required

Adjacent files updated:
  ✅ backend/.env.example.yml — corrected to match updated env var registry
    (added DATABASE_USERNAME, DATABASE_PASSWORD; changed REDIS_URL → REDIS_HOST + REDIS_PORT)

Follow-up TODOs:
  - Add ALLOWED_ORIGINS to env var registry (and .env.example.yml) when T040
    (CORS config task) is implemented
-->

# Lingua Mobile Constitution

## Core Principles

### I. Tiered API Pipeline (NON-NEGOTIABLE)

Every text analysis request MUST flow through the cost-optimization pipeline in order.
Claude is the last resort — it MUST NOT be called when a cheaper source can satisfy the request.

Pipeline (in strict order):
1. **franc / lingua-js** — local language detection, zero cost, no network call
2. **DeepL API** — translation (500k chars/month free tier); fall back to Claude only on quota exhaustion or failure
3. **Dictionary API** — lemma, POS, gloss for known words (Krdict / Jisho / CC-CEDICT / Free Dictionary API by language)
4. **Claude** — morphological decomposition, particles/endings, unknown/rare vocabulary, ambiguous POS in context ONLY
5. **Local post-processing** — romanization (Hangul.js / pinyin-pro / wanakana), frequency lookup (static JSON), audio (Web Speech API)

Rules:
- The `romanization` and `ipa` fields MUST be generated locally, never by Claude
- The Claude tool schema MUST be language-conditional: include `particles`/`endings` fields only for Korean and Japanese
- The Claude prompt MUST accept pre-filled `translation` and `knownWords` as context to skip regeneration
- DeepL quota MUST be tracked server-side (Redis counter, monthly reset); when within 10% of limit, fall back to Claude translation

### II. Security-First (NON-NEGOTIABLE)

Security is a first-class concern, not a post-launch concern. Every endpoint, service, and dependency MUST satisfy:

- **API key isolation**: Claude and DeepL keys MUST reside server-side only. The mobile client MUST never receive or transmit them.
- **Authentication**: JWT via Spring Security. Tokens MUST use short expiry (≤15 min access token) with refresh token rotation. Refresh tokens MUST be stored securely (httpOnly equivalent for mobile).
- **Input sanitization**: All text submitted to `/analyze` and `/translate` MUST be treated as untrusted. Validate length, encoding, and reject inputs that could constitute injection attacks before they reach any downstream API.
- **Rate limiting**: Per-user request caps MUST be enforced via Bucket4j before any API call is made. Rate limit state lives in Redis.
- **Transport security**: HTTPS MUST be enforced in all non-local environments. HTTP requests MUST be rejected or redirected.
- **Error opacity**: Stack traces and internal error details MUST NOT be returned to the client. All error responses MUST use the structured envelope (see Principle IV).
- **CORS**: Locked to known mobile app origins in production via `ALLOWED_ORIGINS` env var. Wildcard origins are PROHIBITED in production config.
- **OWASP Top 10**: All features MUST be reviewed against OWASP Top 10 before merge. SQLi, XSS, broken auth, and IDOR are the highest-priority risks for this project.

### III. Twelve-Factor App (NON-NEGOTIABLE)

The backend MUST comply with the Twelve-Factor App methodology. The highest-priority factors for this project:

- **III. Config**: ALL secrets and external service URLs MUST be supplied via environment variables. Hardcoded credentials are PROHIBITED and MUST be caught in code review. No secrets in committed files.
- **IV. Backing services**: PostgreSQL, Redis, DeepL, and Claude MUST be treated as attached resources, swappable via config without code changes.
- **VI. Processes**: Spring Boot instances MUST be stateless. Per-request session state MUST NOT be stored in-process memory. Redis is the session/state store.
- **IX. Disposability**: The server MUST handle SIGTERM gracefully — complete in-flight SRS writes before shutdown. Startup time MUST be minimized.
- **X. Dev/prod parity**: Local development MUST use real PostgreSQL and Redis via Docker Compose. H2 in-memory databases and mocked Redis are PROHIBITED.
- **XI. Logs**: All log output MUST be structured JSON to stdout (Logback + logstash-logback-encoder). `System.out.println`, unstructured log statements, and log files are PROHIBITED.

Required environment variables (MUST be documented in `backend/.env.example.yml`, never in committed config):
```
DATABASE_URL
DATABASE_USERNAME
DATABASE_PASSWORD
REDIS_HOST
REDIS_PORT
CLAUDE_API_KEY
DEEPL_API_KEY
VERDICT_API_KEY
JWT_SECRET
JWT_EXPIRY_SECONDS
REFRESH_TOKEN_EXPIRY_DAYS
RATE_LIMIT_RPM
SPRING_PROFILES_ACTIVE
```

### IV. Graceful Error Handling (NON-NEGOTIABLE)

The tiered pipeline MUST degrade gracefully at every tier. A failure in one tier MUST trigger fallback to the next — a 500 response is never acceptable when a fallback exists.

**Structured error envelope** — ALL API error responses MUST use this shape:
```json
{
  "error": {
    "code": "SNAKE_CASE_ERROR_CODE",
    "message": "Human-readable description safe for display",
    "retryable": true
  }
}
```

Rules:
- External API calls (Claude, DeepL, dictionary APIs) MUST use retry with exponential backoff for transient failures (HTTP 429, 503, timeout)
- A circuit breaker (resilience4j) MUST be applied to Claude and DeepL integrations — fail fast when a service is consistently unavailable
- SRS review session writes MUST be atomic — partial saves that corrupt flashcard scheduling state are PROHIBITED
- Partial results are preferred over empty responses: if dictionary lookup succeeds but Claude fails, return the dictionary data with an `"error"` field on the affected words
- Log all external API failures at ERROR level with request ID, tier, and latency; never log user text content at ERROR level

### V. Simplicity and Phase-Gated Delivery

Build in the order defined in the project plan. Do not implement future phases speculatively.

- **YAGNI**: Features not required by the current phase MUST NOT be implemented
- **No premature abstraction**: Three similar methods are preferable to a framework invented for two
- **Phase gates**: Each phase MUST be demonstrably functional before the next phase begins. A phase is complete when its core user story can be exercised end-to-end
- **Complexity justification**: Any architectural choice that adds complexity (additional service, pattern, dependency) MUST be justified in the plan's Complexity Tracking table

Build order (phases):
1. Spring Boot auth + `/analyze` endpoint
2. React Native skeleton + analyze screen
3. react-native-share-menu (share → analyze)
4. Flashcard + SRS system
5. Redis caching + rate limiting
6. Language detection routing
7. Floating overlay (Android only)

## Technology Constraints

These are fixed for the project. Deviations require explicit amendment to this constitution.

| Layer | Technology |
|-------|-----------|
| Mobile | React Native + TypeScript |
| Backend | Spring Boot (Java) |
| Cache | Redis |
| Database | PostgreSQL |
| AI | Claude API (`claude-sonnet-4-6`) |
| Translation | DeepL API (tiered; Claude fallback) |
| Language detection | Unicode script-range detection (zero dependency; shared algorithm on client and server) |
| Tokenization (Japanese) | Apache Lucene Kuromoji (`lucene-analysis-kuromoji`) |
| Romanization | ICU4J (Korean/Japanese), Pinyin4j (Chinese) |
| Resilience | resilience4j (circuit breaker + retry) |
| Rate limiting | Bucket4j (Redis-backed) |
| Logging | Logback + logstash-logback-encoder |

## Development Workflow

- **Secrets**: Never committed. `backend/.env.example.yml` documents all required vars with placeholder values.
- **Docker Compose**: Required for local dev. MUST provide PostgreSQL and Redis matching production versions.
- **Linting**: Enforced on commit. No exceptions.
- **Feature branches**: All work happens on feature branches. Merges to `main` require constitution compliance review.
- **Commit discipline**: Commit after each logical task or checkpoint. Commit messages MUST reference the task ID.
- **API contracts**: The `WordCard` and `AnalysisResult` TypeScript types are the canonical shared contract between mobile and backend. Changes to these types require simultaneous updates to both layers.

## Governance

This constitution supersedes all other practices and conventions in this project.

- All pull requests MUST include a Constitution Check confirming compliance with all five principles
- Violations discovered in review MUST be fixed before merge, not deferred
- Amendments require: documentation of the change, rationale, and a migration plan for any in-flight work
- Version bumps follow semantic versioning:
  - MAJOR: Principle removal, redefinition, or backward-incompatible governance change
  - MINOR: New principle or section added; material expansion of existing guidance
  - PATCH: Clarification, wording, typo fix, or env var registry update

**Version**: 1.1.0 | **Ratified**: 2026-06-19 | **Last Amended**: 2026-06-21
