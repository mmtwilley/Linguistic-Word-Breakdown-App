# Contract: Analysis Response Validation Fields

**Feature**: 002-analysis-validation | **Endpoint**: `POST /api/analyze` (existing, extended)

Request contract is unchanged. Response gains two always-present fields.

## Response schema (extended)

```json
{
  "language": "kor",
  "translation": "The weather is really nice today.",
  "words": [
    {
      "surface": "정말",
      "lemma": "정말",
      "pos": "noun",
      "gloss": "fact",
      "romanization": "jeongmal",
      "ipa": null
    }
  ],
  "confidence": "medium",
  "issues": [
    {
      "code": "CARDS_OUT_OF_ORDER",
      "severity": "warning",
      "surface": "날씨가",
      "detail": "1 word card appears out of sentence order."
    }
  ]
}
```

- `confidence`: string enum `"high" | "medium" | "low"`. Always present on 200 responses.
- `issues`: array, always present, empty when the analysis is clean.
- `issues[].code`: stable — clients and tests may switch on it. New codes may be added;
  existing codes are never renamed or repurposed.
- `issues[].severity`: `"warning" | "error"`.
- `issues[].surface`: the affected word exactly as it appears in a card/input; omitted
  (null) for response-level issues.
- `issues[].detail`: display-safe human-readable text. NOT stable — do not parse.

## Issue-code catalog

| Code | Severity | Cache class (FR-014) | Trigger |
|---|---|---|---|
| `EMPTY_ANALYSIS` | error | deterministic¹ | Non-empty input, zero word cards |
| `INPUT_NOT_COVERED` | error | deterministic¹ | ≥ 1 meaningful input character absent from every card surface; `detail` names the uncovered fragment(s) |
| `CARDS_OUT_OF_ORDER` | warning | deterministic | Card order does not follow input position (duplicate-safe check) |
| `MISSING_FIELD` | warning | deterministic | Card `lemma` or `gloss` missing/blank; one issue per affected card |
| `ROMANIZATION_PASSTHROUGH` | warning | deterministic | `romanization` identical to `surface` for kor/jpn/cmn |
| `UNKNOWN_POS` | warning | deterministic | POS label not mappable to the canonical vocabulary (label passed through unchanged) |
| `AI_ENTRY_REJECTED` | warning | deterministic | AI-generated entry dropped: missing/blank required field, or surface not present in the input |
| `STAGE_FAILED` | error (dictionary, claude, detection) / warning (translation, romanization) | **transient** | A pipeline stage threw; `detail` names the stage |

¹ If `STAGE_FAILED` is present in the same response, treat the response as
transient-degraded for caching regardless of other codes.

## Cache-eligibility rule (binding on the future caching feature, FR-014)

- Response contains `STAGE_FAILED` → do not cache beyond a short negative TTL (≤ 60 s).
- Response contains only deterministic codes (or none) → cacheable at normal TTL,
  with `confidence` and `issues` stored inside the cached payload verbatim.
- Cache keys MUST be versioned such that pre-002 cached payloads (lacking `confidence`)
  are never served.

## Confidence derivation (normative)

```
LOW    if any error-severity issue
       or STAGE_FAILED(dictionary | claude | detection)
       or (# cards with ≥1 warning) / (# cards) > 0.5
MEDIUM else if any warning-severity issue
       or STAGE_FAILED(translation | romanization)
HIGH   otherwise
```

## POS canonical vocabulary (normative, FR-008)

> Terminology: "POS" throughout this contract is the spec's "word-class label".

```
noun, verb, adj, adv, pron, prep, conj, det, num, particle, punct, other
```

`words[].pos` is always one of these when normalization succeeded; any other value will
be accompanied by an `UNKNOWN_POS` issue naming that card.

## Acceptance fixtures (SC-001)

The 10-sentence parity suite (`tools/parity-diff/sentences.json`) run against the
pre-remediation backend MUST produce at least:

| Sentence | Expected codes |
|---|---|
| cmn-1, cmn-2 | `EMPTY_ANALYSIS` (+ `INPUT_NOT_COVERED`), confidence `low` |
| eng-2 | `INPUT_NOT_COVERED` (couldn't), confidence `low` |
| kor-1, kor-2, kor-3 | `CARDS_OUT_OF_ORDER`; no `UNKNOWN_POS` for 명사/부사/동사 (normalized) |
| jpn-1..3 | no false `INPUT_NOT_COVERED` from morpheme splits; no order/coverage false positives on duplicate です |
| eng-1 | clean coverage; POS normalized (adverb→adv etc.) |
