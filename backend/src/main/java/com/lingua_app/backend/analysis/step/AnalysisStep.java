package com.lingua_app.backend.analysis.step;

import com.lingua_app.backend.analysis.pipeline.AnalysisContext;

public interface AnalysisStep {
    void run(AnalysisContext ctx) throws Exception;
}
