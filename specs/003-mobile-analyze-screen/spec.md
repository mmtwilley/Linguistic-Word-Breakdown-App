# Feature Specification: Mobile App Skeleton with Analyze Screen

**Feature Branch**: `003-mobile-analyze-screen`
**Created**: 2026-07-08
**Status**: Draft
**Input**: User description: "React Native mobile app skeleton with analyze screen (Phase 2 of Lingua Mobile build order). Set up the mobile/ React Native + TypeScript project: navigation shell for the planned screens (Home, Flashcards, History, Settings — only Home functional for now), a typed API client for the Spring Boot backend, and JWT auth flow (register/login screens, secure token storage, authenticated requests). The core deliverable is the analyze screen: user enters or pastes text, the app calls POST /api/analyze, and displays the translation plus per-word cards (surface, lemma, POS, gloss, romanization) using the shared WordCard/AnalysisResult types. The UI must surface the new feature-002 validation data: overall confidence level (high/medium/low) and per-card validation warnings, so users know when results may be unreliable. Handle the structured error envelope and rate-limit responses gracefully."

## Clarifications

### Session 2026-07-08

- Q: Should the analyze screen let the user pick the source language, or rely purely on the backend's auto-detection? → A: Optional override picker — default is auto-detect, but the user can force a supported source language when detection guesses wrong.
- Q: FR-005 requires sign-out, but all screens except Home are placeholders — where does the sign-out action live? → A: In the Home screen header (menu/icon); Flashcards, History, and Settings remain pure placeholders.
- Q: Which platform(s) must pass acceptance testing for this phase? → A: Android only — code remains cross-platform, but all acceptance scenarios are verified on an Android emulator/device; iOS verification is deferred until a Mac is available.
- Q: When silent token refresh fails and the user must sign in again, what should they find afterward? → A: Preserve draft text — the typed-but-unsubmitted text and language override are restored after re-login; the previously displayed result is not.
- Q: Should the source-language override persist across app restarts? → A: No — it resets to auto-detect on each app launch; it persists only within a running session (including across a forced re-login).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Analyze Text and View Breakdown (Priority: P1)

A language learner opens the app, enters or pastes a sentence in a foreign language on the home screen, and submits it for analysis. The app shows the sentence's translation and a card for each word containing its surface form, dictionary form, part of speech, meaning, and (for applicable languages) romanization — in the same order the words appear in the sentence.

**Why this priority**: This is the core value of the entire product — turning foreign-language text into an understandable word-by-word breakdown. Every other screen and phase builds on this.

**Independent Test**: Using a pre-provisioned signed-in account, enter a known Korean/Japanese/English sentence, submit, and verify the translation and word cards appear with all expected fields in input order.

**Acceptance Scenarios**:

1. **Given** a signed-in user on the home screen, **When** they enter a foreign-language sentence and submit, **Then** the translation and one card per recognized word are displayed, in the order the words appear in the sentence.
2. **Given** an analysis is in progress, **When** the user waits, **Then** a clear in-progress indicator is shown until results arrive.
3. **Given** a result where a card has no romanization (e.g., English), **When** it is displayed, **Then** the card omits that field cleanly rather than showing an empty placeholder.
4. **Given** the user has received a result, **When** they enter new text and submit again, **Then** the previous result is replaced by the new one.
5. **Given** the language override is set to a specific language, **When** the user submits text, **Then** the analysis treats the text as that language rather than relying on automatic detection, and the override remains selected for the next analysis.

---

### User Story 2 - Register, Sign In, and Stay Signed In (Priority: P2)

A new user creates an account from the app; a returning user signs in. Once signed in, the user stays signed in across app restarts until they sign out or their session expires, and all analysis requests are made on their behalf without re-entering credentials.

**Why this priority**: The analysis service requires an authenticated user, so sign-in is the gateway to everything — but it is scaffolding around the P1 value, not the value itself.

**Independent Test**: Register a new account, close and reopen the app, and verify the user lands on the home screen still signed in; sign out and verify protected screens are no longer reachable.

**Acceptance Scenarios**:

1. **Given** a first-time user, **When** they register with valid credentials, **Then** they are signed in and taken to the home screen.
2. **Given** a returning user with valid credentials, **When** they sign in, **Then** they reach the home screen and can analyze text.
3. **Given** a signed-in user, **When** they fully close and reopen the app, **Then** they remain signed in without re-entering credentials.
4. **Given** a user whose session has expired beyond silent recovery, **When** they attempt an analysis, **Then** they are prompted to sign in again, and after success they return to the home screen with their typed-but-unsubmitted text and language override restored (the previously displayed result is not restored).
5. **Given** invalid credentials at sign-in, **When** the user submits, **Then** a clear, non-technical error message is shown and no session is created.

