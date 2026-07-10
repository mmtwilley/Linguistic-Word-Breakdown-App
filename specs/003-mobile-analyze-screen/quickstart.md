# Quickstart: Mobile App Skeleton with Analyze Screen

**Feature**: 003-mobile-analyze-screen | **Date**: 2026-07-08

## Prerequisites (Windows dev machine)

1. **Node.js 20 LTS** — `node --version`
2. **Android Studio** with an Android Virtual Device (API 34+) created and bootable, plus `adb` on PATH.
3. **Expo Go** — installed automatically on the emulator the first time `expo start` targets it (press `a`).
4. **Backend running locally** (fixed dependency — see `specs/001-backend-auth-analyze/quickstart.md`):
   ```powershell
   # in backend/: Postgres + Redis
   docker compose up -d
   # set env vars in your own terminal (API keys, base64 JWT_SECRET), then
   ./mvnw spring-boot:run
   ```
   Liveness: `GET http://localhost:8080/actuator/health` returning 403 means the server is up (endpoint is secured).

## Project setup (first time)

```powershell
# from repo root
npx create-expo-app@latest mobile --template blank-typescript
cd mobile
npx expo install @react-navigation/native @react-navigation/bottom-tabs @react-navigation/native-stack `
  react-native-screens react-native-safe-area-context expo-secure-store
npm i -D jest jest-expo @testing-library/react-native @types/jest
```

Environment config:

```powershell
# mobile/.env  (gitignored; .env.example is committed)
EXPO_PUBLIC_API_URL=http://10.0.2.2:8080
```

`10.0.2.2` is the Android emulator's alias for the host machine's `localhost`. Alternative if that route misbehaves: `adb reverse tcp:8080 tcp:8080` then use `http://localhost:8080`.

## Run

```powershell
cd mobile
npx expo start
# press "a" to launch on the Android emulator
```

## Test

```powershell
cd mobile
npm test          # jest (jest-expo preset) + React Native Testing Library
npx tsc --noEmit  # type-level contract check
```

## Manual acceptance walkthrough (maps to spec user stories)

1. **US2**: Register a fresh email → lands on Home signed in. Kill and relaunch the app → still signed in. Sign out from the Home header → back at Login.
2. **US1**: Enter `점심을 먹었어요` → submit → translation + ordered cards with romanization appear; spinner shows while pending.
3. **US1/FR-006a**: Set the language override to 한국어, submit again → same behavior; override stays selected.
4. **US3**: Run the 002 contract fixture sentences (see `specs/002-analysis-validation/contracts/validation-api.md`): verify the confidence badge (high/medium/low), per-card warning flags, and the empty-analysis explanation for the Chinese fixture.
5. **Errors**: Submit 501 chars (blocked client-side); toggle emulator airplane mode (network message with retry); hammer submit past the rate limit (`RATE_LIMIT_EXCEEDED` → "wait a moment" message).

## Gotchas

- **HTTP vs HTTPS**: cleartext HTTP is acceptable only for the local emulator loopback. The client refuses a non-local `EXPO_PUBLIC_API_URL` that isn't `https://`.
- **JWT_SECRET must be base64** (backend requirement) — a non-base64 secret makes every login fail server-side.
- **Emulator clock skew** can make freshly-issued JWTs appear expired — cold-boot the AVD if auth behaves oddly right after resume.
- **OneDrive**: the repo lives under OneDrive; if Metro or Gradle hit file-lock errors, pause OneDrive sync during development.
