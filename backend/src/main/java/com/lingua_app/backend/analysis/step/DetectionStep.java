package com.lingua_app.backend.analysis.step;

import com.lingua_app.backend.analysis.pipeline.AnalysisContext;
import com.lingua_app.backend.exception.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class DetectionStep implements AnalysisStep {

    private static final Set<String> VALID_HINTS = Set.of("kor", "jpn", "cmn", "lat");

    public void run(AnalysisContext ctx) {
        // AnalysisPipeline copies request.language() onto ctx before calling this step.
        // If the client supplied a valid hint, trust it and skip detection entirely.
        String lang = ctx.getDetectedLanguage();

        if (lang != null && VALID_HINTS.contains(lang)) {
            // Client-supplied hint is valid — trust it, skip detection
            return;
        }

        String detected = detectScript(ctx.getText());
        if ("und".equals(detected)) {
            throw new AppException(
                    HttpStatus.BAD_REQUEST,
                    "LANGUAGE_UNDETECTABLE",
                    "Could not detect a supported language in the submitted text."
            );
        }
        ctx.setDetectedLanguage(detected);
    }

    // Unicode block-counting algorithm — same logic as the mobile client (research.md Decision 3).
    // Counts characters in each script's code-point ranges and picks the dominant script.
    static String detectScript(String text) {
        int kor = 0, kana = 0, cjk = 0, lat = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if ((cp >= 0xAC00 && cp <= 0xD7A3)   // Hangul syllables
             || (cp >= 0x1100 && cp <= 0x11FF)    // Hangul Jamo
             || (cp >= 0x3130 && cp <= 0x318F)) { // Hangul Compatibility Jamo
                kor++;
            } else if ((cp >= 0x3040 && cp <= 0x309F)   // Hiragana
                    || (cp >= 0x30A0 && cp <= 0x30FF)) { // Katakana
                kana++;
            } else if (cp >= 0x4E00 && cp <= 0x9FFF) {  // CJK Unified Ideographs (core block)
                cjk++;
            } else if ((cp >= 0x41 && cp <= 0x5A)        // A-Z
                    || (cp >= 0x61 && cp <= 0x7A)) {      // a-z
                lat++;
            }
            i += Character.charCount(cp);
        }

        if (kor == 0 && kana == 0 && cjk == 0 && lat == 0) return "und";
        if (kor > 0 && kor >= kana && kor >= cjk && kor >= lat) return "kor";
        if (kana > 0) return "jpn";
        if (cjk > 0 && cjk >= lat) return "cmn";
        return "lat";
    }
}
