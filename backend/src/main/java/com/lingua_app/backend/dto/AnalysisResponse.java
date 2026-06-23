package com.lingua_app.backend.dto;

import java.util.List;

public record AnalysisResponse(
    String language,
    String translation,
    List<WordCardDto> words
) {}