---

### User Story 3 - Know When Results May Be Unreliable (Priority: P3)

When the analysis service is less sure about a result, the learner can see that at a glance: the result carries an overall reliability level (high / medium / low), and individual word cards that have known issues (e.g., missing meaning, unverified part of speech, romanization that may be wrong) are visibly flagged with a plain-language explanation.

**Why this priority**: Learners trust what the app tells them; silently wrong glosses or romanization teach mistakes. The backend already reports this data — the app must not hide it. It is P3 only because it decorates the P1 flow rather than enabling it.

**Independent Test**: Submit sentences known (from the backend contract fixtures) to produce high, medium, and low confidence results and verify the overall indicator and per-card warnings render correctly for each.

**Acceptance Scenarios**:

1. **Given** a result with high confidence and no warnings, **When** it is displayed, **Then** the result appears clean, without alarming indicators.
2. **Given** a result with medium or low confidence, **When** it is displayed, **Then** a clearly visible indicator communicates the reduced reliability in plain language.
3. **Given** a word card with one or more validation warnings, **When** the cards are displayed, **Then** that card is visibly marked and the user can see what the warning means in plain language (not internal issue codes).
4. **Given** a result whose analysis produced no word cards at all, **When** it is displayed, **Then** the user sees the translation (if any) plus an explanation that word-level breakdown was unavailable — not an empty or broken-looking screen.

---

### User Story 4 - Navigate the App Shell (Priority: P4)

The user can move between the app's main areas — Home, Flashcards, History, and Settings. Home is fully functional (the analyze flow); the other three exist as clearly labeled placeholders so the app's structure is in place for upcoming phases.

**Why this priority**: Pure scaffolding for later phases. Valuable for structure and demo purposes, but delivers no standalone learning value.

**Independent Test**: From the home screen, navigate to each of the other three areas and back, verifying each shows an appropriate "coming soon" placeholder and navigation state is preserved.

**Acceptance Scenarios**:

1. **Given** a signed-in user, **When** they open the app, **Then** they land on Home and can reach Flashcards, History, and Settings via primary navigation.
2. **Given** the user is on a placeholder screen, **When** they view it, **Then** it clearly communicates the feature is coming later and offers a way back to Home.

---

### Edge Cases

- Empty or whitespace-only input: the submit action is unavailable or produces an inline prompt, and no request is sent.
- Input longer than the service accepts: the user is told the limit before or upon submission rather than receiving a raw server rejection.
- No network connectivity or the server is unreachable: a friendly retriable message is shown; the app does not crash or hang indefinitely.
- The service reports the user has hit their rate limit: the app explains they have made too many requests and should wait, rather than showing a generic failure.
- The service returns its structured error envelope (validation error, unsupported input, internal failure): the app maps it to a plain-language message and never displays raw payloads, codes, or stack traces.
- Session token expires mid-session (see US2, scenario 4).
- Very slow analysis (multi-second): the in-progress state persists and the user can abandon/cancel by navigating away without breaking subsequent requests.
- Device keyboard/paste interactions: pasted multi-line text is accepted and treated as the input sentence(s).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Users MUST be able to create an account and sign in from within the app using the credential scheme the existing backend accepts.
- **FR-002**: The app MUST keep the user signed in across app restarts by storing session credentials in the platform's secure storage (never in plain text or shared/readable storage).
- **FR-003**: All requests to protected capabilities MUST carry the user's session credentials automatically; the user never re-enters credentials for individual actions.
- **FR-004**: When the backend rejects a request because the session is invalid or expired, the app MUST first attempt silent recovery; if that fails, it MUST prompt re-authentication and, on success, return the user to the home screen with their draft text and language override restored (displayed results need not be restored).
- **FR-005**: Users MUST be able to sign out via an action in the Home screen header, which removes stored session credentials and returns the app to the signed-out state.
- **FR-006**: The home screen MUST let the user enter or paste text (including multi-line paste) and submit it for analysis.
- **FR-006a**: The analyze screen MUST offer an optional source-language override (defaulting to automatic detection); when the user selects a language, the request carries that hint, and the selection persists for subsequent analyses within the running session (including across a forced re-login) until changed or reset to automatic. Each fresh app launch starts on automatic detection.
- **FR-007**: The app MUST display the analysis result as the source text's translation plus one card per word, each card showing surface form, dictionary form (lemma), part of speech, and meaning (gloss), with romanization shown when present.
- **FR-008**: Word cards MUST be displayed in the same order as the words appear in the input text, as provided by the service.
- **FR-009**: The app MUST display the result's overall confidence level (high / medium / low) whenever a result is shown, with medium and low visually distinct from high.
- **FR-010**: The app MUST visibly flag any word card that carries validation warnings and present the warning meaning in plain language; internal issue codes are never shown verbatim to the user.
- **FR-011**: When a result contains no word cards, the app MUST present the available parts of the result (e.g., translation) alongside an explanation that word-level breakdown was unavailable.
- **FR-012**: The app MUST show a clear in-progress state while an analysis is pending, and MUST prevent duplicate submission of the same request while one is in flight.
- **FR-013**: The app MUST translate the backend's structured error envelope into user-friendly messages, with distinct handling for: invalid input, rate limiting (tell the user to wait), authentication failure (re-authenticate), and server/unknown errors (retriable message).
- **FR-014**: The app MUST handle network unavailability gracefully with a retriable, non-technical message.
- **FR-015**: The app MUST reject empty input client-side and communicate the service's input length limit rather than surfacing a raw server rejection.
- **FR-016**: The app MUST provide primary navigation between Home, Flashcards, History, and Settings, where the latter three are labeled placeholders for future phases.
- **FR-017**: The app MUST define its data shapes for analysis results, word cards, confidence, and validation warnings to match the backend's wire contract exactly (single source of truth for field names and allowed values), so backend and app cannot silently drift.
- **FR-018**: Session credentials and user-entered text MUST never be written to logs or crash reports by the app.

