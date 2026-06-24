package com.lingua_app.backend.analysis.pipeline;

import com.lingua_app.backend.analysis.step.AnalysisStep;
import com.lingua_app.backend.analysis.step.ClaudeStep;
import com.lingua_app.backend.analysis.step.DetectionStep;
import com.lingua_app.backend.analysis.step.DictionaryStep;
import com.lingua_app.backend.analysis.step.RomanizationStep;
import com.lingua_app.backend.analysis.step.TranslationStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AnalysisPipeline {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPipeline.class);

    private final List<StepDefinition> steps;

    public AnalysisPipeline(DetectionStep detectionStep,
                            TranslationStep translationStep,
                            DictionaryStep dictionaryStep,
                            ClaudeStep claudeStep,
                            RomanizationStep romanizationStep) {
        this.steps = List.of(
            new StepDefinition("detection",    detectionStep),
            new StepDefinition("translation",  translationStep),
            new StepDefinition("dictionary",   dictionaryStep),
            new StepDefinition("claude",       claudeStep),
            new StepDefinition("romanization", romanizationStep)
        );
    }

    public AnalysisContext run(AnalysisContext ctx) {
        for (StepDefinition step : steps) {
            try {
                step.step().run(ctx);
            } catch (Exception e) {
                log.warn("{} failed", step.name(), e);
                ctx.getPartialErrors().put(step.name(), e.getMessage());
            }
        }
        return ctx;
    }

    private record StepDefinition(String name, AnalysisStep step) {}
}
