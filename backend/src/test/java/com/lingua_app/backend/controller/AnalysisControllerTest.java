package com.lingua_app.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingua_app.backend.analysis.pipeline.Confidence;
import com.lingua_app.backend.analysis.pipeline.IssueCode;
import com.lingua_app.backend.dto.AnalysisRequest;
import com.lingua_app.backend.dto.AnalysisResponse;
import com.lingua_app.backend.dto.ValidationIssueDto;
import com.lingua_app.backend.dto.WordCardDto;
import com.lingua_app.backend.exception.AppException;
import com.lingua_app.backend.security.JwtService;
import com.lingua_app.backend.security.UserDetailsServiceImpl;
import com.lingua_app.backend.service.AnalysisService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AnalysisController.class)
class AnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private AnalysisService analysisService;

    // JwtAuthFilter depends on these — must be present in the web context.
    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void resetSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username) {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(ctx);
    }

    @Test
    void analyze_missingToken_returns401() throws Exception {
        // SecurityContextHolder is empty — controller throws UNAUTHORIZED → 401
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AnalysisRequest("오늘 날씨", null))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void analyze_emptyText_returns400ValidationError() throws Exception {
        authenticateAs("testUser");
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AnalysisRequest("", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void analyze_textTooLong_returns400ValidationError() throws Exception {
        authenticateAs("testUser");
        String longText = "a".repeat(501);
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AnalysisRequest(longText, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void analyze_rateLimited_returns429RateLimitExceeded() throws Exception {
        authenticateAs("testUser");
        when(analysisService.analyze(any(), any()))
                .thenThrow(new AppException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
                        "Rate limit exceeded. Try again later.", true));

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AnalysisRequest("오늘 날씨", null))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.error.retryable").value(true));
    }

    @Test
    void analyze_validKoreanText_returns200WithLanguageAndWords() throws Exception {
        authenticateAs("testUser");
        AnalysisResponse mockResponse = new AnalysisResponse(
                "kor",
                "Today the weather is really nice.",
                List.of(
                        new WordCardDto("오늘", "오늘", "NOUN", "today", "oneul", null),
                        new WordCardDto("날씨", "날씨", "NOUN", "weather", "nalssiga", null)
                ),
                Confidence.HIGH,
                List.of()
        );
        when(analysisService.analyze(any(), any())).thenReturn(mockResponse);

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AnalysisRequest("오늘 날씨가 정말 좋네요", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("kor"))
                .andExpect(jsonPath("$.translation").value("Today the weather is really nice."))
                .andExpect(jsonPath("$.words").isArray())
                .andExpect(jsonPath("$.words.length()").value(2))
                .andExpect(jsonPath("$.words[0].surface").value("오늘"))
                .andExpect(jsonPath("$.words[0].romanization").value("oneul"))
                .andExpect(jsonPath("$.confidence").value("high"))
                .andExpect(jsonPath("$.issues").isArray())
                .andExpect(jsonPath("$.issues").isEmpty());
    }

    @Test
    void analyze_perCardIssue_serializesFullIssueShape_andCanonicalPos() throws Exception {
        authenticateAs("testUser");
        AnalysisResponse mockResponse = new AnalysisResponse(
                "kor",
                "Today the weather is really nice.",
                List.of(
                        new WordCardDto("오늘", "오늘", "noun", "today", "oneul", null),
                        new WordCardDto("날씨가", "날씨", "noun", null, "nalssiga", null)
                ),
                Confidence.MEDIUM,
                List.of(new ValidationIssueDto(
                        IssueCode.MISSING_FIELD,
                        ValidationIssueDto.Severity.WARNING,
                        "날씨가",
                        "This word card is missing its base form or meaning."))
        );
        when(analysisService.analyze(any(), any())).thenReturn(mockResponse);

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AnalysisRequest("오늘 날씨가 정말 좋네요", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confidence").value("medium"))
                .andExpect(jsonPath("$.issues.length()").value(1))
                .andExpect(jsonPath("$.issues[0].code").value("MISSING_FIELD"))
                .andExpect(jsonPath("$.issues[0].severity").value("warning"))
                .andExpect(jsonPath("$.issues[0].surface").value("날씨가"))
                .andExpect(jsonPath("$.issues[0].detail").isNotEmpty())
                .andExpect(jsonPath("$.words[0].pos").value("noun"))
                .andExpect(jsonPath("$.words[1].pos").value("noun"));
    }

    @Test
    void analyze_degradedResult_serializesConfidenceAndIssueShape() throws Exception {
        authenticateAs("testUser");
        AnalysisResponse mockResponse = new AnalysisResponse(
                "cmn",
                "Learning Chinese is really interesting.",
                List.of(),
                Confidence.LOW,
                List.of(new ValidationIssueDto(
                        IssueCode.EMPTY_ANALYSIS,
                        ValidationIssueDto.Severity.ERROR,
                        null,
                        "No word cards were produced for this text."))
        );
        when(analysisService.analyze(any(), any())).thenReturn(mockResponse);

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AnalysisRequest("学习中文很有意思", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confidence").value("low"))
                .andExpect(jsonPath("$.issues.length()").value(1))
                .andExpect(jsonPath("$.issues[0].code").value("EMPTY_ANALYSIS"))
                .andExpect(jsonPath("$.issues[0].severity").value("error"))
                .andExpect(jsonPath("$.issues[0].surface").doesNotExist())
                .andExpect(jsonPath("$.issues[0].detail").isNotEmpty());
    }
}
