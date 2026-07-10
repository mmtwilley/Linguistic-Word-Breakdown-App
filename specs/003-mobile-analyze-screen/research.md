# Research: Mobile App Skeleton with Analyze Screen

**Feature**: 003-mobile-analyze-screen | **Date**: 2026-07-08

All Technical Context unknowns resolved below. Each decision records what was chosen, why, and what was rejected.

## Decision 1: Expo managed workflow (not bare React Native CLI)

**Decision**: Bootstrap `mobile/` with Expo (managed workflow, TypeScript template), developed against Expo Go on the Android emulator for this phase.

**Rationale**:
- The dev machine is Windows with acceptance on an Android emulator (clarification 2026-07-08). Expo removes the most failure-prone part of RN on Windows: hand-maintaining the Gradle/native toolchain. `npx expo start` + Expo Go gives a working dev loop in minutes.
- `expo-secure-store` provides Keychain/Keystore-backed storage (FR-002) with zero native configuration.
- Everything this phase needs (navigation, secure storage, fetch) runs inside Expo Go — no development build required yet.
- Phase 3 (share-menu) needs native share-intent handling. The maintained path is `expo-share-intent` via a config plugin + development build — supported in the managed workflow, so choosing Expo now does not paint us into a corner. (Note: `react-native-share-menu`, named in early project notes, is unmaintained and broken on recent RN; Phase 3 should plan for `expo-share-intent` instead.)

**Alternatives considered**:
- **Bare React Native CLI**: full native control, but slow setup on Windows, manual Keystore integration, and no benefit for this phase's requirements. Rejected as unnecessary complexity (Principle V).
- **Ignite or similar boilerplate**: ships Redux/MobX, its own folder dogma, and dependencies this phase doesn't need. Rejected — YAGNI.

## Decision 2: React Navigation v7 (explicit navigators, not Expo Router)

**Decision**: `@react-navigation/native` with a native-stack for the auth flow and bottom-tabs for the main shell; the root navigator switches between them based on session state.

**Rationale**:
- The conditional "auth stack vs. app tabs" switch is the canonical React Navigation auth pattern, documented extensively — the right learning material for this project's explicit-over-magic style.
- The app has exactly two navigator layers; file-based routing buys nothing at this scale.
- Expo Router is built on React Navigation anyway; migrating later (if the app grows many screens) is mechanical.

**Alternatives considered**:
- **Expo Router**: file-based routing, great for large route trees; adds a layer of convention/indirection over a 6-screen app and makes the auth-guard logic less explicit. Rejected for this phase.

## Decision 3: Token storage & refresh strategy

**Decision**: Store `accessToken` + `refreshToken` in `expo-secure-store`. In-memory session state lives in an `AuthContext`. The authed fetch layer attaches the access token, and on a 401 performs a **single-flight** call to `POST /api/auth/refresh` (one refresh at a time; concurrent 401s await the same promise), persists the rotated pair, and retries the original request once. If refresh fails (`INVALID_REFRESH_TOKEN`), the app stashes the analyze screen's draft text + language override in memory (clarification 2026-07-08; see data-model.md `draft`), clears the session, and the root navigator falls back to the auth stack; after re-login the draft is restored, satisfying FR-004.

**Rationale**:
- Matches the backend contract exactly: `AuthResponse { accessToken, refreshToken, expiresIn }` with rotation on `/refresh` (constitution Principle II: ≤15-min access tokens, rotated refresh tokens in secure storage).
- Single-flight refresh prevents rotation races: with token rotation, two concurrent refreshes would invalidate each other.
- Reactive refresh (on 401) is simpler than proactive timers and sufficient for a single-user client; `expiresIn` is stored so a proactive check can be added later without contract changes.

**Alternatives considered**:
- **AsyncStorage for tokens**: violates FR-002 and constitution Principle II (plain-text, non-encrypted). Rejected outright.
- **react-native-keychain**: bare-workflow equivalent; redundant under Expo.
- **Proactive refresh timer**: more moving parts (app-state listeners, clock skew) for no user-visible gain this phase. Deferred.

## Decision 4: Hand-rolled typed fetch client (no axios, no TanStack Query)

**Decision**: A small typed wrapper over the built-in `fetch`: injects `EXPO_PUBLIC_API_URL`, sets JSON headers, parses success bodies to typed results, and parses non-2xx bodies into a typed `ErrorEnvelope` discriminated by `code`. `api/auth.ts` and `api/analyze.ts` expose one function per endpoint.

**Rationale**:
- Four endpoints, one screen. `fetch` is built into RN/Hermes; axios adds a dependency for interceptors we can express in ~30 lines.
- The envelope contract (`{"error":{code,message,retryable}}`) is stable and machine-mapped (FR-013); a typed parser at the boundary means every screen receives either typed data or a typed error — raw payloads can never leak to the UI (SC-004).
- Network failures (no connectivity, timeout via `AbortController`) are normalized into the same error shape with a synthetic `NETWORK_ERROR` code, `retryable: true` (FR-014).

**Alternatives considered**:
- **axios**: interceptor ergonomics, but a dependency doing nothing fetch can't. Rejected (Principle V).
- **TanStack Query**: caching/retry machinery valuable once History/Flashcards exist; for one imperative submit-and-render flow it's premature. Deferred to a later phase.

