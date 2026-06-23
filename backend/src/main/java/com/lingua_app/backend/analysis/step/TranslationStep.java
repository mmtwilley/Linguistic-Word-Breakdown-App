package com.lingua_app.backend.analysis.step;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.deepl.api.QuotaExceededException;
import com.deepl.api.TextResult;
import com.deepl.api.Translator;
import com.lingua_app.backend.AppProperties;
import com.lingua_app.backend.analysis.pipeline.AnalysisContext;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class TranslationStep {

    private final AppProperties appProperties;

    // Self-injection allows Resilience4j AOP proxies to intercept callDeepL()
    // when invoked from run() — direct same-bean calls bypass Spring AOP.
    @Autowired @Lazy
    private TranslationStep self;

    public TranslationStep(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public void run(AnalysisContext ctx) {
        // English text does not need translation; pass through to avoid consuming DeepL quota.
        if ("lat".equals(ctx.getDetectedLanguage())) {
            ctx.setTranslation(ctx.getText());
            return;
        }
        ctx.setTranslation(self.callDeepL(ctx.getText(), ctx.getDetectedLanguage()));
    }

    @Retry(name = "deepl")
    @CircuitBreaker(name = "deepl", fallbackMethod = "fallbackToClaude")
    public String callDeepL(String text, String detectedLanguage) throws Exception {
        Translator translator = new Translator(appProperties.getApi().getDeeplKey());
        TextResult result = translator.translateText(text, toDeepLCode(detectedLanguage), "EN-US");
        return result.getText();
    }

    // Invoked by Resilience4j when callDeepL() throws QuotaExceededException, a 5xx, or a timeout.
    // Signature must mirror callDeepL() parameters with Exception appended.
    public String fallbackToClaude(String text, String detectedLanguage, Exception ex) {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(appProperties.getApi().getClaudeKey())
                .build();

        var message = client.messages().create(
                MessageCreateParams.builder()
                        .model(Model.CLAUDE_HAIKU_4_5_20251001)
                        .maxTokens(512L)
                        .addUserMessage(
                                "Translate the following text to English. " +
                                "Respond with only the translation, no explanation.\n\n" + text)
                        .build()
        );

        return message.content().stream()
                .filter(b -> b.isText())
                .map(b -> b.asText().text())
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Claude translation returned no text content"));
    }

    private String toDeepLCode(String lang) {
        return switch (lang) {
            case "kor" -> "KO";
            case "jpn" -> "JA";
            case "cmn" -> "ZH";
            default -> null;
        };
    }
}
