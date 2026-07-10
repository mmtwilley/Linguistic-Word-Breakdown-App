# Data Model: Mobile App Skeleton with Analyze Screen

**Feature**: 003-mobile-analyze-screen | **Date**: 2026-07-08

The mobile app holds no database. Its data model is (a) the wire types mirrored from the backend (canonical definitions in [contracts/backend-api.md](contracts/backend-api.md)) and (b) client-side session/screen state. All types live in `mobile/src/api/types.ts` unless noted.

## Wire entities (mirror of backend contract — do not reinterpret)

### AnalysisResult
| Field | Type | Notes |
|---|---|---|
| `language` | `string` | Detected (or hinted) language code from the pipeline |
| `translation` | `string \| null` | Whole-input translation |
| `words` | `WordCard[]` | Ordered as words appear in the input (FR-008); may be empty |
| `confidence` | `Confidence` | `"high" \| "medium" \| "low"` |
| `issues` | `ValidationIssue[]` | Result- and card-level warnings; empty when clean |

### WordCard
| Field | Type | Notes |
|---|---|---|
| `surface` | `string` | Required; the word as it appears in the input |
| `lemma` | `string \| null` | Dictionary form |
| `pos` | `string \| null` | Normalized part of speech |
| `gloss` | `string \| null` | Meaning |
| `romanization` | `string \| null` | Present for kor/jpn/cmn; omit UI field when null (US1-AS3) |
| `ipa` | `string \| null` | Not rendered this phase (no reliable data yet) |

### ValidationIssue
| Field | Type | Notes |
|---|---|---|
| `code` | `IssueCode` | Stable SCREAMING_SNAKE literal; append-only on the backend |
| `severity` | `"warning" \| "error"` | Wire-lowercase |
| `surface` | `string \| null` | Non-null ⇒ card-level issue, attach to the matching card; null ⇒ result-level |
| `detail` | `string \| null` | Diagnostic text — never rendered to the user (messages.ts owns display strings) |

**IssueCode display routing** (derived attribute, computed client-side):
- Result-level: `EMPTY_ANALYSIS`, `INPUT_NOT_COVERED`, `CARDS_OUT_OF_ORDER`, `STAGE_FAILED`
- Card-level (via `surface` match): `MISSING_FIELD`, `ROMANIZATION_PASSTHROUGH`, `UNKNOWN_POS`, `AI_ENTRY_REJECTED`
- Unknown/future codes: render generic fallback at result level.

### ErrorEnvelope
| Field | Type | Notes |
|---|---|---|
| `error.code` | `string` | Known codes enumerated in contracts/backend-api.md; client adds synthetic `NETWORK_ERROR` |
| `error.message` | `string` | Backend-guaranteed display-safe; used as fallback text |
| `error.retryable` | `boolean` | Drives the retry affordance (FR-013) |

### Auth wire types
- `RegisterRequest` / `LoginRequest`: `{ email: string, password: string }` (register: valid email, password 8–128 chars — mirror client-side, FR-015 analog for auth forms)
- `RefreshRequest`: `{ refreshToken: string }`
- `AuthResponse`: `{ accessToken: string, refreshToken: string, expiresIn: number }` (seconds)

## Client-side state

### Session (`AuthContext` + `expo-secure-store`)
| Field | Where | Notes |
|---|---|---|
| `accessToken` | secure store + memory | Attached as `Authorization: Bearer` on protected calls |
| `refreshToken` | secure store + memory | Rotated on every `/refresh`; both tokens replaced atomically |
| `status` | memory | `"bootstrapping" \| "signedOut" \| "signedIn"` |

**Lifecycle / state transitions**:
```
app launch ──▶ bootstrapping ──(tokens found)──▶ signedIn
                    │ (no tokens)                    │
                    ▼                                │ sign-out (FR-005) / refresh failure (FR-004)
                signedOut ◀──────────────────────────┘
                    │ login/register success (US2)
                    ▼
                signedIn
```
Invariants: tokens exist in secure store ⇔ status may be `signedIn`; sign-out and refresh-failure both clear the store before flipping status (FR-005, FR-018 — tokens never logged).

### AnalyzeScreenState (local to HomeScreen)
| Field | Type | Notes |
|---|---|---|
| `text` | `string` | Input; submit disabled when blank or >500 chars (FR-015) |
| `languageOverride` | `"auto" \| "kor" \| "jpn" \| "cmn" \| "lat"` | Default `"auto"` (sends no hint); persists across submissions within the running session only — resets to `"auto"` on each app launch (FR-006a, clarification 2026-07-08) |
| `draft` | in-memory stash (module scope or context) | On refresh-failure sign-out, `text` + `languageOverride` are stashed and restored into HomeScreen after re-login (FR-004); never written to secure store or disk; cleared on explicit sign-out |
| `request` | `Idle \| Submitting \| Success(AnalysisResult) \| Failed(ErrorEnvelope)` | Discriminated union; exactly one active |

**Transitions**: `Idle → Submitting` (on submit; duplicate submits blocked, FR-012) → `Success` or `Failed`; any terminal state `→ Submitting` on resubmit (previous result replaced, US1-AS4). Navigating away during `Submitting` abandons the request via `AbortController` without corrupting later requests (edge case list).

### Derived display values
- `ConfidenceBadge` variant ← `AnalysisResult.confidence` (FR-009; `high` renders unobtrusively, US3-AS1)
- `cardWarnings: Map<surface, ValidationIssue[]>` ← card-level issues grouped by `surface` (FR-010)
- `resultNotices: ValidationIssue[]` ← result-level issues (FR-011 empty-analysis explanation)

## Validation rules (client-side mirrors)

| Rule | Source | Enforcement point |
|---|---|---|
| Text non-blank, ≤500 chars | `AnalysisRequest` `@NotBlank @Size(max=500)` | Submit button disabled + inline counter (FR-015) |
| Language hint ∈ {kor, jpn, cmn, lat} or absent | `DetectionStep.VALID_HINTS` | Picker offers only these values + auto |
| Email format, password 8–128 | `RegisterRequest` | Register form inline validation |
| Card order preserved | FR-008 | Render `words` array as-is; never sort client-side |
