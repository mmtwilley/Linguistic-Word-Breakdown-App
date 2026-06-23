package com.lingua_app.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AnalysisRequest(
    @NotBlank
    @Size(max = 500)
    String text,
    String language
) {}

