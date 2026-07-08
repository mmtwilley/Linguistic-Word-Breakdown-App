package com.lingua_app.backend.analysis.step;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.deepl.api.DeepLClient;
import com.deepl.api.TextResult;
import com.lingua_app.backend.AppProperties;
import com.lingua_app.backend.analysis.pipeline.AnalysisContext;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

@Component
public class TranslationStep implements AnalysisStep {

    private static final Logger log = LoggerFactory.getLogger(TranslationStep.class);

    private static final long DEEPL_MONTHLY_CAP = 500_000;
    private static final long QUOTA_THRESHOLD = (long) (DEEPL_MONTHLY_CAP * 0.9); // 450 000

    private final AppProperties appProperties;
    private final StringRedisTemplate redisTemplate;
    private final TranslationStep self;

    // Self-injection allows Resilience4j AOP proxies to intercept callDeepL()
    // when invoked from run() — direct same-bean calls bypass Spring AOP.
    public TranslationStep(AppProperties appProperties,
                           StringRedisTemplate redisTemplate,
                           @Lazy TranslationStep self) {
        this.appProperties = appProperties;
        this.redisTemplate = redisTemplate;
        this.self = self;
    }

    @Override
    public void run(AnalysisContext ctx) throws Exception {
        // English text does not need translation; pass through to avoid consuming DeepL quota.
        if ("lat".equals(ctx.getDetectedLanguage())) {
            ctx.setTranslation(ctx.getText());
            return;
        }
        // Skip DeepL when the monthly character quota is ≥ 90% consumed.
        if (isQuotaNearlyExhausted()) {
            log.debug("DeepL monthly quota >= 90% — skipping DeepL, translating via Claude");
            ctx.setTranslation(callClaude(ctx.getText()));
            return;
        }
        ctx.setTranslation(self.callDeepL(ctx.getText(), ctx.getDetectedLanguage()));
    }

    @Retry(name = "deepl")
    @CircuitBreaker(name = "deepl", fallbackMethod = "fallbackToClaude")
    public String callDeepL(String text, String detectedLanguage) throws Exception {
        DeepLClient client = new DeepLClient(appProperties.getApi().getDeeplKey());
        TextResult result = client.translateText(text, toDeepLCode(detectedLanguage), "EN-US");
        // Track character usage in Redis after a successful DeepL response.
        redisTemplate.opsForValue().increment(quotaKey(), text.length());
        log.debug("DeepL translation succeeded: lang={}, chars={}", detectedLanguage, text.length());
        return result.getText();
    }

    // Invoked by Resilience4j when callDeepL() throws QuotaExceededException, a 5xx, or a timeout.
    // Signature must mirror callDeepL() parameters with Exception appended.
    public String fallbackToClaude(String text, String detectedLanguage, Exception ex) {
        log.debug("DeepL failed ({}: {}) — falling back to Claude",
                ex.getClass().getSimpleName(), ex.getMessage());
        return callClaude(text);
    }

    private String callClaude(String text) {
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

    private boolean isQuotaNearlyExhausted() {
        String val = redisTemplate.opsForValue().get(quotaKey());
        if (val == null) return false;
        return Long.parseLong(val) >= QUOTA_THRESHOLD;
    }

    private String quotaKey() {
        return "deepl:quota:" + YearMonth.now();
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
