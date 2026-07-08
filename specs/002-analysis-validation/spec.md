# Feature Specification: Analysis Validation & Confidence Level

**Feature Branch**: `002-analysis-validation`
**Created**: 2026-07-06
**Status**: Draft
**Input**: User description: "Add a rule-based validation/confidence level for analysis results. A side-by-side comparison of the original extension and the backend found silent quality failures: entire languages returning zero word cards, input words dropped without notice, word cards returned out of input order, mixed word-class labeling vocabularies, pronunciation output that disagrees between systems, and unvalidated AI responses merged into results. The validation layer must catch these with cheap rule-based sanity checks — no second AI model, no additional paid API calls — and report an overall confidence level plus machine-readable issues on every analysis response."

## Clarifications

### Session 2026-07-06

- Q: How should confidence map when an auxiliary stage (translation, romanization) fails but the word-card analysis is complete? → A: Word-affecting failures (dictionary/AI analysis) → low; auxiliary failures (translation, romanization) → medium. (Language-detection failure is classified word-affecting during design: an unknown language invalidates every downstream check.)
- Q: Should validation flag every word-class label not literally in the documented vocabulary, or normalize known equivalents first? → A: Normalize known equivalent labels (e.g., 명사→noun, "adjective"→adjective) into the documented vocabulary; flag only labels that cannot be mapped.
- Q: Should a high proportion of warning-affected cards escalate confidence to low? → A: Yes — if more than 50% of cards carry at least one warning-level issue, confidence is low.
- Q: How should confidence interact with the planned response cache? → A: Confidence and issues are stored inside the cached response; cache eligibility is derived from issue type — transiently-degraded responses (stage failures) get at most a short negative-cache retention, deterministically-degraded and clean responses cache normally.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Learner is never silently given an incomplete breakdown (Priority: P1)

A language learner submits a sentence for analysis. If the returned word breakdown is incomplete — words from their sentence are missing, cards are empty, or the whole breakdown failed — the response openly says so instead of presenting the partial result as if it were complete. The learner (via the app) can tell the difference between "this analysis is trustworthy" and "this analysis is degraded."

**Why this priority**: This is the core trust problem found in the comparison study: Chinese sentences returned zero word cards and an English contraction vanished from results, all reported as apparent successes. A learner studying from silently wrong cards is worse off than a learner told the analysis failed.

**Independent Test**: Submit a sentence known to produce dropped words (e.g., a Chinese sentence today) and verify the response carries a low confidence level and an issue identifying the uncovered input. Submit a sentence known to analyze cleanly and verify it reports high confidence with no issues.

**Acceptance Scenarios**:

1. **Given** a sentence where one or more input words end up absent from the word cards, **When** the analysis completes, **Then** the response confidence is not "high" and an issue identifies that input was not covered.
2. **Given** a non-empty sentence for which zero word cards are produced, **When** the analysis completes, **Then** the response confidence is "low" and an issue states the analysis is empty.
3. **Given** a sentence that analyzes completely (every word covered, all cards populated), **When** the analysis completes, **Then** the response confidence is "high" and the issue list is empty.
4. **Given** word cards that come back in a different order than the words appeared in the sentence, **When** the analysis completes, **Then** an issue flags the ordering problem.

---

### User Story 2 - Client apps can react to quality programmatically (Priority: P2)

The mobile app (and any future client) receives, with every analysis, an overall confidence level and a machine-readable list of issues (stable code, severity, the affected word if any). The client can show a "results may be incomplete" notice, offer a retry, or hide suspect cards — without parsing human-oriented text.

**Why this priority**: The confidence level is only useful if clients can act on it. Stable codes make the contract testable and keep client behavior decoupled from message wording.

**Independent Test**: Trigger each distinct issue type with a crafted input and verify each is reported with its documented code, a severity, and (where applicable) the affected word.

**Acceptance Scenarios**:

1. **Given** any analysis response, **When** a client inspects it, **Then** it finds a confidence level that is exactly one of three documented values (high / medium / low).
2. **Given** a degraded analysis, **When** the client reads the issue list, **Then** every issue has a documented stable code, a severity (warning or error), and identifies the affected word when the problem is word-specific.
3. **Given** a card whose word-class label falls outside the documented label set, or whose pronunciation guide is just the original word repeated back, **When** the analysis completes, **Then** a warning-level issue identifies that card.

---

### User Story 3 - Malformed AI output never reaches users (Priority: P3)

When the AI analysis service returns output that is structurally invalid — missing required fields, empty values, or words that do not appear in the submitted sentence — those entries are rejected before being merged into results, and their rejection is reflected in the confidence level rather than surfacing as broken cards.

**Why this priority**: The AI response is the one externally-generated structure already being paid for; validating its shape costs nothing extra. Lower priority only because malformed AI output is rarer than the deterministic gaps in stories 1–2.

**Independent Test**: Simulate an AI response with missing/blank fields and fabricated words; verify invalid entries are excluded from results and the response reports reduced confidence with a corresponding issue.

**Acceptance Scenarios**:

1. **Given** an AI response entry missing a required field or containing an empty value, **When** results are assembled, **Then** that entry is not shown as a word card and an issue records the rejection.
2. **Given** an AI response entry whose word does not occur in the submitted sentence, **When** results are assembled, **Then** the entry is rejected and flagged.

---

### Edge Cases

