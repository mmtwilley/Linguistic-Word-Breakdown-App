package com.lingua_app.backend.analysis.step;

import com.lingua_app.backend.analysis.pipeline.AnalysisContext;
import com.lingua_app.backend.analysis.pipeline.Confidence;
import com.lingua_app.backend.analysis.pipeline.IssueCode;
import com.lingua_app.backend.analysis.pipeline.ValidationIssue;
import com.lingua_app.backend.analysis.pipeline.WordCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rule-based sanity checks over the finished analysis (feature 002). Runs last in the
 * pipeline so it sees the final card list. Read-only over words/translation except for
 * one deliberate write: mappable POS labels are rewritten to the canonical vocabulary
 * (FR-008, clarification Q2). Other writes are the issue list and the confidence level.
 * All checks are local string scans — no external calls (FR-011).
 */
@Component
public class ValidationStep implements AnalysisStep {

    private static final Logger log = LoggerFactory.getLogger(ValidationStep.class);

    // Stages whose failure invalidates the word cards themselves (clarification Q1);
    // anything else (translation, romanization) degrades the response, not the cards.
    private static final Set<String> WORD_AFFECTING_STAGES =
            Set.of("dictionary", "claude", "detection");

    // Languages whose romanization must differ from the surface script (FR-007);
    // for Latin-script languages an identical "romanization" is simply correct.
    private static final Set<String> TRANSLITERATED_LANGS = Set.of("kor", "jpn", "cmn");

    public void run(AnalysisContext ctx) {
        String text = ctx.getText();
        if (text == null || text.isBlank()) {
            ctx.setConfidence(Confidence.HIGH);
            return;
        }

        List<ValidationIssue> issues = new ArrayList<>();
        // Cards carrying at least one warning — drives the >50% escalation (Q3).
        Set<WordCard> warnedCards = new HashSet<>();
        List<WordCard> words = ctx.getWords();
        String lang = ctx.getDetectedLanguage();

        boolean inputHasMeaningfulChars = hasMeaningfulChars(text, lang);

        if (words.isEmpty() && inputHasMeaningfulChars) {
            issues.add(ValidationIssue.error(IssueCode.EMPTY_ANALYSIS, null,
                    "No word cards were produced for this text."));
        }

        if (!words.isEmpty() && inputHasMeaningfulChars) {
            checkCoverage(text, lang, words, issues);
            checkOrder(text, words, issues, warnedCards);
        }

        checkCards(lang, words, issues, warnedCards);
        checkStageFailures(ctx, issues);

        ctx.getValidationIssues().addAll(issues);
        ctx.setConfidence(derive(ctx.getValidationIssues(), warnedCards, words));
        logSummary(ctx);
    }

    // --- FR-003: character coverage with 1:1 occurrence claiming (research D3) -----

    private void checkCoverage(String text, String lang,
                               List<WordCard> words, List<ValidationIssue> issues) {
        boolean[] claimed = new boolean[text.length()];
        int scanPos = 0;
        for (WordCard card : words) {
            String surface = card.getSurface();
            if (surface == null || surface.isBlank()) continue;
            int idx = indexOfUnclaimed(text, surface, scanPos, claimed);
            if (idx < 0) idx = indexOfUnclaimed(text, surface, 0, claimed);
            if (idx >= 0) {
                for (int k = idx; k < idx + surface.length(); k++) claimed[k] = true;
                scanPos = idx + surface.length();
            }
        }

        List<String> uncovered = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (!claimed[i] && isMeaningful(text.charAt(i), lang)) {
                run.append(text.charAt(i));
            } else if (run.length() > 0) {
                uncovered.add(run.toString());
                run.setLength(0);
            }
        }
        if (run.length() > 0) uncovered.add(run.toString());

