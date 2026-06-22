# API Contract: Text Analysis Endpoint

**Base path**: `/api`
**Content-Type**: `application/json`
**Authentication**: Required — include `Authorization: Bearer <accessToken>` header on every request.
**Error envelope**: All errors use `{"error":{"code":"...","message":"...","retryable":true|false}}`.

---

## POST /api/analyze

Analyze text: detect language, translate to English, tokenize into WordCards (FR-008–FR-012).

### Request body

```json
{
  "text": "오늘 날씨가 정말 좋네요",
  "language": "kor"
}
```

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `text` | string | Yes | 1–500 chars after trimming (FR-012) |
| `language` | string | No | ISO 639-3 hint from client (`kor`/`jpn`/`cmn`/`lat`). Omit or send `"und"` to let the server detect. |

### Responses

**200 OK**
```json
{
  "language": "kor",
  "translation": "The weather is really nice today.",
  "words": [
    {
      "surface": "오늘",
      "lemma": "오늘",
      "pos": "NOUN",
      "gloss": "today",
      "romanization": "oneul",
      "ipa": null
    },
    {
      "surface": "날씨가",
      "lemma": "날씨",
      "pos": "NOUN",
      "gloss": "weather",
      "romanization": "nalssiga",
      "ipa": null
    }
  ]
}
```

| Field | Type | Notes |
|-------|------|-------|
| `language` | string | ISO 639-3 code resolved by server (`kor`/`jpn`/`cmn`/`lat`) |
| `translation` | string | Full English translation of the input (FR-009) |
| `words` | array | One entry per meaningful token, in input order (FR-010) |
| `words[].surface` | string | Token as it appears in input |
| `words[].lemma` | string | Dictionary base form |
| `words[].pos` | string | Part of speech (NOUN, VERB, ADJ, ADV, PARTICLE, etc.) |
| `words[].gloss` | string | English meaning |
| `words[].romanization` | string \| null | Null for Latin-script tokens (FR-011) |
| `words[].ipa` | string \| null | Always null in Phase 1 |

**Partial result** — when dictionary lookup succeeds but Claude fails, affected words include an error marker rather than returning 500:
```json
{
  "language": "kor",
  "translation": "...",
  "words": [
    { "surface": "정말", "lemma": null, "pos": null, "gloss": null, "error": "ANALYSIS_UNAVAILABLE" }
  ]
}
```

---

### Error responses

**400 Bad Request** — empty, whitespace-only, or oversized input (FR-012)
```json
{
  "error": {
    "code": "INVALID_INPUT",
    "message": "Text must be between 1 and 500 characters.",
    "retryable": false
  }
}
```

**400 Bad Request** — language undetectable (short text, symbols only, mixed unsupported scripts)
```json
{
  "error": {
    "code": "LANGUAGE_UNDETECTABLE",
    "message": "Could not detect a supported language. Supported languages: Korean, Japanese, Chinese, English.",
    "retryable": false
  }
}
```

**401 Unauthorized** — missing or invalid access token (FR-007)
```json
{
  "error": {
    "code": "UNAUTHORIZED",
    "message": "Authentication required.",
    "retryable": false
  }
}
```

**429 Too Many Requests** — rate limit exceeded (FR-014)
```json
{
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "You have exceeded the request limit. Please try again later.",
    "retryable": true
  }
}
```

---

### Pipeline execution order

The server runs steps in this strict order (Constitution Principle I):

1. **Validation** — length check, trim, reject empty/oversized
2. **Rate limit** — Bucket4j per-user token bucket (Redis-backed); rejects before any external call
3. **Language detection** — validates client hint or detects from Unicode script ranges; rejects `und`
4. **Translation** — DeepL → Claude fallback (on quota exhaustion or 5xx)
5. **Dictionary lookup** — Kuromoji (Japanese), CC-CEDICT (Chinese), Krdict (Korean), Free Dictionary API (English)
6. **Claude morphological analysis** — fills gaps not covered by dictionary; receives pre-filled translation and known words to skip
7. **Romanization** — ICU4J `Hangul-Latin/BGN` (Korean), ICU4J katakana→romaji (Japanese), Pinyin4j (Chinese); not applied to English

Romanization is always computed locally — never by Claude (Constitution Principle I).
