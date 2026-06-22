# Feature Specification: Backend Authentication & Text Analysis API

**Feature Branch**: `001-backend-auth-analyze`
**Created**: 2026-06-19
**Status**: Draft
**Input**: Phase 1 of Lingua Mobile — secure user accounts and word-level text analysis endpoint

## User Scenarios & Testing

### User Story 1 — Account Registration (Priority: P1)

A new visitor to the Lingua Mobile app wants to create an account so they can save vocabulary and track their learning progress. They provide an email address and password and receive confirmation that their account is active.

**Why this priority**: All other features require an authenticated user. No analysis, flashcard, or history data is meaningful without identity. This is the foundation everything else depends on.

**Independent Test**: Register a new account via the API, confirm a success response is returned, then attempt to log in with the same credentials and receive a valid session credential.

**Acceptance Scenarios**:

1. **Given** no account exists for an email, **When** a user submits a valid email and password, **Then** the system creates an account and returns a success confirmation.
2. **Given** an account already exists for an email, **When** a user attempts to register with that same email, **Then** the system rejects the request with a clear error indicating the email is already in use.
3. **Given** a registration attempt with an invalid email format or a password that is too short, **When** the request is submitted, **Then** the system rejects it with a descriptive validation error.
4. **Given** a successful registration, **When** the user attempts to log in with those credentials, **Then** they receive a valid session credential.

---

### User Story 2 — Login and Session Management (Priority: P1)

A returning user opens Lingua Mobile and logs in with their credentials. They receive a session credential that lets them make authenticated requests. When the credential expires, they can refresh it without re-entering their password.

**Why this priority**: Required before any protected endpoint (including `/analyze`) can be exercised. Paired with registration as the dual P1 auth story.

**Independent Test**: Log in with valid credentials, receive a session credential, use it to call a protected endpoint, and confirm success. Then use an expired or invalid credential and confirm rejection.

**Acceptance Scenarios**:

1. **Given** a registered account, **When** the user submits correct credentials, **Then** they receive a short-lived access credential and a longer-lived refresh credential.
2. **Given** correct login, **When** the access credential expires, **Then** presenting the refresh credential issues a new access credential without re-entering the password.
3. **Given** incorrect credentials, **When** a login attempt is made, **Then** the system rejects it with a generic "invalid credentials" message (no hint as to which field was wrong).
4. **Given** a revoked or expired refresh credential, **When** a refresh is attempted, **Then** the system rejects it and requires re-login.

---

### User Story 3 — Text Analysis for Authenticated Users (Priority: P2)

An authenticated user pastes a sentence in Korean, Japanese, Chinese, or English into Lingua Mobile. The system identifies the language automatically, returns a full English translation, and breaks the sentence into individual word cards — each with the dictionary form, part of speech, English gloss, and romanization for non-Latin scripts.

**Why this priority**: Core value proposition of the app. Depends on P1 auth being complete, but is independently testable once auth works.

**Independent Test**: Submit a short Korean sentence with a valid session credential. Confirm the response contains: correct language detection, a full English translation, and one WordCard per meaningful token with non-empty lemma, POS, and gloss fields.

**Acceptance Scenarios**:

1. **Given** a valid session credential and a Korean sentence, **When** the user submits text for analysis, **Then** the response contains language `"kor"`, an English translation, and a WordCard for each token with `surface`, `lemma`, `pos`, `gloss`, and `romanization` populated.
2. **Given** a valid session credential and an English sentence, **When** the user submits text for analysis, **Then** the response contains language `"eng"`, an English translation (identity or paraphrase), and a WordCard per token with `surface`, `lemma`, `pos`, and `gloss` (no romanization required for Latin script).
3. **Given** a valid session credential and a Japanese or Chinese sentence, **When** the user submits text for analysis, **Then** the response contains the correct language, translation, and WordCards with romanization (romaji or pinyin respectively).
4. **Given** an unauthenticated request (missing or invalid credential), **When** the `/analyze` endpoint is called, **Then** the system returns a 401 error using the standard error envelope.
5. **Given** a valid credential but an empty or whitespace-only text body, **When** the request is submitted, **Then** the system returns a 400 error with a descriptive message.
6. **Given** a valid credential and text that exceeds the maximum allowed length, **When** the request is submitted, **Then** the system rejects it with a 400 error indicating the length limit.

---

### Edge Cases

