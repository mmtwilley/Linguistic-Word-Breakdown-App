# Implementation Plan: Analysis Validation & Confidence Level

**Branch**: `002-analysis-validation` | **Date**: 2026-07-06 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/002-analysis-validation/spec.md`

## Summary

Add a rule-based validation layer to the analysis pipeline that computes a per-response
confidence level (high/medium/low) and a machine-readable issue list, catching the silent
failures found by the extension-vs-backend parity study (tools/parity-diff): empty analyses,
dropped input, out-of-order cards, blank fields, romanization passthrough, off-vocabulary
POS labels, and structurally invalid Claude tool output. Implementation is a new
`ValidationStep` appended to the existing `AnalysisPipeline`, plus shape-validation of
Claude responses inside `ClaudeStep`, plus additive `confidence`/`issues` fields on
`AnalysisResponse`. Zero new external calls, zero new dependencies.

## Technical Context

**Language/Version**: Java 25 (existing backend toolchain)
**Primary Dependencies**: Spring Boot 3.x (existing); no new dependencies — validation is plain Java string/collection checks
**Storage**: N/A (no schema change; validation state lives in `AnalysisContext` per request)
**Testing**: JUnit 5 + Mockito (`*Test`), Testcontainers ITs (`*IT`) — existing conventions
**Target Platform**: Existing Spring Boot service (`backend/`)
**Project Type**: web-service (backend-only feature)
**Performance Goals**: Validation adds < 1 ms per request (input capped at 500 chars; all checks are O(cards × input length) string scans); zero additional external/paid calls (FR-011)
**Constraints**: Additive API change only (existing clients unaffected); issues must never leak internals (Principle II error opacity); user text must not appear in WARN/ERROR logs (Principle IV)
**Scale/Scope**: 1 new pipeline step, 1 modified step (ClaudeStep), 2 new domain types, 2 DTO changes, 1 POS-normalization utility, ~4 test classes

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment | Status |
|---|---|---|
| I. Tiered API Pipeline | Validation makes no API calls and does not reorder tiers. ClaudeStep schema-validation tightens the existing Claude tier without adding calls. | PASS |
| II. Security-First | No new endpoints, secrets, or env vars. Issue `detail` strings are written for client display (no stack traces, no internal class names). Input already sanitized upstream. | PASS |
| III. Twelve-Factor | Stateless; no config added. Structured JSON logging via existing Logback setup. | PASS |
| IV. Graceful Error Handling | Directly strengthens this principle: degraded results are now labeled instead of silent. Validation failures themselves are caught by the pipeline's per-step try/catch (a validation crash degrades to "no confidence computed", never a 500). Logging rule honored: issue summaries (code, language, count) log at WARN; affected surfaces only at DEBUG — user text never at ERROR/WARN. | PASS |
| V. Simplicity / Phase Gates | Reuses the existing `AnalysisStep` abstraction; no new patterns, services, or dependencies. Feature belongs to phase 1 hardening (analyze endpoint), not a future phase. FR-014 deliberately defers cache implementation to phase 5, shipping only the issue-code classification the cache will need. | PASS |

No violations — Complexity Tracking table not required.

**Post-Phase-1 re-check (2026-07-06)**: design artifacts introduce no new dependencies,
endpoints, or config; API change is additive (`confidence`, `issues`). PASS unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/002-analysis-validation/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── validation-api.md  # Response schema + issue-code catalog
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
backend/src/main/java/com/lingua_app/backend/
├── analysis/
│   ├── pipeline/
│   │   ├── AnalysisContext.java      # MODIFIED: + validationIssues, + confidence
│   │   ├── AnalysisPipeline.java     # MODIFIED: + ValidationStep registered last
│   │   ├── Confidence.java           # NEW: enum HIGH | MEDIUM | LOW
│   │   └── ValidationIssue.java      # NEW: record (code, severity, surface, detail)
│   └── step/
│       ├── ClaudeStep.java           # MODIFIED: schema-validate tool output (FR-009)
│       ├── PosNormalizer.java        # NEW: label → canonical vocabulary mapping (FR-008)
│       └── ValidationStep.java       # NEW: all rule checks + confidence derivation
├── dto/
│   ├── AnalysisResponse.java         # MODIFIED: + confidence, + issues
│   └── ValidationIssueDto.java       # NEW
└── service/
    └── AnalysisService.java          # MODIFIED: map context → DTO

backend/src/test/java/com/lingua_app/backend/
├── analysis/step/ValidationStepTest.java   # NEW: unit tests per rule + derivation
├── analysis/step/PosNormalizerTest.java    # NEW
├── analysis/step/ClaudeStepTest.java       # NEW: rejection of malformed entries
├── controller/AnalysisControllerTest.java  # MODIFIED: constructor arity + new jsonPaths
└── integration/AnalysisPipelineIT.java     # MODIFIED: assert confidence present
```

**Structure Decision**: Backend-only change inside the existing `backend/` Spring Boot
module, following its established `analysis/pipeline` + `analysis/step` + `dto` layout.
No mobile work: the client contract change is additive and documented in
`contracts/validation-api.md` for the future React Native client.

## Complexity Tracking

Not required — Constitution Check passed without violations.