- Input consisting solely of punctuation, digits, or symbols: no coverage issue should fire for characters that carry no linguistic content.
- The same word occurring multiple times in a sentence (e.g., Japanese です … です): repeated occurrences must not falsely trigger ordering or coverage issues.
- Single-word input: must validate the same as a full sentence.
- Analysis service entirely unavailable (existing graceful degradation): confidence must be low; the response must still be returned.
- Mixed-script input (e.g., a Korean sentence containing a Latin brand name): coverage rules apply only to characters of the detected language's script(s); foreign-script fragments must not force a false coverage failure.
- Words legitimately split into multiple cards (morpheme-level breakdowns): coverage is judged on the original characters being present across cards, not on one-card-per-word.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Every analysis response MUST include an overall confidence level with exactly three possible values: high, medium, low.
- **FR-002**: Every analysis response MUST include a (possibly empty) list of validation issues; each issue MUST carry a stable documented code, a severity of warning or error, an optional affected word, and a human-readable detail.
- **FR-003**: The system MUST detect when linguistically meaningful characters of the input are not covered by any returned word card and report an error-level coverage issue naming the missing portion.
- **FR-004**: The system MUST detect a non-empty input that yields zero word cards and report it as an error-level issue.
- **FR-005**: The system MUST detect word cards returned out of input order and report a warning-level issue. Repeated words must not cause false positives.
- **FR-006**: The system MUST flag word cards whose essential learning fields (base form, meaning) are missing or blank with a warning-level issue identifying the card.
- **FR-007**: The system MUST flag pronunciation guidance that is identical to the original word (script passthrough) for languages that require transliteration, with a warning-level issue.
- **FR-008**: The system MUST normalize word-class labels into the single documented label vocabulary wherever an equivalent is known (e.g., native-language labels, spelled-out variants), so returned cards always use the documented vocabulary; labels with no known mapping MUST be flagged with a warning-level issue and passed through unchanged.
- **FR-009**: The system MUST structurally validate AI-generated analysis entries before merging them into results: required fields present and non-blank, and the word actually occurring in the submitted sentence. Invalid entries MUST be rejected and their rejection reported as an issue.
- **FR-010**: The confidence level MUST be derived deterministically: any error-level issue, a failure in a word-affecting pipeline stage (dictionary lookup, AI analysis), or warning-level issues affecting more than 50% of word cards → low; a failure in an auxiliary stage (translation, romanization) or any warning-level issue (≤50% of cards) → medium; no issues and no stage failures → high.
- **FR-011**: Validation MUST be entirely rule-based: no additional AI/model invocations and no additional paid external service calls may be made to compute confidence or issues.
- **FR-012**: Validation MUST NOT block or replace results: a degraded analysis is still returned, with its confidence and issues attached.
- **FR-013**: All detected issues MUST be recorded in server logs with enough context (language, issue code, affected word) to aggregate quality trends per language.
- **FR-014**: Confidence and issues MUST be part of the analysis result itself (not recomputed per request), so any future caching of results returns the confidence computed when the analysis ran. When response caching is introduced, cache eligibility MUST derive from issue type: responses degraded by transient causes (failed pipeline stages) MUST NOT be retained beyond a short negative-cache period, while clean responses and responses degraded only by deterministic causes (e.g., unmappable labels) MAY be cached normally. Each issue code MUST therefore be documented as transient or deterministic.

### Key Entities

- **Confidence Level**: An overall trust rating for one analysis response; one of high, medium, low; derived only from validation issues and pipeline stage failures.
- **Validation Issue**: One detected quality problem; attributes: stable code, severity (warning | error), optional affected word, human-readable detail. Belongs to exactly one analysis response.
- **Analysis Response** (existing): Extended to carry the confidence level and the list of validation issues alongside the translation and word cards.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Re-running the 10-sentence, 4-language comparison suite that motivated this feature: 100% of the previously silent failures (empty Chinese results, dropped English word, out-of-order Korean cards, mixed label vocabularies) are reported with at least one appropriate issue code — zero silent failures remain.
- **SC-002**: 100% of analysis responses carry a confidence level and issue list; clients never need to infer quality from absence of data.
- **SC-003**: Analyses that complete cleanly report high confidence with zero issues (no false alarms on the known-good sentences of the comparison suite).
- **SC-004**: Users experience no perceptible slowdown: validation adds no external service calls and no measurable latency relative to the existing analysis flow.
- **SC-005**: A structurally invalid AI entry (missing fields or fabricated word) never appears as a word card in any response.

## Assumptions

- Scope is the four currently supported languages (Korean, Japanese, Chinese, English); the rules are language-agnostic except where noted (script passthrough, script-based coverage).
- Validation observes and reports; it does not attempt repair, re-analysis, or automatic retry of degraded results. The single exception is word-class label normalization (FR-008), which is a deterministic mapping, not re-analysis. Automatic re-analysis of low-confidence responses is a possible future feature, out of scope here.
- Fixing the root causes the comparison uncovered (missing Chinese dictionary data, dropped English tokens, card ordering) is separate remediation work; this feature is the detection layer that proves those fixes and prevents regressions.
- The existing analysis response contract can be extended additively (new fields) without breaking current clients.
- The documented word-class label vocabulary will be the one already used by the original extension (noun, verb, adjective, adverb, pronoun, preposition, conjunction, determiner, numeral, particle, punctuation, other), since clients already understand it.
- Per-card confidence scoring is out of scope for v1; confidence is per-response, with per-card problems expressed through issues.
- Response caching does not exist yet (planned as a later feature). FR-014 defines the contract that feature must honor; implementing the cache itself is out of scope here, but the transient/deterministic classification of each issue code is delivered now as part of the issue-code documentation.
