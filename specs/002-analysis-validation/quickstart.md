# Developer Quickstart: Analysis Validation & Confidence Level

**Branch**: `002-analysis-validation`

## Prerequisites

Same as feature 001 (see `specs/001-backend-auth-analyze/quickstart.md`): Java 25,
Docker Desktop (Postgres 16 + Redis 7 via `backend/docker-compose`), env vars per
`backend/.env.example.yml`. This feature adds **no new env vars and no new dependencies**.

## Run

```bash
cd backend
./mvnw spring-boot:run
```

## Exercise the validation layer

```bash
# Login (register first if needed — see feature 001 quickstart)
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123"}' | jq -r .accessToken)

# A currently-degraded Chinese sentence → expect confidence "low",
# issues containing EMPTY_ANALYSIS (until CEDICT remediation lands)
curl -s -X POST http://localhost:8080/api/analyze \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"text":"学习中文很有意思"}' | jq '{confidence, issues}'

# A clean Korean sentence → expect "high" (or "medium" with CARDS_OUT_OF_ORDER
# until ordering remediation lands), POS labels normalized to the canonical set
curl -s -X POST http://localhost:8080/api/analyze \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"text":"오늘 날씨가 정말 좋네요"}' | jq '{confidence, issues, pos: [.words[].pos]}'
```

## Tests

```bash
# Unit tests (no Docker): ValidationStep truth table, PosNormalizer map,
# ClaudeStep entry rejection, controller serialization
./mvnw test -Dtest="*Test"

# Integration (Docker/Testcontainers): confidence present on every response
./mvnw test -Dtest="*IT"
```

## Full-suite acceptance check (SC-001/SC-003)

With the backend running:

```bash
cd tools/parity-diff
node run-backend.mjs   # re-runs the 10-sentence suite
# backend-results.json responses now include confidence + issues;
# verify against the fixture table in contracts/validation-api.md
```

## Key files

| File | Purpose |
|---|---|
| `analysis/step/ValidationStep.java` | All rule checks + confidence derivation (runs last) |
| `analysis/step/PosNormalizer.java` | Label → canonical vocabulary map |
| `analysis/step/ClaudeStep.java` | Rejects malformed AI entries before merge |
| `analysis/pipeline/ValidationIssue.java`, `Confidence.java` | Domain types |
| `dto/AnalysisResponse.java`, `dto/ValidationIssueDto.java` | API contract |
| `specs/002-analysis-validation/contracts/validation-api.md` | Issue-code catalog (stable) |