## Decision 5: State management — React Context + local state only

**Decision**: `AuthContext` (session, sign-in/out, bootstrap-from-secure-store) is the only global state. The analyze screen holds its own state machine locally: `idle → submitting → success | error`, with the language override kept in screen state (persists for the session per FR-006a).

**Rationale**: One piece of genuinely global state (the session) doesn't justify a store library. The constitution's YAGNI principle is explicit.

**Alternatives considered**: Redux Toolkit / Zustand / Jotai — all rejected as premature for one context and one screen.

## Decision 6: Canonical shared types live in `mobile/src/api/types.ts`

**Decision**: Define `AnalysisResult`, `WordCard`, `Confidence`, `ValidationIssue`, `IssueCode`, `ErrorEnvelope`, and the auth DTOs in one module mirroring the backend wire contract exactly (documented in [contracts/backend-api.md](contracts/backend-api.md)). Wire enums are string-literal unions (`"high" | "medium" | "low"`; issue codes as SCREAMING_SNAKE literals; severity `"warning" | "error"`).

**Rationale**:
- The constitution names the `WordCard`/`AnalysisResult` TS types the canonical mobile↔backend contract; this module is that contract.
- String-literal unions make invalid states unrepresentable at compile time while matching the JSON byte-for-byte (backend uses `@JsonValue` lowercase for confidence, enum names for issue codes, `@JsonProperty` lowercase for severity).
- Contract tests parse fixture responses (from the 002 contract table) with these types to detect drift.

**Alternatives considered**:
- **OpenAPI codegen from the backend**: backend has no OpenAPI spec today; generating one is backend work out of scope for this feature. Reconsider when endpoint count grows.
- **Shared npm package for types**: two consumers don't exist yet (the backend is Java). Rejected — YAGNI.

## Decision 7: Testing stack — jest-expo + React Native Testing Library

**Decision**: Unit/component tests with Jest (`jest-expo` preset) and RNTL. The fetch boundary is mocked with typed fixtures; secure-store is mocked with an in-memory implementation. Contract-shape tests assert the fixtures from the 002 validation contract (kor medium+order, jpn/eng high, cmn low+empty, eng low+coverage) type-check and render: confidence badge, flagged cards, empty-analysis state.

**Rationale**: Matches the project's existing test discipline (backend has 95 unit + ITs). RNTL tests behavior the acceptance scenarios describe (states, messages, ordering) rather than implementation detail. E2E (Detox/Maestro) is deliberately out of scope — acceptance runs manually on the emulator per the spec's Android-only clarification.

**Alternatives considered**: Detox/Maestro E2E — real device automation is heavy setup on Windows for six screens; manual acceptance + component tests cover this phase. Deferred.

## Decision 8: Dev connectivity & environment config

**Decision**: Backend base URL comes from `EXPO_PUBLIC_API_URL` (`.env` local, documented in `mobile/.env.example`). For the Android emulator against a local backend: `http://10.0.2.2:8080` (the emulator's alias for the host's localhost). The client validates at startup that a non-local URL uses `https://` and refuses otherwise.

**Rationale**:
- Twelve-factor config (Principle III) applied to the client: no hardcoded URLs.
- Backend HTTPS enforcement (001/T045) applies to non-local environments; the emulator loopback is a local environment. Expo Go's Android dev client permits cleartext HTTP in development, so no manifest changes are needed this phase; a production build would use HTTPS only.
- The `https://` startup check keeps the constitution's transport-security rule enforced from the client side too.

**Alternatives considered**: `adb reverse tcp:8080 tcp:8080` + `http://localhost:8080` — works, but an extra manual step per emulator boot; documented in quickstart as a fallback.

## Decision 9: Issue-code and error-code presentation

**Decision**: A single `messages.ts` map translates the eight `IssueCode` values and known envelope `code`s (`VALIDATION_ERROR`, `INVALID_INPUT`, `LANGUAGE_UNDETECTABLE`, `RATE_LIMIT_EXCEEDED`, `UNAUTHORIZED`, `INVALID_CREDENTIALS`, `EMAIL_ALREADY_EXISTS`, `INVALID_REFRESH_TOKEN`, `INTERNAL_ERROR`, synthetic `NETWORK_ERROR`) into learner-friendly strings. Unknown codes fall back to a generic message plus the envelope's own `message` field (which the backend guarantees display-safe). Codes are never rendered verbatim (FR-010, SC-004).

**Rationale**: The backend's `IssueCode` javadoc pins these as stable, append-only wire values — a client-side map won't silently break; new codes degrade to the generic fallback. Result-level issues (`EMPTY_ANALYSIS`, `STAGE_FAILED`, `INPUT_NOT_COVERED`, `CARDS_OUT_OF_ORDER`) render on the result header; card-level issues (`MISSING_FIELD`, `ROMANIZATION_PASSTHROUGH`, `UNKNOWN_POS`, `AI_ENTRY_REJECTED`) attach to their card via the issue's `surface` field.

**Alternatives considered**: Server-supplied display strings — the backend `detail` field is diagnostic, not learner-facing; localizing on the client keeps the backend contract stable. Chosen approach also leaves room for UI localization later.