- What happens when the language cannot be detected (very short text, symbols only, mixed scripts)?
- How does the system handle text containing a mix of two languages (e.g., Korean sentence with English loanwords)?
- What happens when the analysis service (AI or dictionary) is temporarily unavailable — does the user receive a partial result or a clean error?
- What happens when a user submits the same text repeatedly in rapid succession — is the rate limiter triggered?
- What happens when a user's account is registered but immediately followed by a login with an uppercase variant of their email?

## Requirements

### Functional Requirements

- **FR-001**: The system MUST allow new users to register with a unique email address and a password meeting minimum security standards (at least 8 characters).
- **FR-002**: The system MUST reject registration attempts for email addresses already associated with an existing account.
- **FR-003**: The system MUST validate email format and password strength at registration time and return specific validation errors.
- **FR-004**: The system MUST allow registered users to authenticate with email and password and receive a short-lived access credential and a longer-lived refresh credential upon success.
- **FR-005**: The system MUST allow holders of a valid refresh credential to obtain a new access credential without re-submitting their password.
- **FR-006**: The system MUST reject authentication requests with incorrect credentials using a generic error message that does not reveal which field was wrong.
- **FR-007**: The system MUST protect the `/analyze` endpoint — unauthenticated requests MUST be rejected with a 401 response using the standard error envelope.
- **FR-008**: The system MUST accept text input on the `/analyze` endpoint and automatically detect the language of the submitted text.
- **FR-009**: For any submitted text, the system MUST return a full English translation.
- **FR-010**: For any submitted text, the system MUST return a list of WordCards, one per meaningful token, each containing: surface form, dictionary lemma, part of speech, and English gloss.
- **FR-011**: For tokens in non-Latin scripts (Korean, Japanese, Chinese), each WordCard MUST include a romanization field.
- **FR-012**: The system MUST reject text analysis requests with empty, whitespace-only, or oversized input with a 400 error.
- **FR-013**: All error responses MUST conform to the standard structured error envelope: `{ "error": { "code": "...", "message": "...", "retryable": true|false } }`.
- **FR-014**: The system MUST enforce per-user rate limiting on the `/analyze` endpoint and return a 429 response with `retryable: true` when the limit is exceeded.
- **FR-015**: Email addresses MUST be treated case-insensitively for login and uniqueness checks.

### Key Entities

- **User**: Represents a registered account. Key attributes: unique email (case-insensitive), hashed password, account creation timestamp, active/inactive status.
- **WordCard**: Represents one analyzed token. Attributes: `surface` (as it appears in text), `lemma` (dictionary base form), `pos` (part of speech), `gloss` (English meaning), `romanization` (optional, for non-Latin scripts), `ipa` (optional).
- **AnalysisResult**: The complete response for a submitted text. Attributes: `translation` (full English translation), `words` (ordered list of WordCards), `language` (detected ISO 639-3 code).
- **Session**: Tracks issued access and refresh credentials. Attributes: user reference, issued-at, expiry, revocation status.

## Success Criteria

### Measurable Outcomes

- **SC-001**: A new user can complete registration and receive a valid session credential in under 3 seconds under normal conditions.
- **SC-002**: Login with valid credentials succeeds and delivers a usable access credential in under 2 seconds.
- **SC-003**: A 10-word sentence in any supported language (Korean, Japanese, Chinese, English) is analyzed and a full WordCard list is returned in under 10 seconds.
- **SC-004**: All unauthenticated requests to protected endpoints are rejected — 0% unauthorized access to `/analyze` data.
- **SC-005**: All error responses from auth and analysis endpoints conform to the standard envelope structure — no raw exception messages exposed to callers.
- **SC-006**: Rate limiting correctly throttles users who exceed the per-user request cap — 100% of over-limit requests receive a 429 with `retryable: true`.

## Assumptions

- The mobile client handles secure storage of session credentials; the backend is not responsible for client-side credential security.
- "Supported languages" for Phase 1 are: Korean, Japanese, Chinese (Simplified), and English. Other languages are out of scope for this phase.
- Maximum text input length for analysis is 500 characters per request.
- Password minimum length is 8 characters; maximum 128 characters. No complexity rules beyond minimum length for Phase 1.
- The rate limit for `/analyze` is per authenticated user (not per IP). The specific cap (e.g., 20 requests/minute) is a configuration value, not hardcoded.
- Analysis results are not persisted in Phase 1 — they are computed and returned in the response only. Persistence is a Phase 4 concern.
- The refresh credential has a 30-day lifetime; access credentials expire in 15 minutes. These are configurable values.
- Email addresses are normalized to lowercase before storage and comparison.
