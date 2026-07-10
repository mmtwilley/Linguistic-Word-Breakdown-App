/**
 * Every user-facing string for backend-reported conditions lives here.
 *
 * Raw wire codes (IssueCode, error envelope codes) must never reach the
 * screen (FR-010, SC-004): screens and components render these strings only.
 * Unknown codes — possible because both registries are append-only on the
 * backend — degrade to generic fallbacks instead of failing.
 */
import type { IssueCode } from "../api/types";

/**
 * Plain-language explanations for validation issues (contract §3).
 * Result-level codes describe the whole analysis; card-level codes are shown
 * on the flagged word card.
 */
const ISSUE_MESSAGES: Record<string, string> = {
  // Result-level
  EMPTY_ANALYSIS: "A word-by-word breakdown wasn't available for this text.",
  INPUT_NOT_COVERED: "Some words in your text don't have a card below.",
  CARDS_OUT_OF_ORDER: "The word cards may not match the order of your sentence.",
  STAGE_FAILED: "Part of the analysis didn't finish — trying again may give a better result.",
  // Card-level
  MISSING_FIELD: "Some details for this word couldn't be found.",
  ROMANIZATION_PASSTHROUGH: "The pronunciation shown for this word may not be accurate.",
  UNKNOWN_POS: "The part of speech for this word couldn't be verified.",
  AI_ENTRY_REJECTED: "This word's details couldn't be verified and may be incomplete.",
};

const ISSUE_FALLBACK = "Something about this result may be unreliable.";

export function issueMessage(code: IssueCode): string {
  return ISSUE_MESSAGES[code] ?? ISSUE_FALLBACK;
}

/** Result-level codes render as notices on the result header; all others attach to cards. */
const RESULT_LEVEL_CODES = new Set<string>([
  "EMPTY_ANALYSIS",
  "INPUT_NOT_COVERED",
  "CARDS_OUT_OF_ORDER",
  "STAGE_FAILED",
]);

/**
 * Unknown codes are treated as result-level so they surface somewhere visible
 * even when we can't match them to a card.
 */
export function isResultLevel(code: IssueCode, surface: string | null): boolean {
  return surface === null || RESULT_LEVEL_CODES.has(code);
}

/**
 * User-facing messages per error-envelope code (contract §4), keyed by the
 * distinct handling FR-013 requires. The envelope's own `message` field is
 * backend-guaranteed display-safe and is used when the code is unknown.
 */
const ERROR_MESSAGES: Record<string, string> = {
  VALIDATION_ERROR: "Please check your input and try again.",
  INVALID_INPUT: "Text must be between 1 and 500 characters.",
  LANGUAGE_UNDETECTABLE:
    "We couldn't tell what language this is. Please try picking the language yourself above.",
  INVALID_CREDENTIALS: "That email or password doesn't match. Please try again.",
  EMAIL_ALREADY_EXISTS: "An account with that email already exists. Try signing in instead.",
  UNAUTHORIZED: "Your session has expired. Please sign in again.",
  INVALID_REFRESH_TOKEN: "Your session has expired. Please sign in again.",
  RATE_LIMIT_EXCEEDED: "You've made a lot of requests! Give it a moment, then try again.",
  INTERNAL_ERROR: "Something went wrong on our end. Please try again.",
  NETWORK_ERROR: "Couldn't reach the server. Check your connection and try again.",
};

const ERROR_FALLBACK = "Something went wrong. Please try again.";

/**
 * @param code Envelope error code.
 * @param envelopeMessage The envelope's display-safe `message`, preferred over
 *   the generic fallback when the code is unknown to this app version.
 */
export function errorMessage(code: string, envelopeMessage?: string): string {
  return ERROR_MESSAGES[code] ?? envelopeMessage ?? ERROR_FALLBACK;
}
