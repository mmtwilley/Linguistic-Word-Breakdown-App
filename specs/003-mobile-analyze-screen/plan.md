# Implementation Plan: Mobile App Skeleton with Analyze Screen

**Branch**: `003-mobile-analyze-screen` | **Date**: 2026-07-08 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/003-mobile-analyze-screen/spec.md`

## Summary

Build the `mobile/` React Native + TypeScript app (Phase 2 of the build order): an Expo-managed project with a four-tab navigation shell (Home functional; Flashcards/History/Settings placeholders), an email/password auth flow against the existing `/api/auth` endpoints with tokens in platform secure storage and automatic refresh, and the analyze screen — text input (≤500 chars) with an optional source-language override, calling `POST /api/analyze` and rendering translation + ordered word cards with the feature-002 confidence level and per-card validation warnings mapped to plain language. The backend contract is fixed; the mobile `WordCard`/`AnalysisResult` TypeScript types become the canonical shared contract per the constitution.

## Technical Context

**Language/Version**: TypeScript 5.x on React Native via Expo SDK (latest stable at implementation time), Node.js 20 LTS toolchain
**Primary Dependencies**: `expo`, `@react-navigation/native` + `@react-navigation/bottom-tabs` + `@react-navigation/native-stack`, `expo-secure-store`, `react-native-safe-area-context`/`react-native-screens` (navigation peers)
**Storage**: `expo-secure-store` (Keychain / Android Keystore) for access + refresh tokens only; no local database this phase
**Testing**: Jest with `jest-expo` preset + React Native Testing Library; contract tests validate TS types against fixture JSON derived from `specs/002-analysis-validation/contracts/validation-api.md`
**Target Platform**: Android emulator/device (API 34+) for acceptance; code stays iOS-compatible (no Android-only APIs), iOS verification deferred (clarification 2026-07-08)
**Project Type**: mobile app — new `mobile/` directory alongside existing `backend/`
**Performance Goals**: UI stays interactive during in-flight analysis (SC-006); analysis latency is backend-owned — client shows an in-progress state and never blocks the JS thread on the request
**Constraints**: input ≤500 chars with optional `language` hint (`kor|jpn|cmn|lat`) per `AnalysisRequest`; JWT access token ≤15 min with refresh rotation (`/api/auth/refresh`); all errors arrive as the `{"error":{code,message,retryable}}` envelope; no external API called from the client
**Scale/Scope**: 6 screens (Login, Register, Home/Analyze, 3 placeholders), 1 authenticated API endpoint + 3 auth endpoints, single result displayed at a time

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate | Status |
|---|---|---|
| I. Tiered API Pipeline | Client MUST NOT call Claude/DeepL/dictionary APIs directly; all analysis goes through the backend pipeline | ✅ PASS — the app's only network dependency is the Spring Boot backend; no AI/translation SDKs in the client |
| II. Security-First | No API keys in the client; tokens in secure storage with refresh rotation; HTTPS in non-local; error opacity preserved | ✅ PASS — `expo-secure-store` (never AsyncStorage) for tokens; client consumes `/api/auth/refresh` rotation; prod base URL must be `https://` (enforced by config validation); client renders only envelope `message` fields, never raw payloads (FR-013, FR-018). HTTP allowed solely for local emulator → `http://10.0.2.2:8080` (a "local environment" under the constitution) |
| III. Twelve-Factor | Config via environment, no hardcoded service URLs | ✅ PASS — backend base URL supplied via `EXPO_PUBLIC_API_URL` (documented in `mobile/.env.example`); no secrets exist client-side by design |
| IV. Graceful Error Handling | Client honors the structured envelope and `retryable` flag; degraded results still render | ✅ PASS — typed envelope parser maps `code` → plain-language message with retry affordance when `retryable`; partial results (empty `words`, warnings) render per FR-011 |
| V. Simplicity & Phase Gates | Phase 2 only; no speculative Phase 3+ work; complexity justified | ✅ PASS — placeholders carry no logic; no share-menu, SRS, offline cache, or state library; navigation + context only |
| Shared contract | `WordCard`/`AnalysisResult` TS types are the canonical mobile↔backend contract | ✅ PASS — `mobile/src/api/types.ts` mirrors the backend DTOs field-for-field (Phase 1 contract doc is the source) |

**Post-design re-check (after Phase 1)**: no new violations introduced; no Complexity Tracking entries required.

## Project Structure

### Documentation (this feature)

```text
specs/003-mobile-analyze-screen/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── backend-api.md   # Fixed backend wire contract + canonical TS types
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
mobile/
├── app.json                     # Expo config (name, scheme, android/ios ids)
├── package.json
├── tsconfig.json
├── .env.example                 # EXPO_PUBLIC_API_URL documentation
├── App.tsx                      # Root: providers + navigation container
├── src/
│   ├── api/
│   │   ├── types.ts             # Canonical shared types: AnalysisResult, WordCard,
│   │   │                        #   Confidence, ValidationIssue, ErrorEnvelope, Auth DTOs
│   │   ├── client.ts            # fetch wrapper: base URL, JSON, envelope parsing
│   │   ├── auth.ts              # register / login / refresh calls
│   │   └── analyze.ts           # POST /api/analyze
│   ├── auth/
│   │   ├── AuthContext.tsx      # session state, sign-in/out, bootstrap from secure store
│   │   ├── tokenStorage.ts      # expo-secure-store read/write/clear
│   │   ├── useAuthedFetch.ts    # attach access token; single-flight refresh on 401
│   │   └── draftStash.ts        # in-memory draft text + override stash across forced re-login
│   ├── navigation/
│   │   ├── RootNavigator.tsx    # auth stack ⇄ main tabs switch
│   │   └── MainTabs.tsx         # Home | Flashcards | History | Settings
│   ├── screens/
│   │   ├── LoginScreen.tsx
│   │   ├── RegisterScreen.tsx
│   │   ├── HomeScreen.tsx       # analyze flow + sign-out in header
│   │   └── PlaceholderScreen.tsx  # shared "coming soon" (Flashcards/History/Settings)
│   ├── components/
│   │   ├── AnalyzeInput.tsx     # text box, char counter, language override, submit
│   │   ├── LanguagePicker.tsx   # auto | 한국어 | 日本語 | 中文 | Latin-script
│   │   ├── ResultView.tsx       # translation + confidence + card list / empty state
│   │   ├── WordCardView.tsx     # surface, lemma, pos, gloss, romanization + warnings
│   │   ├── ConfidenceBadge.tsx  # high/medium/low indicator
│   │   └── ErrorBanner.tsx      # envelope-mapped message + retry affordance
│   └── i18n/
│       └── messages.ts          # issue-code + error-code → plain-language strings
└── __tests__/                   # Jest + RNTL (mirrors src/ structure)
    ├── api/
    ├── auth/
    ├── components/
    ├── navigation/
    └── screens/

backend/                         # UNCHANGED this feature (fixed contract)
```

**Structure Decision**: New self-contained `mobile/` directory at repo root per the project-structure memory and constitution build order; backend untouched. Feature code grouped by responsibility (api / auth / navigation / screens / components) rather than by screen, since api and auth layers are shared by every future phase.

## Complexity Tracking

No constitution violations — table intentionally empty. (Expo is tooling within the mandated "React Native + TypeScript" constraint, not a stack deviation; no state-management or persistence libraries added.)
