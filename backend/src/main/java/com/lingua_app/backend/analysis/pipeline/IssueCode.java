package com.lingua_app.backend.analysis.pipeline;

/**
 * Stable validation issue codes — the wire contract documented in
 * specs/002-analysis-validation/contracts/validation-api.md. Enum names are the
 * exact JSON values; never rename or repurpose, only add.
 *
 * The transient/deterministic split drives cache eligibility (FR-014): a response
 * carrying any transient code must not be cached beyond a short negative TTL,
 * because re-running the analysis after recovery would produce a better result.
 */
public enum IssueCode {
    EMPTY_ANALYSIS,
    INPUT_NOT_COVERED,
    CARDS_OUT_OF_ORDER,
    MISSING_FIELD,
    ROMANIZATION_PASSTHROUGH,
    UNKNOWN_POS,
    AI_ENTRY_REJECTED,
    STAGE_FAILED(true);

    private final boolean transientCause;

    IssueCode() {
        this(false);
    }

    IssueCode(boolean transientCause) {
        this.transientCause = transientCause;
    }

    public boolean isTransient() {
        return transientCause;
    }
}
