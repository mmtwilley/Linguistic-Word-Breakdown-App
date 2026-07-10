# Tasks: Mobile App Skeleton with Analyze Screen

**Input**: Design documents from `/specs/003-mobile-analyze-screen/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/backend-api.md, quickstart.md

**Tests**: Included — the project's established discipline (features 001/002) is unit/component tests per story plus contract-fixture tests against the 002 validation fixtures (SC-003 depends on them).

**Organization**: Tasks are grouped by user story. The backend is a fixed dependency and is never modified. All paths are relative to the repo root.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1 = analyze text, US2 = auth & session, US3 = reliability indicators, US4 = navigation shell

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: A booting Expo TypeScript project with test tooling and environment config.

- [x] T001 Create the Expo project: `npx create-expo-app@latest mobile --template blank-typescript` at repo root; verify it boots on the Android emulator per specs/003-mobile-analyze-screen/quickstart.md ✅ 2026-07-10 (Expo SDK 57, RN 0.86, TS 6; verified via emulator screenshot)
- [x] T002 Install runtime dependencies in mobile/: `npx expo install @react-navigation/native @react-navigation/bottom-tabs @react-navigation/native-stack react-native-screens react-native-safe-area-context expo-secure-store` ✅ 2026-07-10 (React Navigation v7, secure-store plugin auto-added to app.json, tsc clean)
- [x] T003 [P] Configure testing and linting in mobile/package.json: jest-expo preset + `test` script (dev deps jest, jest-expo, @testing-library/react-native, @types/jest); ESLint via eslint-config-expo + Prettier with `lint` script; `typecheck` script running `tsc --noEmit` (constitution: linting enforced on commit — no exceptions); add a passing smoke test in mobile/__tests__/smoke.test.tsx ✅ 2026-07-10 (RNTL v14 requires `await render(...)`; TS6 needs explicit `"types": ["jest"]`; test/lint/format/typecheck all green)
- [x] T004 [P] Create mobile/.env.example documenting EXPO_PUBLIC_API_URL (emulator value `http://10.0.2.2:8080`); add `.env` to mobile/.gitignore ✅ 2026-07-10 (local .env created from example; verified git-ignored)

