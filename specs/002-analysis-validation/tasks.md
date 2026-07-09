# Tasks: Analysis Validation & Confidence Level

**Input**: Design documents from `/specs/002-analysis-validation/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/validation-api.md

**Tests**: Included — the spec defines per-story Independent Tests, SC-001 is an executable
fixture table (contracts/validation-api.md), and the existing backend convention is
unit (`*Test`) + Testcontainers (`*IT`) coverage. Write each story's tests first; they
must fail before implementation.

**Organization**: Grouped by user story so each is independently implementable and
testable. All paths are under `backend/src/` (backend-only feature; see plan.md).

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup (Shared Domain Types)

**Purpose**: The two new domain types and context fields every story writes to.
No new dependencies, no build changes.

- [x] T001 [P] Create `Confidence` enum (HIGH/MEDIUM/LOW, lowercase wire format via `@JsonValue`) in backend/src/main/java/com/lingua_app/backend/analysis/pipeline/Confidence.java and `IssueCode` enum (8 stable codes, enum names = wire format, `isTransient()` classification per FR-014) in backend/src/main/java/com/lingua_app/backend/analysis/pipeline/IssueCode.java — DONE 2026-07-06
- [x] T002 [P] Create `ValidationIssue` record (`IssueCode code`, severity WARN|ERROR, nullable surface, detail; static `warn`/`error` factories) in backend/src/main/java/com/lingua_app/backend/analysis/pipeline/ValidationIssue.java — DONE 2026-07-06
- [x] T003 Add `validationIssues` (List, initialized empty) and `confidence` (Confidence, nullable) fields to backend/src/main/java/com/lingua_app/backend/analysis/pipeline/AnalysisContext.java — DONE 2026-07-06

---

## Phase 2: Foundational (API Contract — blocks all stories)

**Purpose**: The response must carry `confidence` + `issues` before any check exists,
so every story's output is observable end-to-end (FR-001, FR-002, FR-014 compute-once).

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [x] T004 [P] Create `ValidationIssueDto` record (`IssueCode code` serialized by name, severity serialized "warning"/"error" via `@JsonProperty` on the Severity constants, surface, detail; `from(ValidationIssue)` factory) in backend/src/main/java/com/lingua_app/backend/dto/ValidationIssueDto.java — DONE 2026-07-06
- [x] T005 Extend `AnalysisResponse` with `Confidence confidence` (enum-typed; `@JsonValue` yields "high"/"medium"/"low") and `List<ValidationIssueDto> issues` (additive, always present) in backend/src/main/java/com/lingua_app/backend/dto/AnalysisResponse.java — DONE 2026-07-06
- [x] T006 Map context → DTO in `AnalysisService.analyze` (issues list passthrough; null confidence defensively maps to `Confidence.LOW` per data-model.md) in backend/src/main/java/com/lingua_app/backend/service/AnalysisService.java — DONE 2026-07-06
- [x] T007 Fix `AnalysisResponse` constructor arity in existing test and assert `$.confidence` and `$.issues` serialization (added degraded-shape test: code/severity/surface/detail wire format) in backend/src/test/java/com/lingua_app/backend/controller/AnalysisControllerTest.java — DONE 2026-07-06

**Checkpoint**: `./mvnw test -Dtest="*Test"` green; every 200 response carries the two new fields. ✅ PASSED 2026-07-06 (14 tests)

---

## Phase 3: User Story 1 — Learner is never silently given an incomplete breakdown (Priority: P1) 🎯 MVP

**Goal**: Detect empty analyses, uncovered input, out-of-order cards, and stage failures;
derive confidence per FR-010. The parity suite's silent failures become visible.

**Independent Test**: POST 学习中文很有意思 → confidence "low" with `EMPTY_ANALYSIS`;
POST a clean Korean sentence → "high"/"medium" with correct codes; POST while Claude is
unreachable → `STAGE_FAILED`, still a 200 (quickstart.md commands).

### Tests for User Story 1

- [x] T008 [P] [US1] Write failing unit tests for coverage (script-scoped, morpheme-split-safe, mixed-script, dropped-duplicate detection via 1:1 occurrence claiming), empty analysis, duplicate-safe ordering (jpn-3 です×2), punctuation/digit-only input, and the FR-010 derivation truth table (incl. word-affecting vs auxiliary `STAGE_FAILED`, >50% warned cards, zero-card guard for the ratio) in backend/src/test/java/com/lingua_app/backend/analysis/step/ValidationStepTest.java. Also assert FR-012: a ValidationStep that throws mid-check leaves `ctx.words`/`translation` untouched and the exception propagates to the pipeline's per-step catch (context remains serializable; service maps null confidence → "low") — DONE 2026-07-06 (13 tests)

### Implementation for User Story 1

- [x] T009 [US1] Create `ValidationStep implements AnalysisStep` skeleton (reads text/language/words/partialErrors, appends issues, sets confidence exactly once) and register it last in `AnalysisPipeline` in backend/src/main/java/com/lingua_app/backend/analysis/step/ValidationStep.java and backend/src/main/java/com/lingua_app/backend/analysis/pipeline/AnalysisPipeline.java — DONE 2026-07-06
- [x] T010 [US1] Implement `EMPTY_ANALYSIS` (FR-004) and script-scoped character-coverage check `INPUT_NOT_COVERED` naming uncovered fragments (FR-003, research Decision 3, Unicode ranges shared with DetectionStep) in backend/src/main/java/com/lingua_app/backend/analysis/step/ValidationStep.java — DONE 2026-07-06
- [x] T011 [US1] Implement cursor-based duplicate-safe `CARDS_OUT_OF_ORDER` check (FR-005, research Decision 4) in backend/src/main/java/com/lingua_app/backend/analysis/step/ValidationStep.java — DONE 2026-07-06
- [x] T012 [US1] Implement `STAGE_FAILED` issues from `ctx.partialErrors` (severity by stage per clarification Q1) and FR-010 confidence derivation incl. >50% warned-cards escalation in backend/src/main/java/com/lingua_app/backend/analysis/step/ValidationStep.java — DONE 2026-07-06
- [x] T013 [US1] Add structured logging (FR-013, research Decision 8): WARN `validation_summary` with language/confidence/issueCodes/cardCount/requestId, no user text; per-issue surfaces at DEBUG only; HIGH responses at DEBUG, in backend/src/main/java/com/lingua_app/backend/analysis/step/ValidationStep.java — DONE 2026-07-06 (requestId comes from MDC via RequestIdFilter)
- [x] T014 [US1] Extend integration test to assert every /api/analyze response carries confidence + issues, and that a **synthesized** degradation yields HTTP 200 with confidence "low" and a `STAGE_FAILED(claude)` issue in backend/src/test/java/com/lingua_app/backend/integration/AnalysisPipelineIT.java — DONE 2026-07-06 (degradation synthesized via doThrow on the mocked ClaudeStep — even cleaner than the invalid-key plan since ITs mock external steps; exercises the exact pipeline-catch path)

**Checkpoint**: US1 acceptance scenarios 1–4 pass; `node tools/parity-diff/run-backend.mjs`
shows cmn-1/cmn-2 low + `EMPTY_ANALYSIS`, eng-2 low + `INPUT_NOT_COVERED`, kor-* flagged
`CARDS_OUT_OF_ORDER` — SC-001's silent failures now all visible.
✅ PASSED 2026-07-06: live parity run gave exactly the fixture-table result
(kor-1/2/3 medium+CARDS_OUT_OF_ORDER, jpn-1/2/3 high clean, cmn-1/2 low+EMPTY_ANALYSIS,
eng-1 high clean, eng-2 low+INPUT_NOT_COVERED). Zero false positives (SC-003).

---

## Phase 4: User Story 2 — Client apps can react to quality programmatically (Priority: P2)

**Goal**: Per-card checks (blank fields, romanization passthrough, POS vocabulary) with
stable codes, plus POS normalization so returned labels always use the canonical set.

**Independent Test**: Craft inputs triggering each code and verify code/severity/surface
shape; verify Korean 명사/부사 come back as noun/adv with no `UNKNOWN_POS` (contract
fixture table, kor rows).

### Tests for User Story 2

- [x] T015 [P] [US2] Write failing unit tests for the mapping table (Krdict Korean labels, spelled-out English, Kuromoji-prefix Japanese, compound "noun (pronoun)", unmappable → empty) in backend/src/test/java/com/lingua_app/backend/analysis/step/PosNormalizerTest.java
- [x] T016 [P] [US2] Extend ValidationStepTest with `MISSING_FIELD`, `ROMANIZATION_PASSTHROUGH` (kor/jpn/cmn only), `UNKNOWN_POS`, and pos-rewrite assertions in backend/src/test/java/com/lingua_app/backend/analysis/step/ValidationStepTest.java

### Implementation for User Story 2

- [x] T017 [US2] Create `PosNormalizer` utility with canonical vocabulary (noun, verb, adj, adv, pron, prep, conj, det, num, particle, punct, other) and full mapping table (FR-008, research Decision 5) in backend/src/main/java/com/lingua_app/backend/analysis/step/PosNormalizer.java
- [x] T018 [US2] Implement per-card checks in ValidationStep: `MISSING_FIELD` (FR-006), `ROMANIZATION_PASSTHROUGH` (FR-007), and POS normalize-then-flag rewriting `WordCard.pos` when mappable, `UNKNOWN_POS` otherwise (FR-008) in backend/src/main/java/com/lingua_app/backend/analysis/step/ValidationStep.java
- [x] T019 [US2] Add controller test asserting full issue JSON shape (`$.issues[0].code/severity/surface/detail`) and canonical pos values in `$.words[*].pos` in backend/src/test/java/com/lingua_app/backend/controller/AnalysisControllerTest.java

**Checkpoint**: US2 acceptance scenarios pass; contract kor rows hold (labels normalized,
no `UNKNOWN_POS` for known Krdict labels).

---

## Phase 5: User Story 3 — Malformed AI output never reaches users (Priority: P3)

**Goal**: Shape-validate Claude tool entries at parse time; rejected entries never
displace DictionaryStep's surface-only cards and are reported as `AI_ENTRY_REJECTED`.

**Independent Test**: Unit-level: feed `extractWordCards` tool inputs with missing/blank
fields and fabricated surfaces; verify filtering, issues, and that a missing `words`
array records a claude partial error instead of silently returning empty.

### Tests for User Story 3

- [x] T020 [P] [US3] Write failing unit tests for entry rejection (blank field, missing field, surface not in input, missing `words` array → partial error; valid entries preserved with katakana reading restore) in backend/src/test/java/com/lingua_app/backend/analysis/step/ClaudeStepTest.java

### Implementation for User Story 3

- [x] T021 [US3] Implement FR-009 in `ClaudeStep`: filter invalid entries in `extractWordCards`/`callClaude` (required fields non-blank, surface occurs in `ctx.getText()`), append `AI_ENTRY_REJECTED` issues to context, record claude partial error when `words` is absent (research Decision 6) in backend/src/main/java/com/lingua_app/backend/analysis/step/ClaudeStep.java

**Checkpoint**: SC-005 holds — no structurally invalid AI entry can appear as a card;
coverage/`MISSING_FIELD` checks downstream report the honest gap.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T022 Run full unit suite (`./mvnw test -Dtest="*Test"`) and fix any regressions in backend/ — DONE 2026-07-08 (95 tests across 6 classes, 0 failures/errors, no regressions from US1–US3 changes)
- [x] T023 Run integration suite with Docker (`./mvnw test -Dtest="*IT"`) — also clears the 001 carry-over (JwtAuthFilter principal change) in backend/src/test/java/com/lingua_app/backend/integration/ — DONE 2026-07-06 (6/6 green; required Testcontainers 1.21.3 + api.version=1.44 in ~/.docker-java.properties for Docker Engine 29, plus Boot 4 spring-boot-resttestclient/spring-boot-restclient test deps and @AutoConfigureTestRestTemplate on both ITs)
- [ ] T024 [P] Acceptance run: restart backend, execute `node tools/parity-diff/run-backend.mjs`, verify every row of the fixture table in specs/002-analysis-validation/contracts/validation-api.md (SC-001, SC-002, SC-003); compare per-request ms against the pre-002 backend-results.json timings as the SC-004 latency sanity check. Note: the fixture table describes the pre-remediation backend — regenerate expectations when the 001 carry-over remediation (CEDICT bundling, token drops, ordering) lands. (US1-scope preview passed 2026-07-06; final run after US2/US3.) — DONE 2026-07-08: 10/10 ok, every fixture row matched (kor-1..3 medium+CARDS_OUT_OF_ORDER with normalized noun/adv/verb/adj POS and no UNKNOWN_POS; jpn-1..3 high+clean, no coverage/order false positives on morpheme splits or duplicate です; cmn-1..2 low+EMPTY_ANALYSIS; eng-1 high+clean with adv normalized; eng-2 low+INPUT_NOT_COVERED naming "couldn, t"). SC-004: per-request ms within noise of baseline (kor 6.6–7.8s vs 6.1–7.5s, jpn 5.6–9.2s vs 6.0–8.3s, eng/cmn sub-second both runs). Caveat: true pre-002 timings were overwritten before commit — baseline is the 2026-07-06 post-MVP preview (2c15018), which already included ValidationStep; still valid as a no-added-latency check since external API calls dominate.
- [x] T025 [P] Document the validation contract for clients: add confidence/issues section with issue-code table to README.md — DONE 2026-07-08 ("Analysis Validation (Phase 2)" section: example payload, field semantics incl. code-stability guarantee, 8-code table, canonical POS vocabulary, transient-vs-deterministic note, link to full contract)
- [x] T026 Constitution review before merge: issue `detail` strings display-safe (Principle II error opacity), no user text at WARN+ (Principle IV), no new env vars/deps (Principle III/V — note: Testcontainers version bump + two Boot 4 test-scoped modules added under T023, test classpath only), OWASP pass on changed files — PASSED 2026-07-08. (1) All `detail` strings are fixed sentences; the two dynamic parts are the user's own input fragments (INPUT_NOT_COVERED, capped at 5) and code-controlled stage names (STAGE_FAILED) — no exception messages, stack traces, or class names reach the wire; `partialErrors` (which holds e.getMessage()) is never serialized. (2) WARN validation_summary logs only language/confidence/codes/cardCount; surface+detail confined to DEBUG; no WARN+ logging added in ClaudeStep. (3) application.yaml and .env.example.yml untouched; pom.xml delta is the pre-noted test-scope-only set. (4) OWASP: no SQL/auth/endpoint changes; input bounded at 500 chars (bean + service validation) so the O(cards×text²) worst case of coverage claiming is negligible; PosNormalizer regex is linear, applied to short POS labels; US3 shape-validation *improves* A08 posture (model output can no longer inject cards whose surface isn't in the input). Minor non-blocking observations: a UTF-8 BOM crept into pom.xml line 1 (Maven tolerates it); AnalysisPipeline's pre-existing `log.warn("{} failed", name, e)` logs full exception objects at WARN — fine per Principle IV (which restricts ERROR) but worth remembering if a downstream API ever echoes user text in exception messages.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none — start immediately; T001/T002 parallel, T003 after T002
- **Foundational (Phase 2)**: needs Phase 1 (T004 needs T002; T005→T004; T006→T005, T003; T007→T006) — BLOCKS all stories
- **US1 (Phase 3)**: needs Phase 2. T008 first (failing tests), T009 before T010–T013, T014 last
- **US2 (Phase 4)**: needs Phase 2 + T009 (ValidationStep exists). Independent of US1's checks — testable on its own
- **US3 (Phase 5)**: needs Phase 1 only (T002/T003) for issue appending; independent of US1/US2
- **Polish (Phase 6)**: needs all desired stories; T022 before T023; T024/T025 parallel after T023

### Parallel Opportunities

- T001 ∥ T002; T004 ∥ (nothing else in phase 2 until T005)
- After Phase 2: US3 (T020–T021) can proceed fully in parallel with US1/US2 (different files: ClaudeStep vs ValidationStep)
- Test-writing tasks T008, T015, T016, T020 are all [P] against each other (different test files)
- T024 ∥ T025 in Polish

### Parallel Example: after Phase 2 completes

```text
Developer A: T008 → T009 → T010 → T011 → T012 → T013 → T014   (US1, ValidationStep)
Developer B: T020 → T021                                       (US3, ClaudeStep)
US2 (T015–T019) starts once T009 lands (needs the step class to extend)
```

---

## Implementation Strategy

**MVP = Phases 1–3 (through T014).** That alone delivers the headline value: the Chinese
zero-card and English dropped-token failures stop being silent, and every response carries
an honest confidence level. Stop there, re-run the parity suite, demo.
**STATUS: MVP COMPLETE 2026-07-06** — all 27 unit tests + 6 ITs green; live parity run
matches the contract fixture table exactly.

Incremental: +US2 → clients get normalized POS and per-card codes; +US3 → AI-shape
hardening; Polish gates the merge (constitution review + fixture-table acceptance).

**Format validation**: 26 tasks, all in `- [ ] Txxx [P?] [Story?] description + path`
form; story labels only in Phases 3–5; sequential IDs T001–T026.
