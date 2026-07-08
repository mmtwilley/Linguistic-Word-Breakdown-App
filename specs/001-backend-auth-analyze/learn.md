# What I Learned: Backend Authentication & Text Analysis API

**Feature**: Secure user accounts (register/login/refresh) plus a tiered text-analysis endpoint returning per-token WordCards for Korean, Japanese, Chinese, and English
**Generated**: 2026-07-08
**Scope**: Full feature (spec 001)
**Implementation status**: 45/45 tasks completed

---

## Key Decisions

### 1. Opaque Refresh Tokens Instead of Refresh JWTs

**What we did**: Access tokens are short-lived (15 min) signed JWTs carrying only `userId` and `email`. Refresh tokens are random UUIDs — the server stores only their SHA-256 hash in the `refresh_tokens` table, and each use rotates the token (new one issued, old one revoked).

**Why**: A JWT can't be revoked once issued — you'd have to wait out its expiry or maintain a blocklist, which defeats the point of stateless tokens. By keeping the long-lived credential opaque and database-backed, revocation is a single row update. Hashing before storage means a database leak doesn't expose usable tokens (same reasoning as password hashing).

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| Long-lived JWT as refresh token | No revocation path; a stolen token is valid for 30 days no matter what |
| Full OAuth2 Authorization Server | Massive complexity for a single first-party mobile client |
| Storing refresh tokens in plaintext | A DB read (SQL injection, backup leak) would hand attackers live sessions |

**When you'd choose differently**: If you had many third-party clients or needed delegated access (login with X), the OAuth2 server earns its complexity. And if session state per-request were acceptable (server-rendered web app), plain server-side sessions are simpler than JWTs entirely.

---

### 2. 15 Lines of Unicode Range Counting Instead of a Language-Detection Library

**What we did**: `DetectionStep` counts characters in Hangul, Kana, CJK, and Latin Unicode blocks and picks the dominant script — no library. The mobile client runs the identical algorithm and sends a hint; the server re-validates it.

**Why**: The four supported languages have (nearly) disjoint scripts: Hangul is only Korean, Kana is only Japanese, CJK-without-Kana defaults to Chinese. A statistical model like `lingua` (~28MB of models) solves a problem we don't have — distinguishing, say, Spanish from Portuguese. Scope the solution to the actual problem.

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| `lingua` statistical detector | 28MB dependency to answer a question 15 lines of code answers with 100% accuracy for this language set |
| Trust the client's `language` hint alone | Never trust client input for anything that affects server behavior — the server re-detects as validation |
| Detect only server-side | The client would lose the ability to give instant UI feedback before the round trip |

**When you'd choose differently**: The moment you add a second Latin-script language (Spanish, French), script counting collapses — all Latin text looks the same. That's when a statistical detector becomes the right tool. Knowing where your cheap solution breaks is as important as the solution itself.

---

### 3. Tiered Pipeline (DeepL → Dictionary → Claude) Instead of One Claude Call

