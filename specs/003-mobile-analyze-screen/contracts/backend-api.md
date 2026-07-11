# Contract: Backend API consumed by the mobile app

**Feature**: 003-mobile-analyze-screen | **Date**: 2026-07-08
**Status**: FIXED — this documents the existing backend (features 001/002). The mobile app conforms to it; no backend changes are in scope. Verified against the DTO source on branch `003-mobile-analyze-screen` (backend `com.lingua_app.backend.dto.*`, `analysis.pipeline.Confidence`, `analysis.pipeline.IssueCode`).

Base URL: `EXPO_PUBLIC_API_URL` (local dev: `http://10.0.2.2:8080` from the Android emulator). All bodies are JSON. All errors use the envelope in §4.

## 1. Auth endpoints (`/api/auth`, unauthenticated)

### POST /api/auth/register
Request `{ "email": "<valid email>", "password": "<8–128 chars>" }` → **201** `{ "message": "Account created successfully." }` — **no tokens**.
Errors: `409 EMAIL_ALREADY_EXISTS`, `400 VALIDATION_ERROR` (bad email / password length).

> **Correction 2026-07-11** (verified live during US2 manual test): register does NOT return `AuthResponse` as this contract originally claimed — only login issues a token pair. The mobile RegisterScreen therefore performs a follow-up `POST /api/auth/login` with the same credentials to establish the session.

### POST /api/auth/login
Request `{ "email": "...", "password": "..." }` → **200** `AuthResponse`.
Errors: `401 INVALID_CREDENTIALS` (same message for unknown email vs wrong password).

### POST /api/auth/refresh
Request `{ "refreshToken": "..." }` → **200** `AuthResponse` (**rotation**: both tokens replaced; the submitted refresh token is invalidated).
Errors: `401 INVALID_REFRESH_TOKEN` → client must clear the session and return to sign-in (FR-004).

### AuthResponse
```json
{ "accessToken": "<JWT>", "refreshToken": "<opaque>", "expiresIn": 900 }
```
`expiresIn` is the access-token lifetime in seconds (≤15 min per constitution).

## 2. Analysis endpoint (authenticated)

### POST /api/analyze
Headers: `Authorization: Bearer <accessToken>`.

Request:
```json
{ "text": "<1–500 chars, required>", "language": "kor" }
```
`language` is optional; valid hints are exactly `"kor" | "jpn" | "cmn" | "lat"` (`lat` = Latin-script/English). Omit for auto-detection. Invalid hints are ignored (detection runs instead).

Success **200** `AnalysisResult`:
```json
{
  "language": "kor",
  "translation": "I ate lunch.",
  "words": [
    {
      "surface": "점심을",
      "lemma": "점심",
      "pos": "noun",
      "gloss": "lunch",
      "romanization": "jeomsim-eul",
      "ipa": null
    }
  ],
  "confidence": "medium",
  "issues": [
    { "code": "CARDS_OUT_OF_ORDER", "severity": "warning", "surface": null, "detail": "..." }
  ]
}
```

Field notes:
- `words` is ordered as the words appear in the input; the client MUST NOT re-sort (FR-008).
- Every `WordCard` field except `surface` is nullable.
- `confidence` wire values: `"high" | "medium" | "low"` (lowercase).
- `issues[].severity` wire values: `"warning" | "error"` (lowercase).
- `issues[].surface` non-null ⇒ the issue belongs to the card(s) with that surface; null ⇒ result-level.
- `issues[].detail` is diagnostic text — not for display.

Errors: `400 INVALID_INPUT` ("Text must be between 1 and 500 characters."), `400 LANGUAGE_UNDETECTABLE`, `401 UNAUTHORIZED` (missing/expired token → trigger refresh flow), `429 RATE_LIMIT_EXCEEDED` (`retryable: true`).

## 3. IssueCode registry (stable, append-only)

| Code | Level | Transient | Meaning (client display owns wording) |
|---|---|---|---|
| `EMPTY_ANALYSIS` | result | no | No word cards could be produced |
| `INPUT_NOT_COVERED` | result | no | Some input words have no card |
| `CARDS_OUT_OF_ORDER` | result | no | Card order didn't match input order |
| `MISSING_FIELD` | card | no | A card lacks lemma/gloss/etc. |
| `ROMANIZATION_PASSTHROUGH` | card | no | Romanization may be untrustworthy |
| `UNKNOWN_POS` | card | no | Part of speech couldn't be normalized |
| `AI_ENTRY_REJECTED` | card | no | An AI-produced entry was rejected by validation |
| `STAGE_FAILED` | result | **yes** | A pipeline stage failed; retry may improve the result |

Unknown future codes MUST degrade to a generic warning (never crash, never show the raw code).

Contract fixtures for tests: the response table in [specs/002-analysis-validation/contracts/validation-api.md](../../002-analysis-validation/contracts/validation-api.md) (kor medium + order issue; jpn/eng high; cmn low + `EMPTY_ANALYSIS`; eng low + coverage).

## 4. Error envelope (all non-2xx responses)

```json
{ "error": { "code": "SNAKE_CASE_CODE", "message": "Display-safe text", "retryable": false } }
```

Known codes and client handling (FR-013):

| Code | HTTP | Client behavior |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Inline form/input message |
| `INVALID_INPUT` | 400 | Inline input message (length) |
| `LANGUAGE_UNDETECTABLE` | 400 | Suggest the language override picker |
| `INVALID_CREDENTIALS` | 401 | Auth form error |
| `EMAIL_ALREADY_EXISTS` | 409 | Register form error, offer sign-in |
| `UNAUTHORIZED` | 401 | Trigger silent refresh → retry once → else re-auth prompt |
| `INVALID_REFRESH_TOKEN` | 401 | Clear session, return to sign-in |
| `RATE_LIMIT_EXCEEDED` | 429 | "Too many requests — wait a moment" + retry affordance |
| `INTERNAL_ERROR` | 500 | Generic retriable message |
| `NETWORK_ERROR` | — | **Client-synthesized** for fetch failure/timeout; retryable |

## 5. Canonical TypeScript contract (`mobile/src/api/types.ts`)

Per the constitution, these TS types are the canonical mobile↔backend contract; changes require simultaneous updates on both sides.

```typescript
export type Confidence = "high" | "medium" | "low";

export type IssueCode =
  | "EMPTY_ANALYSIS"
  | "INPUT_NOT_COVERED"
  | "CARDS_OUT_OF_ORDER"
  | "MISSING_FIELD"
  | "ROMANIZATION_PASSTHROUGH"
  | "UNKNOWN_POS"
  | "AI_ENTRY_REJECTED"
  | "STAGE_FAILED"
  | (string & {}); // forward-compat: unknown codes degrade gracefully

export interface ValidationIssue {
  code: IssueCode;
  severity: "warning" | "error";
  surface: string | null;
  detail: string | null;
}

export interface WordCard {
  surface: string;
  lemma: string | null;
  pos: string | null;
  gloss: string | null;
  romanization: string | null;
  ipa: string | null;
}

export interface AnalysisResult {
  language: string;
  translation: string | null;
  words: WordCard[];
  confidence: Confidence;
  issues: ValidationIssue[];
}

export type LanguageHint = "kor" | "jpn" | "cmn" | "lat";

export interface AnalysisRequest {
  text: string;            // 1–500 chars
  language?: LanguageHint; // omit for auto-detect
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number; // seconds
}

export interface ErrorEnvelope {
  error: { code: string; message: string; retryable: boolean };
}
```
