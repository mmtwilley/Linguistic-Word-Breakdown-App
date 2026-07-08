# Developer Quickstart: Backend Authentication & Text Analysis API

**Branch**: `001-backend-auth-analyze`

## Prerequisites

- Java 25 (set as default JDK — verify with `java -version`)
- Docker Desktop running
- Maven 3.9+ (or use the bundled `./mvnw`)

## 1. Start backing services

```bash
cd backend
docker compose up -d
```

Starts PostgreSQL 16 on `:5432` and Redis 7 on `:6379`.

## 2. Configure environment

Export the required variables (or add to your shell profile / `.env` file):

```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/lingua
export DATABASE_USERNAME=lingua
export DATABASE_PASSWORD=lingua
export REDIS_HOST=localhost
export REDIS_PORT=6379
export JWT_SECRET=dev-secret-that-is-at-least-32-chars-long
export JWT_EXPIRY_SECONDS=900
export REFRESH_TOKEN_EXPIRY_DAYS=30
export CLAUDE_API_KEY=sk-ant-...
export DEEPL_API_KEY=...
export VERDICT_API_KEY=...       # Krdict (국립국어원) API key
export RATE_LIMIT_RPM=20
export SPRING_PROFILES_ACTIVE=dev
```

See `backend/.env.example.yml` for the full variable list.

## 3. Run the application

```bash
cd backend
./mvnw spring-boot:run
```

Flyway applies `V1__initial_schema.sql` on first startup. The server listens on `:8080`.

## 4. Verify the auth flow

```bash
# Register
curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123"}' | jq

# Login — copy the accessToken and refreshToken from the response
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123"}' | jq

# Analyze (replace <accessToken> with the token from login)
curl -s -X POST http://localhost:8080/api/analyze \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <accessToken>" \
  -d '{"text":"오늘 날씨가 정말 좋네요"}' | jq
```

## 5. Production profile (HTTPS)

HTTPS is mandatory outside local development (constitution Principle II).
Production deployments MUST set:

```bash
export SPRING_PROFILES_ACTIVE=prod
export SSL_KEY_STORE=/path/to/keystore.p12
export SSL_KEY_STORE_PASSWORD=...
export SSL_KEY_STORE_TYPE=PKCS12   # optional, defaults to PKCS12
```

With the `prod` profile active, Tomcat serves TLS directly (plain-HTTP requests
to the TLS port are rejected with 400), and Spring Security additionally
redirects any insecure request to HTTPS — covering deployments where TLS
terminates at a reverse proxy (`X-Forwarded-Proto` is honoured).

## 6. Run tests

```bash
# Unit + controller tests (no Docker needed)
./mvnw test -pl backend -Dtest="*Test"

# Integration tests (Docker required — Testcontainers starts its own containers)
./mvnw test -pl backend -Dtest="*IT"
```

## Key files

| File | Purpose |
|------|---------|
| `backend/src/main/resources/application.yaml` | Main config; all secrets via env vars |
| `backend/src/main/resources/application-dev.yaml` | Dev profile: SQL logging enabled |
| `backend/src/main/resources/db/migration/V1__initial_schema.sql` | `users` + `refresh_tokens` schema |
| `backend/src/main/resources/logback-spring.xml` | JSON logs (prod) / human-readable (dev) |
| `backend/docker-compose.yml` | PostgreSQL 16 + Redis 7 for local dev |
| `backend/pom.xml` | All dependencies — see plan.md for pending additions |

