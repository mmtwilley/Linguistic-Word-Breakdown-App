package com.lingua_app.backend.analysis.pipeline;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
public class AnalysisContext {
    private String text;
    private String detectedLanguage;
    private String translation;
    private List<WordCard> words = new ArrayList<>();
    private Map<String, String> partialErrors = new HashMap<>();
    private List<ValidationIssue> validationIssues = new ArrayList<>();
    // Set exactly once by ValidationStep; null means validation itself crashed,
    // which AnalysisService defensively maps to LOW.
    private Confidence confidence;
}
