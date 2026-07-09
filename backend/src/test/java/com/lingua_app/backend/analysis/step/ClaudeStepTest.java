package com.lingua_app.backend.analysis.step;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingua_app.backend.analysis.pipeline.AnalysisContext;
import com.lingua_app.backend.analysis.pipeline.IssueCode;
import com.lingua_app.backend.analysis.pipeline.ValidationIssue;
import com.lingua_app.backend.analysis.pipeline.WordCard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeStepTest {

    private final ClaudeStep step = new ClaudeStep(null, new ObjectMapper(), null);

    private static AnalysisContext ctx(String text) {
        AnalysisContext ctx = new AnalysisContext();
        ctx.setText(text);
        ctx.setDetectedLanguage("kor");
        ctx.setWords(new ArrayList<>());
        return ctx;
    }

    private static ToolUseBlock toolUse(Map<String, Object> input) {
        return ToolUseBlock.builder()
                .id("tool_1")
                .name("analyze_words")
                .caller(DirectCaller.builder().build())
                .input(JsonValue.from(input))
                .build();
    }

    /** Entry map builder tolerating absent fields (Map.of rejects nulls). */
    private static Map<String, Object> entry(String surface, String lemma,
                                             String pos, String gloss) {
        Map<String, Object> m = new HashMap<>();
        if (surface != null) m.put("surface", surface);
        if (lemma != null) m.put("lemma", lemma);
        if (pos != null) m.put("pos", pos);
        if (gloss != null) m.put("gloss", gloss);
        return m;
    }

    private static List<IssueCode> codes(AnalysisContext ctx) {
        return ctx.getValidationIssues().stream().map(ValidationIssue::code).toList();
    }

    // --- valid entries pass through ------------------------------------------

    @Test
    void validEntries_areAllPreserved_withNoIssues() {
        AnalysisContext c = ctx("오늘 날씨가 좋네요");
        List<WordCard> cards = step.extractWordCards(toolUse(Map.of("words", List.of(
                entry("오늘", "오늘", "noun", "today"),
                entry("날씨가", "날씨", "noun", "weather")))), c);

        assertThat(cards).hasSize(2);
        assertThat(cards.get(0).getSurface()).isEqualTo("오늘");
        assertThat(c.getValidationIssues()).isEmpty();
        assertThat(c.getPartialErrors()).isEmpty();
    }

    // --- FR-009 rejection: blank / missing fields ------------------------------

    @Test
    void blankGloss_rejectsEntry_withWarnIssueNamingSurface() {
        AnalysisContext c = ctx("오늘 날씨가 좋네요");
        List<WordCard> cards = step.extractWordCards(toolUse(Map.of("words", List.of(
                entry("오늘", "오늘", "noun", "today"),
                entry("날씨가", "날씨", "noun", "  ")))), c);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getSurface()).isEqualTo("오늘");
        ValidationIssue issue = c.getValidationIssues().stream()
                .filter(i -> i.code() == IssueCode.AI_ENTRY_REJECTED).findFirst().orElseThrow();
        assertThat(issue.severity()).isEqualTo(ValidationIssue.Severity.WARN);
        assertThat(issue.surface()).isEqualTo("날씨가");
    }

    @Test
    void missingLemma_rejectsEntry() {
        AnalysisContext c = ctx("오늘 날씨가 좋네요");
        List<WordCard> cards = step.extractWordCards(toolUse(Map.of("words", List.of(
                entry("오늘", null, "noun", "today")))), c);

        assertThat(cards).isEmpty();
        assertThat(codes(c)).containsExactly(IssueCode.AI_ENTRY_REJECTED);
    }

    // --- FR-009 rejection: fabricated surface -----------------------------------

    @Test
    void surfaceNotInInput_rejectsEntry() {
        AnalysisContext c = ctx("오늘 날씨가 좋네요");
        List<WordCard> cards = step.extractWordCards(toolUse(Map.of("words", List.of(
                entry("바나나", "바나나", "noun", "banana")))), c);

        assertThat(cards).isEmpty();
        ValidationIssue issue = c.getValidationIssues().get(0);
        assertThat(issue.code()).isEqualTo(IssueCode.AI_ENTRY_REJECTED);
        assertThat(issue.surface()).isEqualTo("바나나");
    }

    // --- missing words array → claude partial error ------------------------------

    @Test
    void missingWordsArray_recordsClaudePartialError_notSilentEmpty() {
        AnalysisContext c = ctx("오늘 날씨가 좋네요");
        List<WordCard> cards = step.extractWordCards(toolUse(Map.of()), c);

        assertThat(cards).isEmpty();
        assertThat(c.getPartialErrors()).containsKey("claude");
        assertThat(codes(c)).doesNotContain(IssueCode.AI_ENTRY_REJECTED);
    }

    // --- merge preserves DictionaryStep's katakana readings ----------------------

    @Test
    void mergeWords_replacesUnresolvedCard_andRestoresKatakanaReading() {
        AnalysisContext c = ctx("映画を見た");
        WordCard seeded = WordCard.builder()
                .surface("映画").romanization("エイガ").build(); // Kuromoji reading, no gloss
        c.getWords().add(seeded);

        WordCard fromClaude = WordCard.builder()
                .surface("映画").lemma("映画").pos("noun").gloss("movie").build();
        step.mergeWords(c, List.of(fromClaude));

        assertThat(c.getWords()).hasSize(1);
        WordCard merged = c.getWords().get(0);
        assertThat(merged.getGloss()).isEqualTo("movie");
        assertThat(merged.getRomanization()).isEqualTo("エイガ");
    }
}
