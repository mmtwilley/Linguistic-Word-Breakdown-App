/**
 * Canonical wire types shared with the Spring Boot backend.
 *
 * Per the project constitution, the TypeScript types in this module ARE the
 * mobile↔backend contract: they mirror the backend DTOs field-for-field and
 * value-for-value. Changing anything here requires a simultaneous backend
 * change. Source of truth: specs/003-mobile-analyze-screen/contracts/backend-api.md
 */

/** Wire values are lowercase (backend Confidence enum uses @JsonValue). */
export type Confidence = "high" | "medium" | "low";

/**
 * Stable, append-only issue codes (backend IssueCode enum names).
 * The trailing `(string & {})` keeps unknown future codes assignable so the
 * app degrades to a generic message instead of failing to parse.
 */
export type IssueCode =
  | "EMPTY_ANALYSIS"
  | "INPUT_NOT_COVERED"
  | "CARDS_OUT_OF_ORDER"
  | "MISSING_FIELD"
  | "ROMANIZATION_PASSTHROUGH"
  | "UNKNOWN_POS"
  | "AI_ENTRY_REJECTED"
  | "STAGE_FAILED"
  | (string & {});

export interface ValidationIssue {
  code: IssueCode;
  /** Wire values are lowercase (backend ValidationIssueDto.Severity via @JsonProperty). */
  severity: "warning" | "error";
  /** Non-null ⇒ card-level issue for the card(s) with this surface; null ⇒ result-level. */
  surface: string | null;
  /** Diagnostic text — never shown to the user (display strings live in i18n/messages.ts). */
  detail: string | null;
}

export interface WordCard {
  surface: string;
  lemma: string | null;
  pos: string | null;
  gloss: string | null;
  romanization: string | null;
  ipa: string | null;
}

export interface AnalysisResult {
  language: string;
  translation: string | null;
  /** Ordered as the words appear in the input — never re-sort client-side (FR-008). */
  words: WordCard[];
  confidence: Confidence;
  issues: ValidationIssue[];
}

/** Valid source-language hints (backend DetectionStep.VALID_HINTS); omit for auto-detect. */
export type LanguageHint = "kor" | "jpn" | "cmn" | "lat";

export interface AnalysisRequest {
  /** 1–500 characters (backend @NotBlank @Size(max = 500)). */
  text: string;
  language?: LanguageHint;
}

export interface RegisterRequest {
  /** Must be a valid email address. */
  email: string;
  /** 8–128 characters. */
  password: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RefreshRequest {
  refreshToken: string;
}

export interface AuthResponse {
  accessToken: string;
  /** Rotated on every refresh — the previous refresh token becomes invalid. */
  refreshToken: string;
  /** Access-token lifetime in seconds. */
  expiresIn: number;
}

/**
 * Envelope for every non-2xx response. `code` values include the backend's
 * registry (VALIDATION_ERROR, INVALID_INPUT, LANGUAGE_UNDETECTABLE,
 * INVALID_CREDENTIALS, EMAIL_ALREADY_EXISTS, UNAUTHORIZED,
 * INVALID_REFRESH_TOKEN, RATE_LIMIT_EXCEEDED, INTERNAL_ERROR) plus the
 * client-synthesized NETWORK_ERROR for fetch failures/timeouts.
 */
export interface ErrorEnvelope {
  error: {
    code: string;
    /** Backend-guaranteed display-safe; used as fallback display text. */
    message: string;
    /** Whether retrying the same request might succeed. */
    retryable: boolean;
  };
}
