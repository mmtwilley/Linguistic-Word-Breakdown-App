package com.lingua_app.backend.integration;

import com.lingua_app.backend.analysis.pipeline.AnalysisContext;
import com.lingua_app.backend.analysis.pipeline.WordCard;
import com.lingua_app.backend.analysis.step.ClaudeStep;
import com.lingua_app.backend.analysis.step.DictionaryStep;
import com.lingua_app.backend.analysis.step.TranslationStep;
import com.lingua_app.backend.dto.AnalysisRequest;
import com.lingua_app.backend.dto.AnalysisResponse;
import com.lingua_app.backend.dto.AuthResponse;
import com.lingua_app.backend.dto.LoginRequest;
import com.lingua_app.backend.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

// Full pipeline integration test against real PostgreSQL + Redis.
// TranslationStep, DictionaryStep, and ClaudeStep are mocked to avoid
// network calls to DeepL, Krdict, and Anthropic. DetectionStep and
// RomanizationStep run with real ICU4J logic.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "JWT_SECRET=c3VwZXJzZWNyZXRrZXkxMjM0NTY3ODkwYWJjZGVmZ2hpams=",
                "JWT_EXPIRY_SECONDS=900",
                "REFRESH_TOKEN_EXPIRY_DAYS=1",
                "CLAUDE_API_KEY=test-key",
                "DEEPL_API_KEY=test-key",
                "VERDICT_API_KEY=test-key",
                "management.health.redis.enabled=false"
        }
)
@Testcontainers
class AnalysisPipelineIT {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("lingua_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", postgres::getJdbcUrl);
        registry.add("DATABASE_USERNAME", postgres::getUsername);
        registry.add("DATABASE_PASSWORD", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private TranslationStep translationStep;

    @MockitoBean
    private DictionaryStep dictionaryStep;

    @MockitoBean
    private ClaudeStep claudeStep;

    private String registerAndLogin(String email, String password) {
        restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(email, password), Void.class);
        ResponseEntity<AuthResponse> login = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(email, password), AuthResponse.class);
        return login.getBody().accessToken();
    }

    private HttpEntity<AnalysisRequest> authenticatedRequest(String token, AnalysisRequest body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void analyze_koreanText_returnsKorLanguageTranslationAndRomanization() throws Exception {
        doAnswer(inv -> {
            AnalysisContext ctx = inv.getArgument(0);
            ctx.setTranslation("Today the weather is really nice.");
            return null;
        }).when(translationStep).run(any());

        doAnswer(inv -> {
            AnalysisContext ctx = inv.getArgument(0);
            ctx.getWords().add(WordCard.builder().surface("오늘").build());
            ctx.getWords().add(WordCard.builder().surface("날씨").build());
            ctx.getWords().add(WordCard.builder().surface("좋네요").build());
            return null;
        }).when(dictionaryStep).run(any());

        String token = registerAndLogin("pipeline_kor@example.com", "Password123!");

        ResponseEntity<AnalysisResponse> response = restTemplate.postForEntity(
                "/api/analyze",
                authenticatedRequest(token, new AnalysisRequest("오늘 날씨가 정말 좋네요", null)),
                AnalysisResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().language()).isEqualTo("kor");
        assertThat(response.getBody().translation()).isNotBlank();
        assertThat(response.getBody().words()).isNotEmpty();
        assertThat(response.getBody().words())
                .allSatisfy(word -> assertThat(word.romanization()).isNotNull());
    }

    @Test
    void analyze_englishText_returnsWordsWithNullRomanization() throws Exception {
        doAnswer(inv -> {
            AnalysisContext ctx = inv.getArgument(0);
            ctx.setTranslation("The dog runs fast.");
            return null;
        }).when(translationStep).run(any());

        doAnswer(inv -> {
            AnalysisContext ctx = inv.getArgument(0);
            ctx.getWords().add(WordCard.builder().surface("The").build());
            ctx.getWords().add(WordCard.builder().surface("dog").build());
            ctx.getWords().add(WordCard.builder().surface("runs").build());
            return null;
        }).when(dictionaryStep).run(any());

        String token = registerAndLogin("pipeline_eng@example.com", "Password123!");

        ResponseEntity<AnalysisResponse> response = restTemplate.postForEntity(
                "/api/analyze",
                authenticatedRequest(token, new AnalysisRequest("The dog runs fast.", null)),
                AnalysisResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().words()).isNotEmpty();
        assertThat(response.getBody().words())
                .allSatisfy(word -> assertThat(word.romanization()).isNull());
    }
}
