package com.lingua_app.backend.service;

import com.lingua_app.backend.analysis.pipeline.AnalysisContext;
import com.lingua_app.backend.analysis.pipeline.AnalysisPipeline;
import com.lingua_app.backend.analysis.pipeline.WordCard;
import com.lingua_app.backend.dto.AnalysisRequest;
import com.lingua_app.backend.dto.AnalysisResponse;
import com.lingua_app.backend.dto.WordCardDto;
import com.lingua_app.backend.exception.AppException;
import io.github.bucket4j.Bucket;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnalysisService {

    private final RateLimitService rateLimitService;
    private final AnalysisPipeline analysisPipeline;

    public AnalysisService(RateLimitService rateLimitService, AnalysisPipeline analysisPipeline) {
        this.rateLimitService = rateLimitService;
        this.analysisPipeline = analysisPipeline;
    }

    public AnalysisResponse analyze(String userId, AnalysisRequest request) {
        String text = request.text().trim();

        if (text.isBlank() || text.length() > 500) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INPUT",
                    "Text must be between 1 and 500 characters.");
        }

        Bucket bucket = rateLimitService.getBucket(userId);
        if (!bucket.tryConsume(1)) {
            throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
                    "Rate limit exceeded. Try again later.", true);
        }

        AnalysisContext ctx = new AnalysisContext();
        ctx.setText(text);
        if (request.language() != null && !request.language().isBlank()) {
            ctx.setDetectedLanguage(request.language());
        }

        analysisPipeline.run(ctx);

        List<WordCardDto> words = ctx.getWords().stream()
                .map(this::toDto)
                .toList();

        return new AnalysisResponse(ctx.getDetectedLanguage(), ctx.getTranslation(), words);
    }

    private WordCardDto toDto(WordCard wc) {
        if (wc.getError() != null) {
            // Word-level pipeline failure: surface is all that is known
            return new WordCardDto(wc.getSurface(), null, null, null, null, null);
        }
        return new WordCardDto(
                wc.getSurface(),
                wc.getLemma(),
                wc.getPos(),
                wc.getGloss(),
                wc.getRomanization(),
                wc.getIpa()
        );
    }
}
