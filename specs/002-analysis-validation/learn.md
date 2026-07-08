# What I Learned: Analysis Validation & Confidence Level

**Feature**: Rule-based validation layer that attaches a confidence level (high/medium/low) and machine-readable issue list to every analysis response, so silent quality failures become visible.
**Generated**: 2026-07-08
**Scope**: Full feature (implementation so far = Phases 1–3 MVP + integration suite)
**Implementation status**: 15/26 tasks completed (Setup, API contract, User Story 1, IT suite; US2/US3/polish pending)

---

## Key Decisions

### 1. Validation as a Pipeline Step, Not Service-Layer Logic

**What we did**: Implemented all checks as a `ValidationStep implements AnalysisStep`, registered **last** in `AnalysisPipeline` — the same abstraction the translation, dictionary, Claude, and romanization stages already use.

**Why**: The pipeline already wraps every step in a try/catch that records failures in `ctx.partialErrors` instead of throwing. By riding that machinery, a bug in validation itself can never turn a good analysis into a 500 — it degrades to "no confidence computed," which the service maps to `low`. Running last also guarantees validation sees the *final* card list, after Claude backfills and romanization.

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| Validate in `AnalysisService.analyze()` after `pipeline.run()` | Loses the free failure isolation and splits pipeline concerns across two layers |
| Aspect/decorator wrapped around the pipeline | More machinery for the same result — premature abstraction |

**When you'd choose differently**: If validation needed data the pipeline context doesn't carry (e.g., the raw HTTP request, or per-user history), the service layer would be the right home. The general lesson: before inventing a new extension point, check whether an existing abstraction already gives you the execution semantics (ordering, error isolation) you need for free.

---

### 2. Stable Issue Codes Instead of Message Strings

**What we did**: Defined exactly eight enum codes (`EMPTY_ANALYSIS`, `INPUT_NOT_COVERED`, `CARDS_OUT_OF_ORDER`, `MISSING_FIELD`, `ROMANIZATION_PASSTHROUGH`, `UNKNOWN_POS`, `AI_ENTRY_REJECTED`, `STAGE_FAILED`), one per functional requirement, with the enum name as the wire format. Human-readable `detail` text rides along but is explicitly *not* part of the contract.

**Why**: Clients need to *act* on quality problems (show a banner, offer retry, hide a card) — they can't do that by parsing prose. One code per FR also makes acceptance testing mechanical: the parity suite's fixture table maps each known failure to exactly one expected code.

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| Free-form message strings | Untestable contract; any wording change breaks clients |
| Per-check numeric scores | Spec fixed a three-level scale; numeric scores invite false precision |

**When you'd choose differently**: Codes are the right call whenever a machine consumes the output. Free-form text is fine only when a human is the sole consumer and no client logic branches on it. The commitment matters: codes are "never renamed, only added" — that's what makes them a contract.

---

### 3. Designing for a Feature That Doesn't Exist Yet (Cache Classification)

**What we did**: Every issue code carries an `isTransient()` flag even though response caching doesn't exist. `STAGE_FAILED` (a step threw — retry might succeed) is transient; everything else (unmappable label, dropped token) is deterministic — re-running won't change it.

**Why**: The future cache must not freeze a Claude-outage response for a day, but *should* cache a response degraded only by a deterministic gap (same input → same gap anyway). Deciding this classification now, while each code's semantics are fresh, is nearly free; retrofitting it later means re-deriving the reasoning. Note what was **not** built: no cache, no eligibility logic — only the one bit of metadata the cache will need.

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| Build the cache eligibility logic now | Cache is a later phase; speculative code rots |
| Defer classification entirely | The knowledge is cheapest to capture now; FR-014 makes it part of each code's documentation |

**When you'd choose differently**: This is the line between *forward-compatible contracts* (cheap, do it) and *speculative implementation* (expensive, don't). If the future feature's needs were uncertain — you're guessing at its design — defer the metadata too, because you'd probably guess wrong.

---

### 4. Character-Level Coverage with 1:1 Occurrence Claiming

**What we did**: To detect dropped input (FR-003), each card claims exactly **one** occurrence of its surface text in the input; then any *unclaimed* characters belonging to the detected language's script count as uncovered. Whitespace, punctuation, digits, and foreign-script characters are exempt.

**Why**: Character-level (not token-level) comparison makes morpheme splits legal — Japanese 行きました split into 行き+まし+た covers every character — while still catching real drops (Chinese sentences returning 0/12 covered characters). The 1:1 claiming rule is the subtle part: if "the … the" comes back with a single `the` card, all-occurrence marking would call it covered and mask exactly the dropped-duplicate failure class this check exists to catch.

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| Token-level set comparison | False positive on every Japanese morpheme split |
| Levenshtein/sequence alignment | Complexity unjustified for a sanity check on ≤500-char input |