**What we did**: `AnalysisPipeline` runs five steps in strict order: Detection → Translation (DeepL) → Dictionary lookup → Claude (only for tokens the dictionary couldn't resolve) → local Romanization. Claude receives the already-computed translation and the already-resolved words, and is told to fill in only the gaps.

**Why**: A single Claude call could produce the whole response, and it would be *simpler* — the plan's Complexity Tracking section admits this. But LLM tokens are the most expensive resource in the system, and most of the work (translation, common-word lookup, romanization) is done better or equally well by cheap deterministic tools. The pipeline targets ~75% token reduction by making Claude the last resort, not the first call.

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| Single Claude call for everything | Highest quality-per-call but pays LLM prices for work a free dictionary API does; violates the project's cost-optimization constitution |
| Claude for translation too | DeepL's free tier (500k chars/month) handles it at zero marginal cost; Claude is the fallback only when quota runs out |
| Let Claude generate romanization | Romanization is a deterministic transform — ICU4J/Pinyin4j do it perfectly, locally, for free; asking an LLM invites hallucinated output for solved problems |

**When you'd choose differently**: For a prototype or low-traffic internal tool, the single Claude call is genuinely better — five steps of orchestration, fallbacks, and quota tracking is real maintenance cost. Tiering pays off only when volume makes the per-request savings matter.

---

### 4. Redis for Rate Limiting and Quota — Not In-Process Memory

**What we did**: Bucket4j token buckets (keyed by `userId`) live in Redis, and the DeepL monthly character counter is a Redis key (`deepl:quota:YYYY-MM`).

**Why**: The app is designed to be stateless (Twelve-Factor): any instance can serve any request, and instances can restart or scale out. An in-memory rate-limit map would reset on every restart (free quota for everyone) and each instance would enforce its own separate limit. Shared state has to live outside the process.

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| In-memory `ConcurrentHashMap` buckets | Per-instance and lost on restart — a user hitting two instances gets 2× the limit |
| Rate limiting in an API gateway | Adds a whole gateway service for one feature; unjustified in Phase 1 |
| PostgreSQL counters | Works, but every analyze request would add DB write load for what is ephemeral counting — Redis is built for this |

**When you'd choose differently**: A single-instance app that tolerates limit resets on restart can use in-memory buckets and drop the Redis dependency entirely. Infrastructure should be justified by the deployment model, not added "to be safe."

---

### 5. Steps Fail Soft: partialErrors Instead of Propagating Exceptions

**What we did**: `AnalysisPipeline` wraps each step in try-catch. A failed step (Claude circuit open, dictionary API down) records an entry in `AnalysisContext.partialErrors` and the pipeline continues; `AnalysisService` maps those into per-word `error` fields on the response instead of failing the whole request.

**Why**: The pipeline depends on four external services, and any of them can be down at any moment. If a Krdict outage turned every Korean analysis into a 500, the app would be down whenever any dependency was. Graceful degradation means the user still gets the translation and most word cards, with honest per-word error markers for the rest.

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| Fail the whole request on any step failure | One flaky dependency takes down the core feature; terrible availability math with 4 external services |
| Silently omit failed words | The client can't distinguish "not a meaningful token" from "lookup failed, retry later" — errors should be visible, not hidden |

**When you'd choose differently**: When a partial result is worse than no result — a payment pipeline, for example, must be all-or-nothing. Fail-soft is right when each piece of output has independent value to the user.

---

### 6. Errors as a Contract: AppException + GlobalExceptionHandler

**What we did**: One runtime exception type (`AppException`) carries an HTTP status and error code; a single `@RestControllerAdvice` (`GlobalExceptionHandler`) converts it — and validation failures, and any uncaught exception — into the fixed envelope `{"error":{"code","message","retryable"}}`.

**Why**: The mobile client needs to handle errors programmatically (`retryable: true` → show a retry button; `EMAIL_ALREADY_EXISTS` → highlight the email field). That only works if every error, from every layer, has the same shape. Centralizing the mapping in one advice class also guarantees no stack trace or internal detail ever leaks to the client — a security requirement, not just tidiness.

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| Per-controller try-catch and ad-hoc error bodies | Shape drift is inevitable across dozens of endpoints; every new controller is a new chance to leak internals |
| One exception subclass per error case | Class explosion for what is really just (status, code) data; a field-carrying exception scales better |

**When you'd choose differently**: This pattern is almost always right for APIs with a programmatic client. The main variation is adopting RFC 7807 (`application/problem+json`) instead of a custom envelope when interoperability with third-party consumers matters.

---

### 7. Login Errors Are Deliberately Vague; Registration Errors Are Deliberately Specific

**What we did**: A wrong password and an unknown email both return the same generic `INVALID_CREDENTIALS` 401. But registering an existing email returns an explicit 409 `EMAIL_ALREADY_EXISTS`.

**Why**: On login, telling an attacker "wrong password" (versus "no such user") confirms which emails have accounts — an enumeration vector. On registration, the user *must* be told the email is taken or they can't proceed; that's a usability requirement that accepts a small enumeration trade-off (mitigated in practice by rate limiting).

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| Specific login errors ("wrong password") | Friendlier UX but confirms account existence to attackers |
| Generic registration errors too | The user literally cannot complete the flow without knowing why it failed |

**When you'd choose differently**: High-security products (banking) close the registration side too, by sending a "you already have an account" *email* instead of an API error — the response looks identical either way. That's the pattern when enumeration resistance outweighs flow friction.

---

## Concepts to Know

### Pipeline (Chain of Steps) with a Shared Context Object

**What it is**: Break a multi-stage process into small step classes that each read and mutate one shared context object (`AnalysisContext`), executed in order by an orchestrator (`AnalysisPipeline`). Each step has one job and doesn't know about the others.

**Where we used it**: `analysis/pipeline/AnalysisPipeline.java` runs the five classes in `analysis/step/`, each writing its results into `AnalysisContext`.

**Why it matters**: Steps can be developed, tested, and mocked independently (which is why tasks T030–T034 could run in parallel), and cross-cutting policy — like the try-catch/partialErrors behavior — lives in one place, the orchestrator, instead of being duplicated in every step.

### Servlet Filter Chain Authentication (JwtAuthFilter)

**What it is**: In Spring Security, authentication happens in filters that run *before* your controllers. `JwtAuthFilter` reads the `Bearer` header, validates the JWT, and populates `SecurityContextHolder`; controllers never see a token.

**Where we used it**: `security/JwtAuthFilter.java`, wired in `config/SecurityConfig.java` ahead of `UsernamePasswordAuthenticationFilter`.

**Why it matters**: One subtle rule: the filter *no-ops* on a missing or bad token rather than rejecting — Spring Security's authorization layer issues the 401 later. If the filter rejected directly, public endpoints like `/api/auth/login` would break, because the filter runs on every request.

### Circuit Breaker + Retry (Resilience4j)

**What it is**: Retry re-attempts transient failures (with exponential backoff so you don't hammer a struggling service). A circuit breaker watches the failure rate and, after a threshold (here: 5 failures in 60s), stops calling the dependency at all for a cooldown period — failing fast instead of stacking up timeouts.

**Where we used it**: `@Retry` and `@CircuitBreaker` annotations on the DeepL call in `TranslationStep` and the Claude call in `ClaudeStep`, configured in `config/ResilienceConfig.java`.

**Why it matters**: The two are complementary, not redundant: retry handles a blip; the breaker handles an outage. Retrying into a dead service just multiplies the load on it and ties up your own threads — the breaker is what protects *you* during *their* outage.

### Token Bucket Rate Limiting

**What it is**: Each user gets a bucket holding N tokens that refill at a fixed rate (here 20/minute). Every request consumes one token; an empty bucket means 429. Unlike a rigid "20 per clock-minute" window, it smoothly allows short bursts while capping sustained rate.

**Where we used it**: `config/RateLimitConfig.java` (Bucket4j over Redis), consumed at the top of `AnalysisService` *before* any pipeline work runs.

**Why it matters**: The check runs before the pipeline deliberately — rate limiting exists to protect the expensive downstream calls (Claude, DeepL), so it must gate them, not run alongside them. Note also the 429 sets `retryable: true`: rate limiting is the one "error" that's explicitly a try-again-later.

### LLM Tool Use for Structured Output

**What it is**: Instead of asking the model to "reply in JSON" (and hoping), you define a tool with a strict JSON schema (`analyze_words`) and the model *calls* it — the API enforces that the arguments match the schema.

**Where we used it**: `analysis/step/ClaudeStep.java`, which also varies the schema by language: `particles`/`endings` fields exist only for Korean/Japanese, and `romanization` is excluded entirely because it's computed locally.

**Why it matters**: Parsing free-form model output is a reliability tarpit (markdown fences, trailing commentary, drifting field names). Schema-enforced tool use turns "parse whatever came back" into ordinary deserialization. The conditional schema is the deeper lesson: don't give a model fields it shouldn't fill — absent fields can't be hallucinated.

### Testcontainers and the Test Pyramid

**What it is**: Three test layers with different trade-offs: plain JUnit+Mockito for logic, `@WebMvcTest` (loads only the web layer, mocks services) for request/response contracts, and `@SpringBootTest` + Testcontainers (real PostgreSQL/Redis in throwaway Docker containers) for end-to-end flows.

**Where we used it**: `AuthControllerTest`/`AnalysisControllerTest` are `@WebMvcTest`; `AuthFlowIT`/`AnalysisPipelineIT` run against real containers — but note DeepL and Claude are still mocked even in the ITs.

**Why it matters**: The constitution bans H2 because "in-memory database that mostly acts like PostgreSQL" is where integration bugs hide (CITEXT columns, `NOW()`, transaction semantics). The line drawn here is instructive: infrastructure you own (DB, Redis) is real in tests; paid external APIs are mocked — you want deterministic, free, offline test runs.

---

## Architecture Overview

The code is layered so each concern has exactly one home: controllers only translate HTTP ↔ DTOs, services hold business rules (validation, rate limiting, token issuance), and the analysis pipeline isolates the messy external-service orchestration behind a single `pipeline.run(context)` call. Security (JWT filter) and error shaping (GlobalExceptionHandler) sit as cross-cutting layers that no controller has to think about.

```
request → JwtAuthFilter → Controller → Service ──→ AnalysisPipeline
              (authn)      (HTTP↔DTO)  (rules,        Detection
                                        rate limit)    Translation (DeepL ⇢ Claude fallback)
                                          │            Dictionary  (Krdict/Kuromoji/CEDICT/FreeDict)
                                          │            Claude      (gaps only, tool use)
        GlobalExceptionHandler ←──────────┘            Romanization (local: ICU4J/Pinyin4j)
        (every error → standard envelope)
```

---

## Glossary

| Term | Meaning |
|------|---------|
| Lemma | The dictionary base form of a word (e.g., "running" → "run"); what each WordCard's lookup keys on |
| Token rotation | Issuing a new refresh token and revoking the old one on every refresh, so a stolen token stops working the first time either party uses it |
| Opaque token | A credential that carries no readable data (random UUID) — the server must look it up, which is exactly what makes it revocable |
| CITEXT | A PostgreSQL case-insensitive text column type; enforces FR-015 (email case-insensitivity) in the database instead of application code |
| Circuit open | Breaker state where calls to a failing dependency are skipped entirely and fail fast until a cooldown passes |
| Graceful degradation | Returning the best partial result available (translation + some words + per-word errors) instead of an all-or-nothing failure |
| ISO 639-3 | Three-letter language codes used in the API (`kor`, `jpn`, `cmn`, `eng`) |

---

# Deep Dive: Unicode Range Counting Instead of a Language-Detection Library

**Scope**: Decision 2 above, expanded — the detection algorithm in `analysis/step/DetectionStep.java` (research.md Decision 3)
**Generated**: 2026-07-08

The headline decision was covered above: for a four-language set with (nearly) disjoint scripts, counting characters per Unicode block beats a 28MB statistical model. This section unpacks the smaller decisions *inside* those 15 lines — each one is a place where the code could plausibly have been written differently.

## Key Decisions

### D1. The Classification Order Is the Algorithm

**What we did**: After counting, the checks run in a fixed order: Korean wins ties (`kor >= kana && kor >= cjk && kor >= lat`), then *any* kana at all means Japanese (`if (kana > 0)` — no majority required), then CJK beats Latin for Chinese, then Latin is the default.

**Why**: This order encodes real linguistic facts. A Japanese sentence is typically a *mix* of kanji (which land in the CJK counter) and kana — often more kanji than kana. If Japanese required a kana *majority*, ordinary Japanese sentences would be misclassified as Chinese. So kana works as a presence flag, not a vote: kana appears in Japanese and nowhere else, so even one kana character is decisive. Conversely, CJK characters appear in both Japanese and Chinese, which is why "CJK with zero kana" defaults to Chinese.

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| Pick the script with the highest count, no ordering | Misclassifies most real Japanese text as Chinese, because kanji usually outnumber kana |
| Weight kana higher instead of using presence | Same effect with an extra magic number to tune and explain |

**When you'd choose differently**: If you needed to distinguish languages that *share* a dominant script (Chinese vs. Japanese written entirely in kanji, or any two Latin-script languages), presence flags stop working — there's no exclusive marker character. That's the precise boundary where you graduate to a statistical n-gram detector.

---

### D2. Ties Go to Korean — a Deliberate Loanword Policy

**What we did**: Korean wins with `>=` comparisons, not `>`. A sentence with 5 Hangul characters and 5 Latin characters is classified `kor`.

**Why**: The spec's edge cases call out "Korean sentence with English loanwords" — real learner input like "오늘 meeting 있어요" is common. Latin letters embedded in a CJK-script sentence are almost always loanwords or brand names; the sentence's grammar (and therefore the right dictionary and analysis path) belongs to the CJK language. Biasing ties toward Korean routes mixed input to the more useful pipeline.

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| Strict `>` (Latin wins ties) | A short Korean sentence with one English word could flip to `lat` and get English dictionary lookups for Hangul tokens — useless output |
| Reject mixed-script input as ambiguous | Punishes completely normal learner text; mixed script is the common case, not the error case |

**When you'd choose differently**: If your users were primarily English speakers quoting occasional foreign words ("the word 사랑 means love"), the bias should flip — the *matrix language* assumption depends on who your users are. This is a product decision wearing an algorithm's clothes.

---

### D3. Trust a Valid Client Hint Entirely — Skip Detection

**What we did**: The mobile client runs the same algorithm locally and sends a `language` hint. If the hint is in the whitelist (`kor`, `jpn`, `cmn`, `lat`), `DetectionStep.run()` returns immediately without detecting anything. Only a missing or invalid hint triggers server-side detection.

**Why**: The client ran the identical algorithm on the identical text, so re-running it server-side buys nothing when the hint is well-formed — it's pure wasted work. The whitelist is the actual security boundary: it guarantees the hint can only steer the pipeline to one of four supported, safe code paths, never inject an arbitrary value into dictionary dispatch.

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| Always re-detect and compare with the hint | Doubles the (tiny) work for zero benefit while client and server run the same algorithm |
| Trust any hint string the client sends | Unvalidated client input dispatching `DictionaryStep` — a malformed value would hit the default path or worse |

**When you'd choose differently**: Be honest about the trade-off here: a client that sends a *wrong but valid* hint (says `cmn` for Korean text) is trusted, and the pipeline produces garbage for that request. That's acceptable because the client is first-party and runs the same code. If third-party clients could call `/analyze`, the server should re-detect and treat the hint as advisory only — "validate, don't trust" scales with how much you control the caller. Note the implementation drifted slightly from research.md, which described the server re-detecting "as validation"; the shipped code trusts valid hints outright. Worth knowing when you read the docs versus the code.

---

### D4. Refuse to Guess: `und` Is a 400, Not a Default

**What we did**: If no counter registers a single character (input is all digits, emoji, punctuation), detection returns `und` and the step throws `AppException(400, LANGUAGE_UNDETECTABLE)` — stopping the pipeline before any external call is made.

**Why**: Every downstream step dispatches on language: which dictionary, which romanizer, what Claude's tool schema looks like. Running the pipeline on a guess would spend DeepL quota and Claude tokens producing confidently wrong output. Failing fast at the first step costs nothing and gives the user an actionable message.

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| Default to English on `und` | "🎉🎉 123" would get an English "translation" and empty word cards — garbage dressed as success |
| Let Claude figure it out | Pays the most expensive tier to handle input that has no analyzable content at all |

**When you'd choose differently**: In a UI where blocking is worse than guessing (e.g., live keyboard suggestions), a soft default with low-confidence signaling beats a hard error. APIs that spend money per request should fail fast; UIs that render for free can guess.

---

### D5. Iterate by Code Point, Not by `char`

**What we did**: The loop uses `text.codePointAt(i)` and advances by `Character.charCount(cp)` instead of the idiomatic `for (char c : text.toCharArray())`.

**Why**: Java strings are UTF-16, and characters outside the Basic Multilingual Plane (emoji, rare CJK extension ideographs) are stored as *two* `char`s (a surrogate pair). A `char`-based loop would see each half as a separate garbage value — never matching any range correctly, and in pathological cases double-counting. The TypeScript client has the same issue solved the same way: `for (const ch of text)` iterates code points, where `text[i]` would not.

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| `for (char c : ...)` loop | Silently miscounts any input containing emoji or supplementary-plane characters — exactly the messy input real users paste |
| `text.codePoints()` stream | Equally correct; the explicit loop was chosen to stay line-for-line parallel with the client version — cross-language consistency is itself a maintainability feature here |

**When you'd choose differently**: Never, really — code-point iteration is the correct default whenever you inspect character *values* in Java or JavaScript. The transferable lesson: "iterate a string" and "iterate its characters" are different operations in UTF-16 languages, and the bug only shows up on input your tests probably don't contain.

---

### D6. Which Blocks Made the Cut — and Which Didn't

**What we did**: Korean gets three ranges (Hangul syllables `AC00–D7A3`, Jamo `1100–11FF`, Compatibility Jamo `3130–318F`); Japanese gets Hiragana + Katakana; Chinese gets only the core CJK Unified Ideographs block (`4E00–9FFF`); Latin gets ASCII `A–Z`/`a–z` only.

**Why**: These blocks cover essentially all text a learner will paste, and every added range is another line to keep in sync between the Java and TypeScript copies. The core CJK block alone covers ~99% of characters in running Chinese and Japanese text; accented Latin letters are unnecessary because plain ASCII letters always co-occur with them in real sentences and the counter only needs *enough* signal, not *all* of it.

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| `Character.UnicodeScript.of(cp)` (Java built-in script lookup) | Correct and comprehensive, but has no TypeScript twin — the client would need a different implementation, breaking the "identical algorithm on both sides" guarantee |
| Exhaustive ranges (CJK Extensions A–G, half-width katakana `FF66–FF9D`, Latin-1 Supplement…) | More lines, more sync burden, negligible accuracy gain on real input |

**When you'd choose differently**: Known blind spots exist — half-width katakana (common in older Japanese data) isn't counted, so a string of *only* half-width katakana would come back `und`. That's the accepted cost. If a bug report ever shows real users hitting it, adding one range is a two-line fix on each side. Deliberately shipping known-incomplete coverage with a cheap extension path beats speculatively covering everything.

## Concepts to Know

### Script Detection vs. Language Detection

**What it is**: A *script* is a writing system (Hangul, Kana, Han, Latin); a *language* is what's being said. This code detects scripts and maps them to languages, which only works because the four target languages happen to use (nearly) mutually exclusive scripts.

**Where we used it**: The internal code `lat` (a script name) versus the API's `eng` (a language name) exposes the seam — the detector genuinely cannot tell English from French; the *product* decision that Latin script means English is what closes the gap.

**Why it matters**: Knowing which problem you're solving is what made the 15-line solution valid. The moment the language set breaks the one-script-one-language mapping, the whole approach — not just a range or two — is invalidated.

### One Algorithm, Two Codebases

**What it is**: The exact same block-counting logic exists in TypeScript (`mobile/src/utils/detectScript.ts`) and Java (`DetectionStep.detectScript`), kept structurally line-for-line parallel so a change to one can be mirrored by eye.

**Where we used it**: It's why D3's "trust the valid hint" is safe, and why D6 rejected Java-only conveniences like `Character.UnicodeScript`.

**Why it matters**: Duplicated logic across languages can't be deduplicated by extraction — the only tools left are *structural parallelism* (make drift visible in review) and shared test vectors. If this algorithm starts changing often, the right move is a shared test-case file (JSON of input → expected code) both suites consume.

### Fail Fast at the Cheapest Tier

**What it is**: Ordering a pipeline so validation and free computation happen before metered external calls — detection is step one precisely because it's free and everything after it costs money.

**Where we used it**: `DetectionStep` throwing `LANGUAGE_UNDETECTABLE` before `TranslationStep` ever touches DeepL quota; the same principle puts the rate-limit check in `AnalysisService` before the pipeline runs at all.

**Why it matters**: In cost-tiered systems, *where* an error is caught has a price tag. The general habit: sort your pipeline's failure checks by cost of reaching them, cheapest first.

## Glossary (Deep-Dive Additions)

| Term | Meaning |
|------|---------|
| Code point | The numeric identity of one Unicode character (e.g., 가 = U+AC00), independent of how it's stored in bytes |
| Surrogate pair | Two UTF-16 `char` units encoding one code point above U+FFFF; why the loop uses `charCount` to advance |
| Unicode block | A named contiguous range of code points (e.g., Hiragana = U+3040–309F); the unit this detector counts by |
| BMP | Basic Multilingual Plane — code points up to U+FFFF that fit in one `char`; everything else needs a surrogate pair |
| `und` | ISO 639-3's official code for "undetermined language"; used here as the refuse-to-guess signal |
| Matrix language | The language providing a mixed sentence's grammar; the reason ties go to Korean over embedded English loanwords |
