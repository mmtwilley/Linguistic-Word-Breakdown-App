# Research: Backend Authentication & Text Analysis API

**Feature**: 001-backend-auth-analyze
**Date**: 2026-06-19
**Status**: Complete — all unknowns resolved

---

## Decision 1: Java Runtime Version

**Decision**: Java 21 (LTS)

**Rationale**: Spring Boot 3.x requires Java 17 minimum; Java 21 is the current LTS with virtual threads
(Project Loom) available, which benefits I/O-heavy workloads like outbound API calls to DeepL and Claude.
No breaking changes from 17 to 21 for this use case.

**Alternatives considered**:
- Java 17 — supported but misses virtual thread performance gains
- Java 11 — incompatible with Spring Boot 3.x

---

## Decision 2: JWT Library

**Decision**: `io.jsonwebtoken:jjwt` (0.12.x)

**Rationale**: Widely adopted, minimal API surface, actively maintained. Generates and validates compact
JWTs with HMAC-SHA256 signing. Sufficient for access token (15 min) + refresh token identity (UUID stored
in DB, not inside the JWT). Does not require a full OAuth2 server for this use case.

**Refresh token strategy**: Refresh tokens are opaque random UUIDs stored in the `refresh_tokens` table.
The JWT access token carries only `userId` and `email` as claims. Refresh token rotation: each use issues
a new refresh token and revokes the previous one.

**Alternatives considered**:
- Spring Security OAuth2 Authorization Server — excessive for a mobile API with a single client
- nimbus-jose-jwt — more powerful but higher complexity than required for Phase 1

---

## Decision 3: Language Detection — Unicode Script Ranges (Zero Dependency)

**Decision**: Unicode block range counting — identical algorithm on both client (TypeScript) and server (Java).
No external library on either side.

**Client-side** (`mobile/src/utils/detectScript.ts`):
```typescript
export const SCRIPT = { KOR: 'kor', JPN: 'jpn', CMN: 'cmn', LAT: 'lat', UND: 'und' };

export function detectScript(text: string): string {
  let kor = 0, kana = 0, cjk = 0, lat = 0;
  for (const ch of text) {
    const cp = ch.codePointAt(0)!;
    if ((cp >= 0xAC00 && cp <= 0xD7A3) || (cp >= 0x1100 && cp <= 0x11FF) || (cp >= 0x3130 && cp <= 0x318F)) kor++;
    else if ((cp >= 0x3040 && cp <= 0x309F) || (cp >= 0x30A0 && cp <= 0x30FF)) kana++;
    else if (cp >= 0x4E00 && cp <= 0x9FFF) cjk++;
    else if ((cp >= 0x41 && cp <= 0x5A) || (cp >= 0x61 && cp <= 0x7A)) lat++;
  }
  if (kor === 0 && kana === 0 && cjk === 0 && lat === 0) return SCRIPT.UND;
  if (kor > 0 && kor >= kana && kor >= cjk && kor >= lat) return SCRIPT.KOR;
  if (kana > 0) return SCRIPT.JPN;
  if (cjk > 0 && cjk >= lat) return SCRIPT.CMN;
  return SCRIPT.LAT;
}
```

**Server-side** (`ScriptDetector.java`) — same algorithm, no external dependency:
```java
public static String detectScript(String text) {
    int kor = 0, kana = 0, cjk = 0, lat = 0;
    for (int i = 0; i < text.length(); ) {
        int cp = text.codePointAt(i);
        if ((cp >= 0xAC00 && cp <= 0xD7A3) || (cp >= 0x1100 && cp <= 0x11FF) || (cp >= 0x3130 && cp <= 0x318F)) kor++;
        else if ((cp >= 0x3040 && cp <= 0x309F) || (cp >= 0x30A0 && cp <= 0x30FF)) kana++;
        else if (cp >= 0x4E00 && cp <= 0x9FFF) cjk++;
        else if ((cp >= 0x41 && cp <= 0x5A) || (cp >= 0x61 && cp <= 0x7A)) lat++;
        i += Character.charCount(cp);
    }
    if (kor == 0 && kana == 0 && cjk == 0 && lat == 0) return "und";
    if (kor > 0 && kor >= kana && kor >= cjk && kor >= lat) return "kor";
    if (kana > 0) return "jpn";
    if (cjk > 0 && cjk >= lat) return "cmn";
    return "lat";
}
```