        if (!uncovered.isEmpty()) {
            String fragments = String.join(", ", uncovered.subList(0, Math.min(5, uncovered.size())));
            issues.add(ValidationIssue.error(IssueCode.INPUT_NOT_COVERED, null,
                    "Parts of the input are missing from the word breakdown: " + fragments));
        }
    }

    private static int indexOfUnclaimed(String text, String surface, int from, boolean[] claimed) {
        int idx = text.indexOf(surface, from);
        outer:
        while (idx >= 0) {
            for (int k = idx; k < idx + surface.length(); k++) {
                if (claimed[k]) {
                    idx = text.indexOf(surface, idx + 1);
                    continue outer;
                }
            }
            return idx;
        }
        return -1;
    }

    // --- FR-005: input-order check, duplicate-safe via cursor (research D4) --------

    private void checkOrder(String text, List<WordCard> words,
                            List<ValidationIssue> issues, Set<WordCard> warnedCards) {
        int cursor = 0;
        int violations = 0;
        String firstOffender = null;
        for (WordCard card : words) {
            String surface = card.getSurface();
            if (surface == null || surface.isBlank()) continue;
            int idx = text.indexOf(surface, cursor);
            if (idx >= 0) {
                cursor = idx + surface.length();
            } else if (text.indexOf(surface) >= 0) {
                // Occurs only before the cursor: this card is out of input order.
                violations++;
                warnedCards.add(card);
                if (firstOffender == null) firstOffender = surface;
            }
            // Surface absent from text entirely → a coverage problem, not an order one.
        }
        if (violations > 0) {
            issues.add(ValidationIssue.warn(IssueCode.CARDS_OUT_OF_ORDER, firstOffender,
                    violations + " word card(s) appear out of sentence order."));
        }
    }

    // --- FR-006/007/008: per-card checks --------------------------------------------

    private void checkCards(String lang, List<WordCard> words,
                            List<ValidationIssue> issues, Set<WordCard> warnedCards) {
        for (WordCard card : words) {
            String surface = card.getSurface();

            if (isBlank(card.getLemma()) || isBlank(card.getGloss())) {
                issues.add(ValidationIssue.warn(IssueCode.MISSING_FIELD, surface,
                        "This word card is missing its base form or meaning."));
                warnedCards.add(card);
            }

            if (TRANSLITERATED_LANGS.contains(lang) && surface != null
                    && surface.equals(card.getRomanization())) {
                issues.add(ValidationIssue.warn(IssueCode.ROMANIZATION_PASSTHROUGH, surface,
                        "Pronunciation guidance repeats the original script."));
                warnedCards.add(card);
            }

            // FR-008 normalize-then-flag: rewrite when mappable, warn and leave
            // unchanged otherwise. Absent labels are FR-006's concern, not FR-008's.
            String pos = card.getPos();
            if (!isBlank(pos)) {
                PosNormalizer.normalize(pos).ifPresentOrElse(card::setPos, () -> {
                    issues.add(ValidationIssue.warn(IssueCode.UNKNOWN_POS, surface,
                            "Word-class label is outside the documented vocabulary."));
                    warnedCards.add(card);
                });
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // --- STAGE_FAILED from partialErrors (clarification Q1) ------------------------

    private void checkStageFailures(AnalysisContext ctx, List<ValidationIssue> issues) {
        ctx.getPartialErrors().forEach((stage, error) -> {
            String detail = "The " + stage + " step failed; results may be incomplete.";
            if (WORD_AFFECTING_STAGES.contains(stage)) {
                issues.add(ValidationIssue.error(IssueCode.STAGE_FAILED, null, detail));
            } else {
                issues.add(ValidationIssue.warn(IssueCode.STAGE_FAILED, null, detail));
            }
        });
    }

    // --- FR-010: confidence derivation (clarifications Q1 + Q3) --------------------

    private Confidence derive(List<ValidationIssue> issues,
                              Set<WordCard> warnedCards, List<WordCard> words) {
        boolean anyError = issues.stream()
                .anyMatch(i -> i.severity() == ValidationIssue.Severity.ERROR);
        boolean majorityWarned = !words.isEmpty()
                && warnedCards.size() * 2 > words.size();
        if (anyError || majorityWarned) return Confidence.LOW;
        if (!issues.isEmpty()) return Confidence.MEDIUM;
        return Confidence.HIGH;
    }

    // --- Script-scoped "meaningful character" rules (research D3) ------------------

    private static boolean hasMeaningfulChars(String text, String lang) {
        for (int i = 0; i < text.length(); i++) {
            if (isMeaningful(text.charAt(i), lang)) return true;
        }
        return false;
    }

    private static boolean isMeaningful(char ch, String lang) {
        if (lang == null) return Character.isLetter(ch);
        return switch (lang) {
            case "kor" -> (ch >= 0xAC00 && ch <= 0xD7A3)
                    || (ch >= 0x1100 && ch <= 0x11FF)
                    || (ch >= 0x3130 && ch <= 0x318F);
            case "jpn" -> (ch >= 0x3040 && ch <= 0x309F)
                    || (ch >= 0x30A0 && ch <= 0x30FF)
                    || (ch >= 0x4E00 && ch <= 0x9FFF);
            case "cmn" -> ch >= 0x4E00 && ch <= 0x9FFF;
            case "lat" -> (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
            default -> Character.isLetter(ch);
        };
    }

    // --- FR-013 logging (research D8): no user text above DEBUG --------------------

    private void logSummary(AnalysisContext ctx) {
        List<IssueCode> codes = ctx.getValidationIssues().stream()
                .map(ValidationIssue::code).toList();
        if (ctx.getConfidence() == Confidence.HIGH) {
            log.debug("validation_summary language={} confidence={} issueCodes={} cardCount={}",
                    ctx.getDetectedLanguage(), ctx.getConfidence(), codes, ctx.getWords().size());
            return;
        }
        log.warn("validation_summary language={} confidence={} issueCodes={} cardCount={}",
                ctx.getDetectedLanguage(), ctx.getConfidence(), codes, ctx.getWords().size());
        if (log.isDebugEnabled()) {
            for (ValidationIssue issue : ctx.getValidationIssues()) {
                log.debug("validation_issue code={} severity={} surface={} detail={}",
                        issue.code(), issue.severity(), issue.surface(), issue.detail());
            }
        }
    }
}
