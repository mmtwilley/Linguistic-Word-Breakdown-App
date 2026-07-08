package com.lingua_app.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lingua_app.backend.analysis.pipeline.IssueCode;
import com.lingua_app.backend.analysis.pipeline.ValidationIssue;

public record ValidationIssueDto(
        IssueCode code,
        Severity severity,
        String surface,
        String detail
) {
    // Wire names per contracts/validation-api.md: "warning" | "error"
    public enum Severity {
        @JsonProperty("warning") WARNING,
        @JsonProperty("error") ERROR
    }

    public static ValidationIssueDto from(ValidationIssue issue) {
        return new ValidationIssueDto(
                issue.code(),
                issue.severity() == ValidationIssue.Severity.ERROR ? Severity.ERROR : Severity.WARNING,
                issue.surface(),
                issue.detail()
        );
    }
}
