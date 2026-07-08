# Research: Analysis Validation & Confidence Level

**Feature**: 002-analysis-validation | **Date**: 2026-07-06

No NEEDS CLARIFICATION markers remained after `/speckit-clarify` (4 questions resolved in
spec Clarifications). This document records the design decisions for the implementation
approach, each grounded in the parity study (`tools/parity-diff/parity-report.md`) that
motivated the feature.

## Decision 1: Validation as a pipeline step, not a service decorator

**Decision**: Implement validation as a `ValidationStep implements AnalysisStep`,
registered last in `AnalysisPipeline` (after `RomanizationStep`).

**Rationale**: The pipeline already has exactly the right execution semantics: steps run
in order, each wrapped in try/catch with failures recorded in `ctx.partialErrors` — so a
bug in validation itself can never 500 a response (FR-012, Principle IV). Validation needs
`ctx.text`, `ctx.detectedLanguage`, `ctx.words`, and `ctx.partialErrors`, all of which are
already on `AnalysisContext`. Running last guarantees it sees the final card list,
including Claude backfills and romanization.

**Alternatives considered**: (a) Validating in `AnalysisService.analyze()` after
`pipeline.run()` — works, but loses the free try/catch isolation and splits pipeline
concerns across layers. (b) Aspect/decorator around the pipeline — more machinery for the
same result; violates Principle V (no premature abstraction).

## Decision 2: Issue-code catalog with transient/deterministic classification

**Decision**: Eight stable codes, each classified per FR-014:

| Code | Severity | Class | Fires when |
|---|---|---|---|
| `EMPTY_ANALYSIS` | error | deterministic¹ | Non-empty input produced zero cards (FR-004) |
| `INPUT_NOT_COVERED` | error | deterministic¹ | Meaningful input chars absent from all card surfaces (FR-003) |
| `CARDS_OUT_OF_ORDER` | warning | deterministic | Card sequence not monotonic in input position (FR-005) |
| `MISSING_FIELD` | warning | deterministic | Card lemma or gloss null/blank (FR-006) |
| `ROMANIZATION_PASSTHROUGH` | warning | deterministic | Romanization equals surface for kor/jpn/cmn (FR-007) |
| `UNKNOWN_POS` | warning | deterministic | POS label unmappable to canonical vocabulary (FR-008) |
| `AI_ENTRY_REJECTED` | warning | deterministic | Claude entry dropped: missing/blank field or surface not in input (FR-009) |
| `STAGE_FAILED` | error/warning² | **transient** | A pipeline step threw (from `ctx.partialErrors`) |

¹ When `EMPTY_ANALYSIS`/`INPUT_NOT_COVERED` co-occur with `STAGE_FAILED`, the response is
transient-degraded for cache purposes — `STAGE_FAILED`'s classification wins (a Claude
outage that empties the analysis should not be cached as if it were a code bug).
² `STAGE_FAILED` severity by stage: dictionary/claude/detection = error (word-affecting);
translation/romanization = warning (auxiliary) — per clarification Q1.

**Rationale**: One code per FR-numbered check keeps the contract testable (SC-001 maps
each parity-study failure to exactly one expected code). The transient/deterministic
column is the FR-014 deliverable the future cache feature consumes.

**Alternatives considered**: Free-form message strings (rejected: FR-002 requires stable
codes); per-check numeric scores (rejected: spec fixes a three-level scale, and scores
invite false precision).

## Decision 3: Coverage algorithm (FR-003) — script-scoped character marking

**Decision**: Each card claims exactly **one** occurrence of its surface (1:1 claiming):
walk cards in order, seek the first *unclaimed* occurrence of the surface
(`String.indexOf` from a scan position, falling back to any unclaimed occurrence), and
mark that span covered. Then count uncovered characters that belong to the detected
language's script ranges — reusing the same Unicode ranges as `DetectionStep` (Hangul
blocks for kor; Kana + CJK for jpn; CJK for cmn; A–Z/a–z for lat). Whitespace,
punctuation, digits, and foreign-script characters never count as uncovered.

1:1 claiming (rather than marking every occurrence of each surface) is deliberate: if a
repeated word loses one of its cards ("the … the" with a single `the` card), the second
occurrence stays unclaimed and `INPUT_NOT_COVERED` fires. All-occurrence marking would
mask exactly the dropped-token failure class this check exists to catch.

**Rationale**: Character-level coverage (not token-level) is what makes morpheme splits
legal (jpn edge case: 行きました → 行き+まし+た covers all chars) while still catching real
drops (cmn-1: 0/12 chars covered; eng-2: "couldn't" chars uncovered). Script-scoping
implements the mixed-script edge case from the spec for free. Cost: O(cards × input) with
input ≤ 500 chars — microseconds.

**Alternatives considered**: Token-level set comparison (rejected: false positives on
every Kuromoji morpheme split); Levenshtein/alignment (rejected: complexity unjustified —
Principle V).

