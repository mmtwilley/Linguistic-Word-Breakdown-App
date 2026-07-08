package com.lingua_app.backend.analysis.step;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceTool;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingua_app.backend.AppProperties;
import com.lingua_app.backend.analysis.pipeline.AnalysisContext;
import com.lingua_app.backend.analysis.pipeline.WordCard;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ClaudeStep implements AnalysisStep {

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    // Resolved lazily at call time — resolving in the constructor would create a
    // circular dependency, since the bean is still being constructed at that point.
    private final ObjectProvider<ClaudeStep> selfProvider;

    // Spring implicitly auto-wires single constructors
    public ClaudeStep(AppProperties appProperties,
                      ObjectMapper objectMapper,
                      ObjectProvider<ClaudeStep> selfProvider) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.selfProvider = selfProvider;
    }


    @Override
    public void run(AnalysisContext ctx) {
        List<WordCard> knownWords = ctx.getWords().stream()
                .filter(w -> w.getGloss() != null)
                .toList();
        List<String> unresolvedSurfaces = ctx.getWords().stream()
                .filter(w -> w.getGloss() == null)
                .map(WordCard::getSurface)
                .toList();

        if (unresolvedSurfaces.isEmpty()) return;

        // Go through the AOP proxy so @Retry/@CircuitBreaker apply (self-invocation bypasses them)
        selfProvider.getObject().callClaude(ctx, unresolvedSurfaces, knownWords);
    }

    @Retry(name = "claude")
    @CircuitBreaker(name = "claude", fallbackMethod = "fallbackClaude")
    public void callClaude(AnalysisContext ctx,
                           List<String> unresolvedSurfaces,
                           List<WordCard> knownWords) {
        String lang = ctx.getDetectedLanguage();

        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(appProperties.getApi().getClaudeKey())
                .build();

        Message message = client.messages().create(
                MessageCreateParams.builder()
                        .model(Model.of("claude-sonnet-4-6"))
                        .maxTokens(2048L)
                        .addTool(buildTool(lang))
                        .toolChoice(ToolChoice.ofTool(ToolChoiceTool.builder()
                                .name("analyze_words")
                                .build()))
                        .addUserMessage(buildPrompt(lang, ctx.getText(),
                                ctx.getTranslation(), knownWords, unresolvedSurfaces))
                        .build()
        );

        List<WordCard> claudeWords = parseToolResponse(message);

        // Preserve katakana readings placed by DictionaryStep (Japanese Kuromoji output)
        // so RomanizationStep can still convert them after we replace the entry.
        Map<String, String> savedReadings = ctx.getWords().stream()
                .filter(w -> w.getRomanization() != null)
                .collect(Collectors.toMap(WordCard::getSurface, WordCard::getRomanization,
                        (a, b) -> a));

        Set<String> claudeSurfaces = claudeWords.stream()
                .map(WordCard::getSurface)
                .collect(Collectors.toSet());

        ctx.getWords().removeIf(w -> claudeSurfaces.contains(w.getSurface()));

        claudeWords.forEach(w -> {
            String reading = savedReadings.get(w.getSurface());
            if (reading != null) w.setRomanization(reading);
        });
        ctx.getWords().addAll(claudeWords);
    }

    // Resilience4j calls this when the circuit is open or retries are exhausted.
    public void fallbackClaude(AnalysisContext ctx,
                               List<String> unresolvedSurfaces,
                               List<WordCard> knownWords,
                               Exception ex) {
        ctx.getPartialErrors().put("claude", "CLAUDE_UNAVAILABLE");
    }

    // -------------------------------------------------------------------------
    // Tool schema
    // -------------------------------------------------------------------------

    private Tool buildTool(String lang) {
        boolean includeGrammar = "kor".equals(lang) || "jpn".equals(lang);

        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("surface", Map.of("type", "string",
                "description", "Exact surface token as given"));
        itemProps.put("lemma", Map.of("type", "string",
                "description", "Dictionary/base form of the word"));
        itemProps.put("pos", Map.of("type", "string",
                "description", "Part of speech (e.g. noun, verb, particle)"));
        itemProps.put("gloss", Map.of("type", "string",
                "description", "Brief English definition (one phrase)"));
        if (includeGrammar) {
            itemProps.put("particles", Map.of(
                    "type", "array",
                    "items", Map.of("type", "string"),
                    "description", "Grammatical particles attached to the token"));
            itemProps.put("endings", Map.of(
                    "type", "array",
                    "items", Map.of("type", "string"),
                    "description", "Verb or adjective endings"));
        }

        Map<String, Object> itemSchema = new LinkedHashMap<>();
        itemSchema.put("type", "object");
        itemSchema.put("properties", itemProps);
        itemSchema.put("required", List.of("surface", "lemma", "pos", "gloss"));

        Map<String, Object> schemaProps = Map.of(
                "words", Map.of(
                        "type", "array",
                        "items", itemSchema,
                        "description", "One entry per token from the unresolved list")
        );

        return Tool.builder()
                .name("analyze_words")
                .description("Return morphological and semantic analysis for each unresolved "
                        + "surface token. Do not include tokens already listed as resolved.")
                .inputSchema(Tool.InputSchema.builder()
                        .type(JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(schemaProps))
                        .build())
                .build();
    }

    // -------------------------------------------------------------------------
    // Prompt
    // -------------------------------------------------------------------------

    private String buildPrompt(String lang,
                               String sourceText,
                               String translation,
                               List<WordCard> knownWords,
                               List<String> unresolvedSurfaces) {
        String langName = switch (lang) {
            case "kor" -> "Korean";
            case "jpn" -> "Japanese";
            case "cmn" -> "Chinese";
            case "lat" -> "English";
            default    -> lang;
        };

        StringBuilder sb = new StringBuilder();
        sb.append("Language: ").append(langName).append("\n");
        sb.append("Source text: ").append(sourceText).append("\n");
        sb.append("English translation: ").append(
                translation != null ? translation : "NEEDS TRANSLATION").append("\n\n");

        if (!knownWords.isEmpty()) {
            sb.append("Already resolved — do NOT repeat these:\n");
            for (WordCard w : knownWords) {
                sb.append("  ").append(w.getSurface())
                  .append(" (").append(w.getPos() != null ? w.getPos() : "?").append(")")
                  .append(" → ").append(w.getGloss()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("Tokens to analyze:\n");
        for (String s : unresolvedSurfaces) {
            sb.append("  ").append(s).append("\n");
        }

        sb.append("\nCall the analyze_words tool with one entry per token above.");
        if ("kor".equals(lang) || "jpn".equals(lang)) {
            sb.append(" Include particles and endings where applicable.");
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Response parsing
    // -------------------------------------------------------------------------

    private List<WordCard> parseToolResponse(Message message) {
        return message.content().stream()
                .filter(b -> b.isToolUse())
                .map(b -> b.asToolUse())
                .filter(tu -> "analyze_words".equals(tu.name()))
                .findFirst()
                .map(this::extractWordCards)
                .orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    private List<WordCard> extractWordCards(ToolUseBlock tu) {
        String json;
        try {
            json = objectMapper.writeValueAsString(tu._input());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Claude tool input", e);
        }

        Map<String, Object> root;
        try {
            root = objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Claude tool response JSON", e);
        }

        List<Map<String, Object>> wordMaps = (List<Map<String, Object>>) root.get("words");
        if (wordMaps == null) return List.of();

        return wordMaps.stream()
                .map(this::mapToWordCard)
                .toList();
    }

    private WordCard mapToWordCard(Map<String, Object> m) {
        return WordCard.builder()
                .surface((String) m.get("surface"))
                .lemma((String) m.get("lemma"))
                .pos((String) m.get("pos"))
                .gloss((String) m.get("gloss"))
                .build();
    }
}