**Checkpoint**: `npx expo start` shows the template app in the emulator; `npm test`, `npm run lint`, and `npm run typecheck` all pass.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The canonical wire types, API client, token storage, auth context, minimal sign-in, and navigation shell — everything every story sits on. (The analyze endpoint is authenticated, so a minimal sign-in path is foundational; the full US2 journey — register, refresh, sign-out, draft restore — stays in Phase 4.)

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [x] T005 [P] Create canonical wire types in mobile/src/api/types.ts exactly as specified in specs/003-mobile-analyze-screen/contracts/backend-api.md §5 (Confidence, IssueCode with forward-compat union, ValidationIssue, WordCard, AnalysisResult, LanguageHint, AnalysisRequest, AuthResponse, ErrorEnvelope) ✅ 2026-07-10 (also RegisterRequest/LoginRequest/RefreshRequest; typecheck + lint clean)
- [x] T006 [P] Create plain-language message maps in mobile/src/i18n/messages.ts: all 8 issue codes + generic unknown-code fallback (contract §3) and all 10 error codes with per-code behavior notes (contract §4); no raw code is ever a display string ✅ 2026-07-10 (also exports isResultLevel() routing helper; unknown envelope codes prefer server message over generic fallback)
- [x] T007 Create the typed fetch client in mobile/src/api/client.ts: reads EXPO_PUBLIC_API_URL (startup check: non-local URL must be https, research.md Decision 8), JSON headers, parses 2xx to typed results and non-2xx bodies to ErrorEnvelope, synthesizes `NETWORK_ERROR` (retryable) for fetch failure/timeout via AbortController (depends on T005) ✅ 2026-07-10 (ApiResult discriminated union; error carries HTTP status for T024's 401 detection; 30s default timeout)
- [x] T008 [P] Create token storage in mobile/src/auth/tokenStorage.ts wrapping expo-secure-store: read/write/clear the accessToken+refreshToken pair as a unit (data-model.md Session invariants) ✅ 2026-07-10 (single JSON value under one key = atomic pair; corrupt/unreadable entries self-purge to signed-out)
- [x] T009 Create auth API functions in mobile/src/api/auth.ts: register, login, refresh returning typed AuthResponse or ErrorEnvelope (depends on T005, T007) ✅ 2026-07-10
- [x] T010 Create mobile/src/auth/AuthContext.tsx: status machine `bootstrapping → signedOut/signedIn` (data-model.md lifecycle), bootstrap from tokenStorage on mount, signIn(email, password) and signOut() that keep secure store and state in sync (depends on T008, T009) ✅ 2026-07-10 (persist-before-state invariant; getTokens/applyTokens seams for T014 Bearer + T024 rotation; tokens in ref not state)
- [ ] T011 Create minimal sign-in screen mobile/src/screens/LoginScreen.tsx: email/password fields, submit via AuthContext.signIn, inline INVALID_CREDENTIALS message from messages.ts, link stub to Register (depends on T010)
- [ ] T012 Create the navigation shell: mobile/src/navigation/RootNavigator.tsx (switches auth stack ⇄ main tabs on AuthContext status, shows nothing while bootstrapping), mobile/src/navigation/MainTabs.tsx (Home | Flashcards | History | Settings), shared mobile/src/screens/PlaceholderScreen.tsx stub, and wire providers + container in mobile/App.tsx (depends on T010, T011)
- [ ] T013 [P] Foundational tests: envelope/network parsing in mobile/__tests__/api/client.test.ts; contract-fixture parse test in mobile/__tests__/api/types.contract.test.ts asserting the 002 fixture responses (specs/002-analysis-validation/contracts/validation-api.md) type-check against types.ts (depends on T005, T007)

**Checkpoint**: With the backend running and a pre-registered account, sign-in lands on a four-tab shell with stub screens.

---

## Phase 3: User Story 1 — Analyze Text and View Breakdown (Priority: P1) 🎯 MVP

**Goal**: Signed-in user enters/pastes text (≤500 chars, optional language override), submits, and sees the translation plus ordered word cards with clean handling of null fields, loading, and errors.

**Independent Test**: With a signed-in session (Phase 2 sign-in), submit `점심을 먹었어요` → translation + ordered cards with romanization; verify loading state, resubmit-replaces-result, and the FR-013 error mappings.

### Implementation for User Story 1

- [ ] T014 [P] [US1] Create the analyze API call in mobile/src/api/analyze.ts: POST /api/analyze with Bearer token, body `{text, language?}` per contract §2, returning AnalysisResult or ErrorEnvelope; accepts an AbortSignal (depends on foundational T005–T009)
- [ ] T015 [P] [US1] Create mobile/src/components/AnalyzeInput.tsx: multiline paste-friendly input, live character counter, submit disabled when blank/>500 chars (FR-015) or while a request is in flight (FR-012)
- [ ] T016 [P] [US1] Create mobile/src/components/LanguagePicker.tsx: options auto | 한국어 (kor) | 日本語 (jpn) | 中文 (cmn) | English/Latin (lat); "auto" sends no hint (FR-006a, data-model.md validation rules)
- [ ] T017 [P] [US1] Create mobile/src/components/WordCardView.tsx: renders surface, lemma, pos, gloss, romanization; null fields are omitted, not shown empty (US1-AS3); `ipa` not rendered this phase
- [ ] T018 [US1] Create mobile/src/components/ResultView.tsx: translation header + words rendered in array order — never sorted (FR-008) — with a loading state; new results replace the previous one (US1-AS4) (depends on T017)
- [ ] T019 [P] [US1] Create mobile/src/components/ErrorBanner.tsx: displays the mapped message from messages.ts with a retry affordance when `retryable` is true (FR-013/FR-014)
- [ ] T020 [US1] Implement mobile/src/screens/HomeScreen.tsx: request state machine `Idle → Submitting → Success | Failed` (data-model.md AnalyzeScreenState), language override kept in screen state defaulting to auto and persisting across submissions, AbortController cancel on unmount/navigate-away, wires T014–T019 together (depends on T014–T019); register as the Home tab in MainTabs.tsx
- [ ] T021 [US1] Wire per-code error UX in HomeScreen/AnalyzeInput: INVALID_INPUT + VALIDATION_ERROR inline at the input, LANGUAGE_UNDETECTABLE suggests the language picker, RATE_LIMIT_EXCEEDED shows the wait message, NETWORK_ERROR/INTERNAL_ERROR show the retriable banner (FR-013, contract §4 table) (depends on T020)
- [ ] T022 [P] [US1] Component tests in mobile/__tests__/: AnalyzeInput char-limit/disable behavior, ResultView ordering + null-romanization omission, HomeScreen submit flow and error mappings with mocked fetch fixtures (depends on T020, T021)

**Checkpoint**: US1 acceptance scenarios 1–5 pass on the emulator — the product's core value works end-to-end.

---

## Phase 4: User Story 2 — Register, Sign In, and Stay Signed In (Priority: P2)

**Goal**: Full account journey: registration, persistent session across restarts, silent token refresh with rotation, forced re-auth with draft preservation, and sign-out.

**Independent Test**: Register a fresh email → land signed in on Home; force-close and reopen → still signed in; corrupt/expire the refresh token → re-auth prompt, and typed draft text survives re-login; sign out from the Home header → back at Login.

### Implementation for User Story 2

- [ ] T023 [P] [US2] Create mobile/src/screens/RegisterScreen.tsx: email + password (8–128) inline validation mirroring contract §1, EMAIL_ALREADY_EXISTS maps to "sign in instead" affordance, success signs the user in and lands on Home (US2-AS1); wire into the auth stack in RootNavigator.tsx
- [ ] T024 [US2] Implement single-flight token refresh in mobile/src/auth/useAuthedFetch.ts: attach Bearer token; on 401 UNAUTHORIZED perform one shared refresh (concurrent 401s await the same promise, research.md Decision 3), persist the rotated pair atomically via tokenStorage, retry the original request once; on INVALID_REFRESH_TOKEN clear the session (FR-004); switch analyze calls in HomeScreen to use it (depends on foundational T007–T010)
- [ ] T025 [US2] Implement draft preservation in mobile/src/auth/draftStash.ts: on refresh-failure sign-out, stash HomeScreen's text + languageOverride in memory (never persisted to disk, FR-018); HomeScreen restores the stash after re-login; explicit sign-out clears it (FR-004, data-model.md `draft`) (depends on T024)
- [ ] T026 [US2] Add sign-out to the Home screen header in mobile/src/screens/HomeScreen.tsx / MainTabs.tsx header options: clears tokens + draft via AuthContext.signOut, root navigator falls back to Login (FR-005, clarification 2026-07-08)
- [ ] T027 [P] [US2] Tests in mobile/__tests__/auth/: single-flight refresh (two concurrent 401s → one refresh call, both retried), rotation persistence, bootstrap-from-store session restore, draft stash/restore/clear, RegisterScreen validation + EMAIL_ALREADY_EXISTS handling (depends on T023–T026)

**Checkpoint**: US2 acceptance scenarios 1–5 pass; US1 still passes (regression check).

---

## Phase 5: User Story 3 — Know When Results May Be Unreliable (Priority: P3)

**Goal**: Overall confidence is always visible (high renders unobtrusively), flagged cards show plain-language warnings, and an empty analysis explains itself.

**Independent Test**: Submit the 002 contract fixture sentences: kor (medium + order warning), jpn/eng (high, clean), cmn (low + EMPTY_ANALYSIS explanation), eng (low + coverage warning) — each renders the correct indicator and card flags with no raw codes visible.

### Implementation for User Story 3

- [ ] T028 [P] [US3] Create mobile/src/components/ConfidenceBadge.tsx: high = subtle, medium/low = visually distinct with plain-language label from messages.ts (FR-009, US3-AS1/AS2)
- [ ] T029 [US3] Implement issue routing in mobile/src/components/ResultView.tsx + WordCardView.tsx: split issues by `surface` (null ⇒ result-level notice list; non-null ⇒ warning flag + expandable plain-language text on the matching card), unknown codes fall back to the generic message, raw codes and `detail` never rendered (FR-010, data-model.md display routing) (depends on T028)
- [ ] T030 [US3] Implement the empty-analysis state in mobile/src/components/ResultView.tsx: when `words` is empty, show translation (if any) plus the EMPTY_ANALYSIS explanation instead of a bare screen (FR-011, US3-AS4) (depends on T029)
- [ ] T031 [P] [US3] Fixture-driven component tests in mobile/__tests__/components/reliability.test.tsx: render each 002 contract fixture response and assert badge variant, flagged-card count, empty-state copy, and absence of raw issue codes (depends on T028–T030)

**Checkpoint**: SC-003 verified — all fixture rows render the right indicators; US1/US2 unaffected.

---

## Phase 6: User Story 4 — Navigate the App Shell (Priority: P4)

**Goal**: Flashcards, History, and Settings are polished, clearly-labeled "coming soon" placeholders reachable via tabs.

**Independent Test**: From Home, visit each tab and back; each placeholder names itself, says the feature is coming later, and offers a way back to Home; tab state is preserved.

### Implementation for User Story 4

- [ ] T032 [P] [US4] Flesh out mobile/src/screens/PlaceholderScreen.tsx: per-screen title (Flashcards/History/Settings), "coming in a later phase" copy, and a go-to-Home affordance (US4-AS2); pass screen names from MainTabs.tsx
- [ ] T033 [P] [US4] Add tab icons + labels in mobile/src/navigation/MainTabs.tsx and a navigation test in mobile/__tests__/navigation/tabs.test.tsx verifying all four tabs render and Home is the initial route (US4-AS1)

**Checkpoint**: All four user stories independently demonstrable.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T034 [P] FR-018 audit: grep mobile/src for console.log/console.error paths that could carry tokens or user text; ensure client.ts and auth modules log nothing sensitive
- [ ] T035 [P] Add a "Mobile app" section to README.md: prerequisites, env setup, run + test commands (pointer to specs/003-mobile-analyze-screen/quickstart.md)
- [ ] T036 Run the full manual acceptance walkthrough from specs/003-mobile-analyze-screen/quickstart.md against the live local backend; verify SC-001 through SC-006 and record results as notes against this task in specs/003-mobile-analyze-screen/tasks.md (feature-002 precedent)
- [ ] T037 Constitution compliance review (Principles I–V + shared-contract rule) before merge; confirm no client-side external API calls, tokens only in secure store, envelope opacity, YAGNI held

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none — start immediately
- **Foundational (Phase 2)**: needs Phase 1 — BLOCKS all stories
- **US1 (Phase 3)**: needs Phase 2 (uses foundational sign-in for a session)
- **US2 (Phase 4)**: needs Phase 2; T024–T026 touch HomeScreen, so running after US1 avoids file conflicts (single-developer order: US1 → US2)
- **US3 (Phase 5)**: needs US1's ResultView/WordCardView (extends them)
- **US4 (Phase 6)**: needs only Phase 2's navigation shell — can run any time after Phase 2
- **Polish (Phase 7)**: after all desired stories

### Key task-level dependencies

- T007 ← T005; T009 ← T005+T007; T010 ← T008+T009; T012 ← T010+T011
- T020 ← T014–T019; T024 ← T007–T010; T025 ← T024; T029 ← T028; T030 ← T029

### Parallel Opportunities

- Phase 1: T003 ‖ T004 (after T001–T002)
- Phase 2: T005 ‖ T006 ‖ T008 together; T013 alongside T010–T012
- Phase 3: T014 ‖ T015 ‖ T016 ‖ T017 ‖ T019 all at once, then T018 → T020 → T021
- Phase 4: T023 ‖ T024 (different files), then T025 → T026
- Phase 6: T032 ‖ T033; US4 can also run in parallel with US3 entirely

---

## Implementation Strategy

**MVP first**: Phases 1–3 (T001–T022) deliver the product's core value — sign in, analyze, read word cards. Stop, run the US1 independent test, demo.

**Incremental delivery**: then US2 (full auth journey) → US3 (reliability UI, completes the feature-002 payoff) → US4 (shell polish) → Polish. Each checkpoint is independently testable; commit after each task or logical group per the constitution's commit discipline (reference task IDs in messages).

**Single-developer note**: the phases are ordered to avoid same-file conflicts (US2 edits HomeScreen after US1 creates it; US3 extends US1's components). Follow phase order; parallel markers matter mainly within a phase.
