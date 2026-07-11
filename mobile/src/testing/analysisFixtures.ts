/**
 * Real POST /api/analyze responses captured from the feature-002 backend
 * (tools/parity-diff/backend-results.json), copied verbatim.
 *
 * The `AnalysisResult` annotations are the contract assertion: if types.ts
 * drifts from what the backend actually sends, these stop compiling.
 * Test-only — never import from app code. Reused by T031's reliability tests.
 */
import type { AnalysisResult } from "../api/types";

/** kor-1 "오늘 날씨가 정말 좋네요" — medium confidence, card-level order warning. */
export const korMediumOrderWarning: AnalysisResult = {
  language: "kor",
  translation: "The weather is really nice today.",
  words: [
    {
      surface: "오늘",
      lemma: "오늘",
      pos: "noun",
      gloss: "today",
      romanization: "oneul",
      ipa: null,
    },
    {
      surface: "정말",
      lemma: "정말",
      pos: "noun",
      gloss: "fact",
      romanization: "jeongmal",
      ipa: null,
    },
    {
      surface: "날씨가",
      lemma: "날씨",
      pos: "noun",
      gloss: "weather",
      romanization: "nalssiga",
      ipa: null,
    },
    {
      surface: "좋네요",
      lemma: "좋다",
      pos: "verb",
      gloss: "to be good/nice",
      romanization: "johneyo",
      ipa: null,
    },
  ],
  confidence: "medium",
  issues: [
    {
      code: "CARDS_OUT_OF_ORDER",
      severity: "warning",
      surface: "날씨가",
      detail: "1 word card(s) appear out of sentence order.",
    },
  ],
};

/** jpn-1 "私は毎朝コーヒーを飲みます" — high confidence, no issues. */
export const jpnHighClean: AnalysisResult = {
  language: "jpn",
  translation: "I drink coffee every morning.",
  words: [
    {
      surface: "私",
      lemma: "私",
      pos: "noun",
      gloss: "I, me (first-person pronoun)",
      romanization: "watashi",
      ipa: null,
    },
    {
      surface: "は",
      lemma: "は",
      pos: "particle",
      gloss: "topic marker particle",
      romanization: "ha",
      ipa: null,
    },
    {
      surface: "毎朝",
      lemma: "毎朝",
      pos: "noun",
      gloss: "every morning",
      romanization: "maiasa",
      ipa: null,
    },
    {
      surface: "コーヒー",
      lemma: "コーヒー",
      pos: "noun",
      gloss: "coffee",
      romanization: "kōhī",
      ipa: null,
    },
    {
      surface: "を",
      lemma: "を",
      pos: "particle",
      gloss: "object marker particle",
      romanization: "wo",
      ipa: null,
    },
    {
      surface: "飲み",
      lemma: "飲む",
      pos: "verb",
      gloss: "to drink",
      romanization: "nomi",
      ipa: null,
    },
    {
      surface: "ます",
      lemma: "ます",
      pos: "particle",
      gloss: "polite non-past auxiliary verb",
      romanization: "masu",
      ipa: null,
    },
  ],
  confidence: "high",
  issues: [],
};

/** cmn-1 "我昨天和朋友一起看了电影" — low confidence, empty words, result-level EMPTY_ANALYSIS. */
export const cmnLowEmptyAnalysis: AnalysisResult = {
  language: "cmn",
  translation: "I went to see a movie with a friend yesterday.",
  words: [],
  confidence: "low",
  issues: [
    {
      code: "EMPTY_ANALYSIS",
      severity: "error",
      surface: null,
      detail: "No word cards were produced for this text.",
    },
  ],
};

/** eng-2 "She couldn't have finished the report yesterday" — low confidence, null romanization, result-level coverage error. */
export const engLowNotCovered: AnalysisResult = {
  language: "lat",
  translation: "She couldn't have finished the report yesterday",
  words: [
    {
      surface: "She",
      lemma: "she",
      pos: "noun",
      gloss: "A female.",
      romanization: null,
      ipa: null,
    },
    {
      surface: "have",
      lemma: "have",
      pos: "noun",
      gloss: "A wealthy or privileged person.",
      romanization: null,
      ipa: null,
    },
    {
      surface: "finished",
      lemma: "finished",
      pos: "verb",
      gloss: "To complete (something).",
      romanization: null,
      ipa: null,
    },
    {
      surface: "the",
      lemma: "the",
      pos: "adv",
      gloss: "With a comparative or with more and a verb phrase, establishes a correlation with one or more other such comparatives.",
      romanization: null,
      ipa: null,
    },
    {
      surface: "report",
      lemma: "report",
      pos: "noun",
      gloss: "A piece of information describing, or an account of certain events given or presented to someone, with the most common adpositions being by (referring to creator of the report) and on (referring to the subject).",
      romanization: null,
      ipa: null,
    },
    {
      surface: "yesterday",
      lemma: "yesterday",
      pos: "noun",
      gloss: "The day immediately before today; one day ago.",
      romanization: null,
      ipa: null,
    },
  ],
  confidence: "low",
  issues: [
    {
      code: "INPUT_NOT_COVERED",
      severity: "error",
      surface: null,
      detail: "Parts of the input are missing from the word breakdown: couldn, t",
    },
  ],
};

export const allFixtures = {
  korMediumOrderWarning,
  jpnHighClean,
  cmnLowEmptyAnalysis,
  engLowNotCovered,
} as const;