### Key Entities

- **User Session**: The signed-in identity — credentials issued by the backend at register/sign-in, stored securely on device, attached to protected requests, cleared on sign-out or expiry.
- **Analysis Result**: The outcome of analyzing one piece of text — the detected language, the translation, an ordered list of Word Cards, an overall confidence level, and any result-level warnings or partial-failure notes.
- **Word Card**: One word from the input — surface form, dictionary form (lemma), part of speech, meaning (gloss), optional romanization, and zero or more attached validation warnings.
- **Validation Warning**: A machine-classified issue reported by the backend (stable code + affected field/card) that the app renders as plain-language guidance about reliability.
- **Error Envelope**: The backend's structured error response (code, message, details) that the app maps to user-facing messaging by category.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A signed-in user can go from opening the app to seeing a completed analysis of a short sentence in under 30 seconds, with no more than 3 interactions (focus input, paste/type, submit).
- **SC-002**: A new user can complete registration and reach their first analysis result in under 2 minutes.
- **SC-003**: 100% of displayed results include the reliability indicator, and 100% of word cards carrying backend warnings are visibly flagged — verified against the backend contract fixture sentences (high, medium, and low confidence cases all render correctly).
- **SC-004**: For every failure class the backend can produce (invalid input, rate limit, auth expiry, server error, network loss), the user sees a plain-language message — zero raw error payloads, codes, or stack traces reach the screen.
- **SC-005**: A user who force-closes and reopens the app remains signed in and can analyze again without re-entering credentials, in 100% of trials.
- **SC-006**: The app remains responsive (input and navigation usable) during a slow analysis; no scenario in the acceptance tests results in a frozen or crashed app.

## Assumptions

- The existing backend (features 001/002) is the sole data source: its authentication endpoints, analysis endpoint, response shapes (translation, word cards, confidence, validation issues), and error envelope are treated as a fixed contract for this feature. No backend changes are in scope.
- The credential scheme is the one feature 001 established (account registration and sign-in issuing a session token); no third-party/social sign-in in this phase.
- Supported analysis languages and the input length limit are whatever the backend currently enforces; the app mirrors the limit client-side rather than defining its own.
- Flashcards, History, and Settings are placeholders only — no data, persistence, or settings behavior in this phase (build order Phases 3+).
- Cross-app text sharing (share-menu integration) is explicitly out of scope; it is the next phase and will reuse this analyze screen.
- One result is shown at a time; local history/persistence of past analyses is out of scope for this phase.
- The app targets both Android and iOS at the code level, but acceptance testing for this phase runs on Android only (developer machine is Windows; iOS verification deferred until a Mac is available).
- The backend's accepted input is text up to 500 characters with an optional source-language hint (verified against the current analyze request contract); FR-006a and FR-015 mirror these exactly.
- Analysis latency is dominated by the backend (which may call external services); the app's obligation is a responsive in-progress state, not a latency guarantee.
