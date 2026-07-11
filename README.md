# Lingua — Language Learning App

A mobile language-learning application with a Spring Boot backend that breaks down foreign-language text into structured word cards, with full translation and romanization support.

## Tech Stack

**Mobile**
- React Native 0.86 (Expo SDK 57) · TypeScript 6
- React Navigation v7 (native stack + bottom tabs)
- expo-secure-store (token storage: Android Keystore / iOS Keychain)
- Hand-rolled typed `fetch` client (no axios) with error-envelope parsing
- Jest (jest-expo) + React Native Testing Library

**Backend**
- Java 25 · Spring Boot 4.1.0
- Spring Security + JJWT (authentication)
- PostgreSQL 16 (primary storage)
- Redis 7 (rate limiting via Bucket4j)
- Flyway (schema migrations)
- Resilience4j (circuit breakers)

**Language Services**
- DeepL — translation (primary)
- Anthropic Claude — morphological analysis and translation fallback
- Kuromoji — Japanese tokenization
- ICU4J — Korean/Japanese romanization
- Pinyin4j — Chinese romanization
- Apache Lucene — English lemmatization

## Features

### Authentication (Phase 1)
- Register with email + password
- Login returns a JWT access token (15 min) and a refresh token (30 days)
- Token refresh endpoint — no re-login required
- Refresh token rotation and revocation tracking

### Text Analysis (Phase 1)
Analyzes a passage in Korean, Japanese, Chinese, or English and returns per-token word cards.

**5-Tier Pipeline**
1. **Language detection** — Unicode script ranges (no API call)
2. **Translation** — DeepL; falls back to Claude at 90% quota
3. **Dictionary lookup** — language-specific tokenizers
4. **Morphological analysis** — Claude tool-use for ambiguous tokens
5. **Romanization** — ICU4J / Pinyin4j for non-Latin scripts

**Word Card Output**
```json
{
  "surface": "食べる",
  "lemma": "食べる",
  "pos": "verb",
  "gloss": "to eat",
  "romanization": "taberu"
}
```

**Rate limiting**: 20 requests/min per user (configurable).

### Analysis Validation (Phase 2)

Every `/analyze` response carries two always-present fields that report how trustworthy
the analysis is:

```json
{
  "language": "kor",
  "translation": "The weather is really nice today.",
  "words": [ ... ],
  "confidence": "medium",
  "issues": [
    {
      "code": "CARDS_OUT_OF_ORDER",
      "severity": "warning",
      "surface": "날씨가",
      "detail": "1 word card(s) appear out of sentence order."
    }
  ]
}
```

- `confidence` — `"high"`, `"medium"`, or `"low"`. Clean analyses are `high`; any warning
  drops to `medium`; any error-severity issue, a failed core pipeline stage, or warnings
  on more than half the cards drop to `low`.
- `issues` — empty array when the analysis is clean.
- `issues[].code` — **stable**: clients may switch on it. New codes may be added, but
  existing codes are never renamed or repurposed.
- `issues[].surface` — the affected word as it appears in a card/input; `null` for
  response-level issues.
- `issues[].detail` — display-safe, human-readable, **not stable** — show it, don't parse it.

**Issue codes**

| Code | Severity | Meaning |
|---|---|---|
| `EMPTY_ANALYSIS` | error | Non-empty input produced zero word cards |
| `INPUT_NOT_COVERED` | error | Part of the input appears in no card; `detail` names the missing fragment(s) |
| `CARDS_OUT_OF_ORDER` | warning | Card order doesn't follow input position |
| `MISSING_FIELD` | warning | A card's `lemma` or `gloss` is missing/blank (one issue per card) |
| `ROMANIZATION_PASSTHROUGH` | warning | `romanization` identical to `surface` (Korean/Japanese/Chinese) |
| `UNKNOWN_POS` | warning | POS label couldn't be normalized to the canonical set; original label passed through |
| `AI_ENTRY_REJECTED` | warning | An AI-generated entry was dropped (missing field, or surface not in the input) |
| `STAGE_FAILED` | error or warning | A pipeline stage failed; `detail` names the stage. Error for dictionary/claude/detection, warning for translation/romanization |

`words[].pos` is normalized to a canonical vocabulary
(`noun, verb, adj, adv, pron, prep, conj, det, num, particle, punct, other`); any
unmapped label is passed through unchanged and flagged with `UNKNOWN_POS`.

Responses containing `STAGE_FAILED` are transient (retrying may succeed); all other
codes are deterministic for the same input. The full contract, including the normative
confidence derivation and cache-eligibility rules, is in
[specs/002-analysis-validation/contracts/validation-api.md](specs/002-analysis-validation/contracts/validation-api.md).

### Mobile App (Phase 3, in progress)

React Native (Expo) app for Android — the first consumer of the backend API.

**Shipped (US1 + US2, merged to main)**
- **Analyze screen** — enter/paste text (≤500 chars, live counter), optional language
  override (auto / 한국어 / 日本語 / 中文 / English–Latin), translation + ordered word
  cards with romanization; null card fields are omitted, not rendered as blanks
- **Full auth journey** — register (with inline validation and an
  "email already exists → sign in instead" affordance), sign in, session persists
  across app restarts, sign out from the Home header
