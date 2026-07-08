package com.lingua_app.backend.analysis.pipeline;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum Confidence {
    HIGH,
    MEDIUM,
    LOW;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
