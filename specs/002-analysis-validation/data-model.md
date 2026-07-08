# Data Model: Analysis Validation & Confidence Level

**Feature**: 002-analysis-validation | **Date**: 2026-07-06

No persistent storage changes — all entities are per-request, in-memory, and (for DTOs)
serialized on the API response. No database migration.

## Confidence (enum)

| Value | Meaning | Serialized as |
|---|---|---|
| `HIGH` | No issues, no stage failures — result fully trustworthy | `"high"` |
| `MEDIUM` | Warning-level issues (≤ 50% of cards) or auxiliary stage failure | `"medium"` |
| `LOW` | Any error-level issue, word-affecting stage failure, or > 50% of cards warned | `"low"` |

Derivation is a pure function of the issue list + `partialErrors` (FR-010, research
Decision 7). Never null on a successful response (SC-002). Serialization: `@JsonValue`
on the enum (`name().toLowerCase(Locale.ROOT)`); DTOs carry the enum, not a String.

## IssueCode (enum)

The 8 stable codes as enum constants — names are the exact wire format (no annotation
needed). Carries the FR-014 cache classification: `isTransient()` is `true` only for
`STAGE_FAILED`; all other codes are deterministic. The future caching feature calls
`issues.stream().anyMatch(i -> i.code().isTransient())` for eligibility.

## ValidationIssue (in-memory record → ValidationIssueDto)

| Field | Type | Constraints | Notes |
|---|---|---|---|
| `code` | `IssueCode` | One of the 8 enum constants (contracts/validation-api.md) | Stable contract; never renamed, only added |
| `severity` | enum `WARN` \| `ERROR` | Required | Serialized `"warning"` / `"error"` |
| `surface` | String, nullable | Present iff the issue is card-specific | Verbatim user text — DEBUG-only in logs |
| `detail` | String | Required; display-safe (no internals) | Human-readable; not part of the stable contract |

Relationships: belongs to exactly one analysis response; zero or more per response.
Ordering: issues are emitted in check order (coverage → empty → order → per-card checks →
stage failures) so output is deterministic for tests.

## AnalysisContext (existing, modified)

| Field | Type | Change |
|---|---|---|
| `validationIssues` | `List<ValidationIssue>` | NEW — appended by `ClaudeStep` (AI_ENTRY_REJECTED) and `ValidationStep` (all others) |
| `confidence` | `Confidence` | NEW — set once by `ValidationStep`; null only if the validation step itself crashed (pipeline catch), in which case the service maps it to `LOW` defensively |

Existing fields (`text`, `detectedLanguage`, `translation`, `words`, `partialErrors`)
unchanged and are the inputs to validation.

## WordCard (existing, behavioral change only)

No field changes. `pos` is rewritten in place by `ValidationStep` to the canonical
vocabulary when a mapping exists (FR-008 / clarification Q2). Canonical vocabulary:

```
noun, verb, adj, adv, pron, prep, conj, det, num, particle, punct, other
```

## AnalysisResponse (existing DTO, extended additively)

| Field | Type | Change |
|---|---|---|
| `language` | String | unchanged |
| `translation` | String | unchanged |
| `words` | `List<WordCardDto>` | unchanged |
| `confidence` | `Confidence` (enum) | NEW — serializes `"high"` \| `"medium"` \| `"low"` via `@JsonValue`, always present |
| `issues` | `List<ValidationIssueDto>` | NEW — always present, `[]` when clean |

Backward compatibility: additive fields only; existing clients (none in production yet)
ignore unknown fields. The shared TypeScript `AnalysisResult` type gains:

```typescript
type ValidationIssue = {
  code: string;
  severity: 'warning' | 'error';
  surface?: string;
  detail: string;
};

type AnalysisResult = {
  translation: string;
  words: WordCard[];
  language: string;
  confidence: 'high' | 'medium' | 'low';   // NEW
  issues: ValidationIssue[];               // NEW
};
```

## State transitions

None — validation is stateless per request. The only ordered behavior is within one
pipeline run: `ClaudeStep` may append `AI_ENTRY_REJECTED` issues before `ValidationStep`
runs its checks and derives confidence exactly once.