**Rationale**: For Phase 1's supported language set (Korean, Japanese, Chinese, English), Unicode script
blocks are uniquely identifying — no statistical model is needed. Kana characters (Hiragana/Katakana)
are exclusive to Japanese, Hangul to Korean, and CJK-without-Kana defaults to Chinese. This gives
100% accuracy for these languages at zero cost and zero latency.

**Request flow**: The mobile client calls `detectScript()` before sending to `/analyze` and includes the
result as `"language"` in the request body. The backend runs its own detection as a fast validation and
fallback. If the resolved language is `"und"` (too short, symbols only), the backend returns a 400 error.

**Alternatives considered**:
- `com.github.pemistahl:lingua` (~28MB, statistical models) — accurate but vastly over-engineered for
  a 4-language set with distinct scripts; replaced by 15 lines of code
- Apache Tika language detection — heavy dependency, worse accuracy on short texts
- `franc` (JS-only) — client-side only; server would have no fallback

---

## Decision 4: Translation — DeepL Integration

**Decision**: Official `com.deepl.api:deepl-java` SDK

**Rationale**: Official SDK handles request/response marshalling, error codes, and quota tracking.
DeepL's translation quality is best-in-class for Korean ↔ English and competitive for Japanese and
Chinese. The free tier (500k chars/month) is tracked server-side using a Redis monthly counter with
automatic reset at month start.

**Fallback**: When DeepL returns `QuotaExceededException` or a 5xx error, the pipeline falls back
to asking Claude to provide the translation alongside the morphological analysis (one combined prompt).

**Alternatives considered**:
- NAVER Papago — no official Java SDK; REST-only, rate limits more restrictive on free tier
- Google Cloud Translate — paid from first character; cost-inefficient vs DeepL free tier
- Claude for all translation — highest quality but highest cost; reserved for quota exhaustion

---

## Decision 5: Dictionary APIs by Language

| Language | API | Auth | Bundled? | Notes |
|----------|-----|------|----------|-------|
| Korean | Krdict API (국립국어원) | API key required (free, registration) | No | REST; returns lemma, POS, definition |
| Japanese | Kuromoji (local tokenizer) | None | Yes (JAR) | Handles tokenization + reading extraction locally |
| Chinese | CC-CEDICT | None | Yes (static file ~8MB) | UTF-8 dictionary file parsed at startup into a `Map<String, CedictEntry>` |
| English | Free Dictionary API (`api.dictionaryapi.dev`) | None | No | REST; fallback to Claude if unavailable |

**Japanese special case**: `org.apache.lucene:lucene-analysis-kuromoji` handles both tokenization and
reading extraction for Japanese, replacing both the dictionary API lookup and romanization steps.
Lucene's `JapaneseTokenizer` gives token surface, base form (lemma), POS tag, and reading (katakana),
which is then converted to romaji via ICU4J.

**Why not Atilika (`com.atilika:kuromoji-ipadic`)**: Atilika's standalone library is unmaintained
(last release 2017); the same Kuromoji engine is actively developed and shipped as part of Apache
Lucene, which is already a declared dependency (`lucene-core`). The Lucene API is the canonical
current interface.

---

## Decision 6: Romanization Libraries

| Language | Library | Maven Artifact | Notes |
|----------|---------|----------------|-------|
| Korean | ICU4J Transliterator | `com.ibm.icu:icu4j` | `Hangul-Latin/BGN` transliteration scheme |
| Japanese | Kuromoji → ICU4J | same | Kuromoji extracts katakana reading; ICU4J converts to romaji |
| Chinese | Pinyin4j | `com.belerweb:pinyin4j` | Handles tone marks; use tone-number format for simplicity |
| English | N/A | — | No romanization field for Latin-script languages |

ICU4J is a shared dependency covering Korean and Japanese → only one ICU4J artifact needed.

---

## Decision 7: Claude API Integration

**Decision**: `com.anthropic:anthropic-java` (official Anthropic Java SDK)

**Rationale**: Official SDK handles streaming, retries, and error classification. The SDK supports
tool use (structured JSON output), which is the correct approach for extracting WordCard arrays
rather than asking Claude to output free-form JSON.