**When you'd choose differently**: If inputs were long documents, the O(cards × input) scan and 1:1 claiming would need an index. And if the analyzer legitimately merged repeated tokens into one card by design, 1:1 claiming would be the wrong invariant. The transferable skill: pick the *unit of comparison* (character vs token vs edit-distance) by asking which false positives and false negatives each one produces against your known failure cases.

---

### 5. Confidence as a Pure Function, Derived Once

**What we did**: Confidence is a deterministic truth table evaluated after all checks: any error-level issue, word-affecting stage failure, or >50% of cards warned → `low`; any warning or auxiliary stage failure → `medium`; else `high`. It's computed once, stored on the context, and serialized with the response — never recomputed.

**Why**: A pure function of (issues, partialErrors) is trivially unit-testable as a truth table — [ValidationStepTest.java](../../backend/src/test/java/com/lingua_app/backend/analysis/step/ValidationStepTest.java) covers the FR-010 derivation exhaustively. Compute-once matters for the future cache: a cached response must return the confidence *from when the analysis ran*, not a fresh recomputation against changed rules.

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| Weighted numeric scoring | Three discrete levels were mandated; weights would need tuning and invite bikeshedding |
| Recompute confidence per request from stored issues | Breaks the "result is immutable once produced" property FR-014 depends on |

**When you'd choose differently**: Weighted scores earn their complexity when you have training data to calibrate against and consumers who need ranking rather than banding. With neither, discrete levels derived from explicit rules are more honest and infinitely easier to explain to a client developer.

---

### 6. Observe and Report — Never Block, Never Repair

**What we did**: A degraded analysis is still returned with HTTP 200; validation only annotates it (FR-012). The single exception is POS-label normalization, which is a deterministic string mapping, not re-analysis.

**Why**: The whole feature exists because *silent* failure is worse than *visible* failure — but a blocked response is worse than both: the learner gets nothing. Repair (automatic retry, re-analysis) is a separate future feature with its own cost model; bolting it on here would couple detection to remediation.

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| Reject/500 low-confidence responses | Learner gets nothing instead of a labeled partial result |
| Auto-retry degraded analyses | Doubles cost per failure, and retry policy deserves its own design |

**When you'd choose differently**: In domains where a wrong answer is dangerous (payments, medical dosing), blocking beats labeling. For a learning aid, a labeled partial result keeps the user productive. Always ask: what does the consumer do with a degraded result, and is "nothing" actually safer?

---

### 7. Logging Quality Signals Without Logging User Text

**What we did**: One structured WARN log per degraded response — `validation_summary {language, confidence, issueCodes[], cardCount, requestId}` — with **no user text**. The affected surfaces (verbatim user input) appear only at DEBUG. Clean responses log the same summary at DEBUG.

**Why**: FR-013 requires aggregating quality trends per language, which needs only language × code × count — not the sentences themselves. The constitution forbids user text at ERROR; this extends that conservatively to WARN because issue surfaces *are* user input. The requestId (from MDC via `RequestIdFilter`) lets an operator correlate a summary to a request without the log itself leaking content.

**Alternatives considered**:
| Approach | Why it wasn't chosen |
|----------|---------------------|
| Log full issue detail (with surfaces) at WARN | Puts verbatim user text in production log streams |
| Micrometer metrics counters | No metrics stack exists yet; structured logs already support the aggregation |

**When you'd choose differently**: Once a metrics stack exists, counters are cheaper to aggregate than log parsing — the structured-log approach is a deliberate stopgap. The durable lesson: decide *what question the log answers* (trends per language) and log the minimum fields that answer it.

---

## Concepts to Know

### Pipeline (Chain of Steps) with Per-Step Failure Isolation

**What it is**: A request flows through an ordered list of steps sharing a mutable context object; each step is individually wrapped in try/catch so one failure degrades the result instead of aborting the run.

**Where we used it**: `AnalysisPipeline` runs Detection → Translation → Dictionary → Claude → Romanization → **Validation**, all against one `AnalysisContext`; failures land in `ctx.partialErrors`, which validation then reads as its `STAGE_FAILED` input.

**Why it matters**: It gives you graceful degradation *structurally* rather than by remembering try/catch everywhere — and it means the failure record is data other steps can react to, which is exactly how confidence derivation consumes stage failures.

### Additive API Evolution

**What it is**: Extending a response contract only by adding fields (never renaming, removing, or changing the meaning of existing ones), so existing clients keep working by ignoring what they don't know.

