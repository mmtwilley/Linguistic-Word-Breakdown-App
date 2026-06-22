# Lingua — Language Learning App

A mobile language-learning application with a Spring Boot backend that breaks down foreign-language text into structured word cards, with full translation and romanization support.

## Tech Stack

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
| `POST` | `/api/v1/auth/register` | Create account |
| `POST` | `/api/v1/auth/login` | Login, get tokens |
| `POST` | `/api/v1/auth/refresh` | Refresh access token |
| `POST` | `/api/v1/analyze` | Analyze text, get word cards |

Full request/response contracts are in [specs/001-backend-auth-analyze/contracts/](specs/001-backend-auth-analyze/contracts/).

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
└── specs/                   # Feature specs, plans, and contracts
```

## Running Tests

```bash
cd backend
./mvnw test
```

Integration tests use Testcontainers and spin up real PostgreSQL and Redis instances.