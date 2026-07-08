package com.lingua_app.backend.analysis.step;

import com.lingua_app.backend.analysis.pipeline.AnalysisContext;
import com.lingua_app.backend.analysis.pipeline.Confidence;
import com.lingua_app.backend.analysis.pipeline.IssueCode;
import com.lingua_app.backend.analysis.pipeline.ValidationIssue;
import com.lingua_app.backend.analysis.pipeline.WordCard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationStepTest {

    private final ValidationStep step = new ValidationStep();

    private static AnalysisContext ctx(String text, String lang, WordCard... cards) {
        AnalysisContext ctx = new AnalysisContext();
        ctx.setText(text);
        ctx.setDetectedLanguage(lang);
        ctx.setWords(new ArrayList<>(List.of(cards)));
        return ctx;
    }

    private static WordCard card(String surface) {
        return WordCard.builder().surface(surface).lemma(surface).gloss("gloss").build();
    }

    private static List<IssueCode> codes(AnalysisContext ctx) {
        return ctx.getValidationIssues().stream().map(ValidationIssue::code).toList();
    }

    // --- clean path -------------------------------------------------------

    @Test
    void cleanAnalysis_isHighWithNoIssues() {
        AnalysisContext c = ctx("오늘 날씨가 정말 좋네요", "kor",
                card("오늘"), card("날씨가"), card("정말"), card("좋네요"));
        step.run(c);
        assertThat(c.getValidationIssues()).isEmpty();
        assertThat(c.getConfidence()).isEqualTo(Confidence.HIGH);
    }

    // --- FR-004 empty analysis -------------------------------------------

    @Test
    void emptyWords_flagsEmptyAnalysis_andLow() {
        AnalysisContext c = ctx("学习中文很有意思", "cmn");
        step.run(c);
        assertThat(codes(c)).contains(IssueCode.EMPTY_ANALYSIS);
        assertThat(c.getConfidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    void punctuationAndDigitsOnlyInput_producesNoIssues() {
        AnalysisContext c = ctx("123 !? 456", "lat");
        step.run(c);
        assertThat(c.getValidationIssues()).isEmpty();
        assertThat(c.getConfidence()).isEqualTo(Confidence.HIGH);
    }

    // --- FR-003 coverage --------------------------------------------------

    @Test
    void droppedToken_flagsInputNotCovered_namingFragment_andLow() {
        AnalysisContext c = ctx("She couldn't have finished the report yesterday", "lat",
                card("She"), card("have"), card("finished"),
                card("the"), card("report"), card("yesterday"));
        step.run(c);
        assertThat(codes(c)).contains(IssueCode.INPUT_NOT_COVERED);
        ValidationIssue coverage = c.getValidationIssues().stream()
                .filter(i -> i.code() == IssueCode.INPUT_NOT_COVERED).findFirst().orElseThrow();
        assertThat(coverage.severity()).isEqualTo(ValidationIssue.Severity.ERROR);
        assertThat(coverage.detail()).contains("couldn");
        assertThat(c.getConfidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    void droppedDuplicate_isDetected_via1to1Claiming() {
        // Two "the" in input, only one "the" card — the second occurrence stays unclaimed.
        AnalysisContext c = ctx("the cat and the dog", "lat",
                card("the"), card("cat"), card("and"), card("dog"));
        step.run(c);
        assertThat(codes(c)).contains(IssueCode.INPUT_NOT_COVERED);
    }

    @Test
    void morphemeSplitCards_fullyCoverInput_noFalsePositive() {
        AnalysisContext c = ctx("昨日友達と映画を見に行きました", "jpn",
                card("昨日"), card("友達"), card("と"), card("映画"), card("を"),
                card("見"), card("に"), card("行き"), card("まし"), card("た"));
        step.run(c);
        assertThat(codes(c)).doesNotContain(IssueCode.INPUT_NOT_COVERED);
        assertThat(c.getConfidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void foreignScriptFragment_doesNotCountAsUncovered() {
        AnalysisContext c = ctx("아이폰 iPhone 좋아요", "kor",
                card("아이폰"), card("좋아요"));
        step.run(c);
        assertThat(codes(c)).doesNotContain(IssueCode.INPUT_NOT_COVERED);
    }

    // --- FR-005 ordering --------------------------------------------------

    @Test
    void duplicateSurfacesInOrder_noOrderViolation() {
        AnalysisContext c = ctx("日本語の勉強は難しいですが、楽しいです", "jpn",
                card("日本語"), card("の"), card("勉強"), card("は"), card("難しい"),
                card("です"), card("が"), card("楽しい"), card("です"));
        step.run(c);
        assertThat(codes(c)).doesNotContain(IssueCode.CARDS_OUT_OF_ORDER);
    }

    @Test
    void outOfOrderCards_flagWarning_andMedium() {
        // Backend's real kor-1 ordering bug: Krdict hits first, Claude fills appended.
        AnalysisContext c = ctx("오늘 날씨가 정말 좋네요", "kor",
                card("오늘"), card("정말"), card("날씨가"), card("좋네요"));
        step.run(c);
        assertThat(codes(c)).contains(IssueCode.CARDS_OUT_OF_ORDER);
        ValidationIssue order = c.getValidationIssues().stream()
                .filter(i -> i.code() == IssueCode.CARDS_OUT_OF_ORDER).findFirst().orElseThrow();
        assertThat(order.severity()).isEqualTo(ValidationIssue.Severity.WARN);
        assertThat(c.getConfidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void majorityOfCardsOutOfOrder_escalatesToLow() {
        // 3 of 4 cards displaced (> 50% warned) — clarification Q3.
        AnalysisContext c = ctx("a b c d", "lat",
                card("d"), card("c"), card("b"), card("a"));
        step.run(c);
        assertThat(codes(c)).contains(IssueCode.CARDS_OUT_OF_ORDER);
        assertThat(c.getConfidence()).isEqualTo(Confidence.LOW);
    }

    // --- STAGE_FAILED + derivation (clarification Q1) ----------------------

    @Test
    void auxiliaryStageFailure_isWarning_andMedium() {
        AnalysisContext c = ctx("오늘 날씨", "kor", card("오늘"), card("날씨"));
        c.getPartialErrors().put("translation", "DEEPL_UNAVAILABLE");
        step.run(c);
        ValidationIssue stage = c.getValidationIssues().stream()
                .filter(i -> i.code() == IssueCode.STAGE_FAILED).findFirst().orElseThrow();
        assertThat(stage.severity()).isEqualTo(ValidationIssue.Severity.WARN);
        assertThat(stage.detail()).contains("translation");
        assertThat(c.getConfidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void wordAffectingStageFailure_isError_andLow() {
        AnalysisContext c = ctx("오늘 날씨", "kor", card("오늘"), card("날씨"));
        c.getPartialErrors().put("claude", "CLAUDE_UNAVAILABLE");
        step.run(c);
        ValidationIssue stage = c.getValidationIssues().stream()
                .filter(i -> i.code() == IssueCode.STAGE_FAILED).findFirst().orElseThrow();
        assertThat(stage.severity()).isEqualTo(ValidationIssue.Severity.ERROR);
        assertThat(c.getConfidence()).isEqualTo(Confidence.LOW);
    }

    // --- FR-012 robustness --------------------------------------------------

    @Test
    void run_neverMutatesWordsOrTranslation_andToleratesNullFields() {
        WordCard broken = WordCard.builder().build(); // null surface
        AnalysisContext c = ctx("오늘 날씨", "kor", card("오늘"), broken);
        c.setTranslation("today weather");
        List<WordCard> wordsBefore = List.copyOf(c.getWords());

        step.run(c); // must not throw

        assertThat(c.getWords()).containsExactlyElementsOf(wordsBefore);
        assertThat(c.getTranslation()).isEqualTo("today weather");
        assertThat(c.getConfidence()).isNotNull();
    }
}