- **Silent token refresh** — on 401/403 the app performs a single-flight refresh
  (concurrent failures share one refresh; rotation-safe), retries the original
  request once, and only forces re-auth when the refresh token itself is rejected
- **Draft preservation** — text typed on Home survives a forced re-login
  (kept in memory only, never written to disk)
- **Error handling** — every backend error code maps to a plain-language message,
  placed where it belongs (inline at the input, beside the language picker, or in a
  retryable banner); raw wire codes never reach the screen

**Remaining on the feature branch**: reliability UI (confidence badge, per-card
warnings, empty-analysis explanation), tab icons and shell polish.

## Getting Started

### Prerequisites
- Java 25+
- Docker + Docker Compose
- Maven (or use the bundled `mvnw`)

### 1. Start infrastructure

```bash
cd backend
docker-compose up -d
```

This starts PostgreSQL 16 on `:5432` and Redis 7 on `:6379`.

### 2. Configure environment

Copy the example and fill in your API keys:

```bash
cp .env.example.yml .env.yml
```

| Variable | Description |
|---|---|
| `DATABASE_URL` | JDBC connection string |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | Postgres credentials |
| `REDIS_HOST` / `REDIS_PORT` | Redis connection |
| `JWT_SECRET` | Base64-encoded 256-bit secret |
| `JWT_EXPIRY_SECONDS` | Access token TTL (default: 900) |
| `REFRESH_TOKEN_EXPIRY_DAYS` | Refresh token TTL (default: 30) |
| `CLAUDE_API_KEY` | Anthropic API key |
| `DEEPL_API_KEY` | DeepL API key |
| `RATE_LIMIT_RPM` | Per-user request limit (default: 20) |

### 3. Run the backend

```bash
./mvnw spring-boot:run
```

Flyway migrations run automatically on startup.

### 4. Run the mobile app (Android emulator)

Prerequisites: Node.js 20 LTS, Android Studio with a bootable AVD (API 34+).

```powershell
cd mobile
copy .env.example .env   # EXPO_PUBLIC_API_URL=http://10.0.2.2:8080
npm install
npx expo start           # press "a" to launch on the Android emulator
```

`10.0.2.2` is the emulator's alias for the host's `localhost`. Cleartext HTTP is
allowed only for local hosts — the client refuses a non-local API URL that isn't
`https://`. More detail (and gotchas like OneDrive file locks and emulator clock
skew) in [specs/003-mobile-analyze-screen/quickstart.md](specs/003-mobile-analyze-screen/quickstart.md).

## API Overview

All responses use a structured envelope. Errors look like:
```json
{
  "error": {
    "code": "INVALID_CREDENTIALS",
    "message": "Email or password is incorrect.",
    "retryable": false
  }
}
```

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/auth/register` | Create account — returns `201` + message, **no tokens**; follow with login |
| `POST` | `/api/auth/login` | Login, get access (15 min) + refresh (30 days) tokens |
| `POST` | `/api/auth/refresh` | Rotate the token pair (submitted refresh token is invalidated) |
| `POST` | `/api/analyze` | Analyze text, get word cards (Bearer auth) |

Full request/response contracts are in
[specs/001-backend-auth-analyze/contracts/](specs/001-backend-auth-analyze/contracts/); the
wire contract as consumed by the mobile app (including live-verified corrections) is in
[specs/003-mobile-analyze-screen/contracts/backend-api.md](specs/003-mobile-analyze-screen/contracts/backend-api.md).

## Project Structure

```
language-app/
├── backend/
│   ├── src/main/java/com/lingua_app/backend/
│   │   ├── config/          # Security, Redis, app config
│   │   ├── controller/      # REST controllers
│   │   ├── dto/             # Request/response DTOs
│   │   ├── entity/          # JPA entities
│   │   ├── exception/       # Global error handling
│   │   ├── mapper/          # Entity ↔ DTO mappers
│   │   ├── repository/      # Spring Data repositories
│   │   └── service/         # Business logic
│   ├── src/main/resources/
│   │   ├── application.yaml
│   │   └── db/migration/    # Flyway SQL migrations
│   └── docker-compose.yml
├── mobile/
│   ├── src/
│   │   ├── api/             # Typed fetch client, wire types, endpoint functions
│   │   ├── auth/            # AuthContext, secure token storage, silent refresh, draft stash
│   │   ├── components/      # AnalyzeInput, LanguagePicker, ResultView, WordCardView, ...
│   │   ├── i18n/            # Plain-language messages for every wire code
│   │   ├── navigation/      # Root stack (auth ⇄ tabs) + bottom tabs
│   │   └── screens/         # Home (analyze), Login, Register, placeholders
│   └── __tests__/           # Jest + React Native Testing Library
└── specs/                   # Feature specs, plans, and contracts
```

## Running Tests

**Backend** — integration tests use Testcontainers and spin up real PostgreSQL and Redis instances:

```bash
cd backend
./mvnw test
```

**Mobile**:

```powershell
cd mobile
npm test           # jest + React Native Testing Library
npm run typecheck  # tsc --noEmit
npm run lint
```