**Tool schema**: Claude is given a tool named `analyze_words` with a strict JSON schema matching
the WordCard type. Claude is instructed to call this tool to return morphological data. The prompt
template conditionally includes `particles`/`endings` fields only for Korean and Japanese, and
excludes `romanization` (handled locally).

**What Claude receives in the prompt**:
1. The source text
2. The detected language
3. The pre-computed translation (from DeepL, or "NEEDS TRANSLATION" if DeepL failed)
4. The words already resolved by the dictionary API (so Claude skips them)
5. Instruction to fill in only the words NOT covered by the dictionary lookup

**Model**: `claude-sonnet-4-6` (per project constitution)

**Alternatives considered**:
- Direct REST calls to Anthropic API — viable but reimplements retry/error handling the SDK provides

---

## Decision 8: Resilience — Circuit Breaker and Retry

**Decision**: `io.github.resilience4j:resilience4j-spring-boot3`

**Rationale**: resilience4j integrates natively with Spring Boot 3.x and provides both `@CircuitBreaker`
and `@Retry` annotations. Applied to `ClaudeAnalysisStep` and `DeepLTranslationStep`. Configured with:
- Retry: 3 attempts, exponential backoff starting at 500ms, on `IOException` and 5xx responses
- Circuit breaker: Opens after 5 consecutive failures within 60s; half-open after 30s recovery wait

**Alternatives considered**:
- Spring Retry — lighter but lacks circuit breaker; would require adding Resilience4j anyway
- Hystrix — deprecated; Netflix no longer maintains it

---

## Decision 9: Rate Limiting

**Decision**: `com.github.vladimir-bukhtoyarov:bucket4j-core` + `com.bucket4j:bucket4j-redis`

**Rationale**: Bucket4j implements the token bucket algorithm. The Redis backend ensures rate limit
state is shared across multiple Spring Boot instances (horizontal scaling). Each bucket is keyed by
`userId`. Configuration: 20 requests/minute per user (configurable via env var `RATE_LIMIT_RPM`).

**Alternatives considered**:
- Spring Cloud Gateway rate limiting — adds a gateway service dependency not needed in Phase 1
- In-memory ConcurrentHashMap bucket — does not survive restart or scale to multiple instances

---

## Decision 10: Database Migrations

**Decision**: `org.flywaydb:flyway-core` with versioned SQL migration files

**Rationale**: Flyway runs migrations on startup, ensuring the schema matches the application
automatically. Compatible with 12-Factor (Factor IX: disposability). Migration files are versioned
SQL stored under `src/main/resources/db/migration/`.

**Alternatives considered**:
- Hibernate `ddl-auto: create-drop` — destroys data on restart; PROHIBITED for anything beyond
  local scratch environments
- Liquibase — more capable but higher complexity; Flyway is sufficient for this project

---

## Decision 11: Structured Logging

**Decision**: Logback (default with Spring Boot) + `net.logstash.logback:logstash-logback-encoder`

**Rationale**: logstash-logback-encoder emits JSON-formatted log lines to stdout — satisfying
12-Factor XI and making logs parseable by any log aggregation system without additional config.

**Configuration**: Set via `logback-spring.xml`. In `prod` profile: JSON appender only.
In `dev` profile: human-readable console appender for local readability.

---

## Decision 12: Testing Strategy

**Decision**: JUnit 5 + Spring Boot Test + Testcontainers

**Rationale**: Testcontainers launches real PostgreSQL and Redis Docker containers for integration
tests, satisfying 12-Factor X (dev/prod parity). Spring Boot Test loads the full application context.
`@WebMvcTest` for controller-layer unit tests (mock services). No H2 — prohibited by constitution.

| Test type | Framework | Scope |
|-----------|-----------|-------|
| Unit | JUnit 5 + Mockito | Service and pipeline step logic |
| Controller | `@WebMvcTest` + MockMvc | Request/response shape, auth filters |
| Integration | `@SpringBootTest` + Testcontainers | Full pipeline against real DB + Redis |
| Contract | MockMvc + JSON assertions | API response envelope conformance |

---

## Resolved NEEDS CLARIFICATION Items

None — all technical choices were derivable from project constitution, tech stack definition, and
established library ecosystem.
