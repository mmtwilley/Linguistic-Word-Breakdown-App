package com.lingua_app.backend.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;

public record WordCardDto(
    @NotNull
    String surface,
    @Nullable String lemma,
    @Nullable String pos,
    @Nullable String gloss,
    @Nullable String romanization,
    @Nullable String ipa
) {}
