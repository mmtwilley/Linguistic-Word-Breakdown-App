package com.lingua_app.backend.dto;

import com.lingua_app.backend.analysis.pipeline.Confidence;

import java.util.List;

public record AnalysisResponse(
    String language,
    String translation,
    List<WordCardDto> words,
    Confidence confidence,
    List<ValidationIssueDto> issues
) {}
