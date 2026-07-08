package com.lingua_app.backend.analysis.pipeline;

/**
 * One rule-based sanity-check failure found during analysis validation.
 * ERROR means the response is structurally wrong (empty analysis, uncovered input);
 * WARN means a card is suspicious but the response is still usable.
 *
 * Wire shape is defined in specs/002-analysis-validation/contracts/validation-api.md;
 * {@code detail} must stay display-safe (no stack traces or internal class names).
 */
public record ValidationIssue(
        IssueCode code,
        Severity severity,
        String surface,
        String detail
) {
    public enum Severity { WARN, ERROR }

    public static ValidationIssue error(IssueCode code, String surface, String detail) {
        return new ValidationIssue(code, Severity.ERROR, surface, detail);
    }

    public static ValidationIssue warn(IssueCode code, String surface, String detail) {
        return new ValidationIssue(code, Severity.WARN, surface, detail);
    }
}