## Decision 4: Order check (FR-005) — cursor-based monotonic scan

**Decision**: Walk cards in order keeping a cursor into the input. For each surface, seek
`indexOf(surface, cursor)`; on hit, advance cursor to match start. On miss, retry from 0 —
if found before the cursor, count an order violation (card appears earlier than the
previous card's position).

**Rationale**: Duplicate-safe (jpn-3's two です cards resolve to successive occurrences,
no false positive — spec edge case) and single-pass. Validated in the parity harness
(`tools/parity-diff/diff.mjs`), which used this exact approach to find the 4 real
violations.

**Alternatives considered**: Sorting cards by first `indexOf` and comparing (rejected:
first-occurrence collapses duplicates and false-flags them).

## Decision 5: POS normalization (FR-008) — static map utility, applied to output

**Decision**: `PosNormalizer` with the canonical vocabulary
`noun, verb, adj, adv, pron, prep, conj, det, num, particle, punct, other` (the
extension's `VALID_POS` set plus `particle`, per spec Assumptions). Mapping table covers:
Korean Krdict labels (명사→noun, 대명사→pron, 동사→verb, 형용사→adj, 부사→adv, 조사→particle,
수사→num, 관형사→det, 감탄사→other), spelled-out English (adjective→adj, adverb→adv,
pronoun→pron, preposition→prep, conjunction→conj, determiner/article→det,
numeral/number→num, punctuation→punct, auxiliary→verb, interjection→other,
postposition→particle), Kuromoji prefixes (名詞→noun, 動詞→verb, 助詞→particle,
形容詞→adj, 副詞→adv, 助動詞→verb, 記号→punct, 連体詞→det, 接続詞→conj, 感動詞→other),
and compound forms matched on their head (e.g. "noun (pronoun)" → matched on first token).
`ValidationStep` rewrites each card's `pos` to the normalized value when mappable
(clarification Q2) and emits `UNKNOWN_POS` otherwise, leaving the label unchanged.

**Rationale**: The parity study showed the same response mixing 명사 and `noun`; the
mobile client should never need its own mapping table. Normalize-then-flag was decided in
clarification Q2. The map lives in one utility so `DictionaryStep` can adopt it at the
source later (remediation work) without contract change.

**Alternatives considered**: Strict flagging without normalization (rejected in Q2 — every
Korean sentence would be medium); normalizing inside each source step now (rejected: that
is remediation scope; validation must ship independently).

## Decision 6: Claude output schema validation (FR-009) — filter at parse time in ClaudeStep

**Decision**: In `ClaudeStep.extractWordCards`, reject entries where `surface`, `lemma`,
`pos`, or `gloss` is missing/blank, or where `surface` does not occur in `ctx.getText()`.
Rejected entries become `AI_ENTRY_REJECTED` issues on the context (one per entry, surface
included when parseable). A response with no `words` array records a `STAGE_FAILED`-style
partial error for the claude stage instead of today's silent `List.of()`.

**Rationale**: This is the one external structure already paid for (User Story 3); the
existing code silently trusts it (`mapToWordCard` casts nullable fields). Filtering at
parse time means invalid entries never displace the surface-only cards DictionaryStep
seeded — the unresolved token stays visible and coverage/`MISSING_FIELD` checks report it
honestly downstream.

**Alternatives considered**: JSON-Schema library validation (rejected: new dependency for
five field checks — Principle V); throwing on any invalid entry (rejected: FR-012/
Principle IV prefer partial results).

## Decision 7: Confidence derivation (FR-010) — pure function, evaluated last

**Decision**: In `ValidationStep`, after all checks:
`LOW` if any error-severity issue, any word-affecting `STAGE_FAILED`
(dictionary/claude/detection), or > 50% of cards carry ≥ 1 warning;
`MEDIUM` if any warning-severity issue or auxiliary `STAGE_FAILED`
(translation/romanization); else `HIGH`. Stored on `AnalysisContext` as an enum,
serialized lowercase (`high|medium|low`).

**Rationale**: Direct transcription of FR-010 + clarifications Q1/Q3. Deterministic and
trivially unit-testable as a truth table.

**Alternatives considered**: Weighted scoring (rejected: spec mandates three discrete
levels derived from issues).

## Decision 8: Logging (FR-013) under Principle IV constraints

**Decision**: One structured WARN log per degraded response:
`validation_summary {language, confidence, issueCodes[], cardCount, requestId}` — no user
text. Per-issue detail including the affected surface logs at DEBUG only. Clean (HIGH)
responses log the same summary at DEBUG.

**Rationale**: Satisfies FR-013 aggregation (language × code trends from WARN stream)
while honoring the constitution's "never log user text content at ERROR level" rule —
extended conservatively to WARN, since issue surfaces are verbatim user input.

**Alternatives considered**: Metrics registry counters (Micrometer) — deferred; the
structured log stream is sufficient for trend aggregation now, and no metrics stack exists
yet (Principle V).