**Where we used it**: `AnalysisResponse` gained `confidence` and `issues`; everything else is untouched. Both fields are *always present* (`issues: []` when clean) so clients never infer quality from a field's absence.

**Why it matters**: Once any client ships, breaking changes require coordinated deploys. "Always present, possibly empty" is the stricter and kinder variant — absent-vs-empty ambiguity is a classic source of client bugs.

### Enums as Wire Contracts (Domain Type vs DTO)

**What it is**: Keeping typed enums (`Confidence`, `IssueCode`) all the way through the code and controlling their JSON form at the boundary (`@JsonValue` for lowercase confidence; enum names as codes), with a thin DTO (`ValidationIssueDto`) separating the internal record from the response shape.

**Where we used it**: [Confidence.java](../../backend/src/main/java/com/lingua_app/backend/analysis/pipeline/Confidence.java), [IssueCode.java](../../backend/src/main/java/com/lingua_app/backend/analysis/pipeline/IssueCode.java), [ValidationIssueDto.java](../../backend/src/main/java/com/lingua_app/backend/dto/ValidationIssueDto.java).

**Why it matters**: Strings invite typos and drift ("High" vs "high"); an enum makes an invalid value unrepresentable internally while the DTO layer keeps you free to change internal names without breaking the wire format.

### An Executable Fixture Table as Acceptance Criteria

**What it is**: A concrete table of real inputs and their expected outputs (the 10-sentence, 4-language parity suite) that doubles as the feature's definition of done — rerun it and diff.

**Where we used it**: `tools/parity-diff/run-backend.mjs` plus the fixture table in contracts/validation-api.md; the US1 checkpoint passed when the live run matched the table row-for-row (Chinese rows low + `EMPTY_ANALYSIS`, eng-2 low + `INPUT_NOT_COVERED`, Korean rows flagged `CARDS_OUT_OF_ORDER`, zero false positives on clean rows).

**Why it matters**: "Detects quality problems" is unfalsifiable; "these 10 known-bad/known-good sentences produce exactly these codes" is a regression suite. When a feature is motivated by observed failures, encode those failures as fixtures *first* — they're the cheapest spec you'll ever write.

### Defensive Defaults at Trust Boundaries

**What it is**: When a value might be missing due to an internal failure, map it to the *safest* value, not the most optimistic one.

**Where we used it**: If `ValidationStep` itself crashes, `confidence` is null on the context; `AnalysisService` maps null → `low`. Also mid-check safety: a throwing validation step must leave `ctx.words`/`translation` untouched (asserted in ValidationStepTest).

**Why it matters**: The failure mode of the quality-checker must not be "everything looks fine." Defaulting the missing signal to `low` means a validation bug shows up as over-caution (visible, annoying, fixed quickly) instead of false trust (invisible, harmful).

---

## Architecture Overview

Everything lives inside the existing backend module and its established layout: domain types and the shared context in `analysis/pipeline/`, checks in `analysis/step/`, wire shapes in `dto/`. The design has one direction of data flow — steps write facts (`words`, `partialErrors`, `AI_ENTRY_REJECTED` issues from `ClaudeStep`) onto `AnalysisContext`; `ValidationStep`, running last, reads all of them, appends its own issues, and derives confidence exactly once; `AnalysisService` translates context → DTO. No persistence, no config, no new dependencies.

```text
POST /api/analyze
      │
AnalysisPipeline:  Detection → Translation → Dictionary → Claude → Romanization → Validation
                        (each step try/caught; failures → ctx.partialErrors)      │
                                                                        reads everything,
                                                                        writes issues + confidence
      │
AnalysisService:  AnalysisContext → AnalysisResponse (+ confidence, + issues[])
```

---

## Glossary

| Term | Meaning |
|------|---------|
| Parity study | The side-by-side comparison of the original browser extension vs the backend (`tools/parity-diff/`) that found the silent failures motivating this feature |
| Surface | The exact text of a word as it appears in the input sentence (vs lemma, its dictionary base form) |
| Partial error | A per-step failure recorded on `AnalysisContext` by the pipeline's catch, instead of failing the whole request |
| Transient vs deterministic issue | Whether re-running the same input could plausibly fix it (stage outage = transient) or not (unmappable label = deterministic); drives future cache eligibility |
| Script-scoped | Applying a rule only to characters of the detected language's Unicode script ranges, so a Latin brand name inside a Korean sentence can't trigger a false coverage failure |
| Negative cache | Caching a failure result briefly so repeated identical requests don't hammer a downstream that's already known to be failing |
| POS | Part of speech (noun, verb, particle, …) — the word-class label on each card, normalized to a single canonical vocabulary |
