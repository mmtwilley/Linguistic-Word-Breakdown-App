package com.lingua_app.backend.service;

import com.lingua_app.backend.AppProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimitService {

    private final ProxyManager<String> proxyManager;
    private final AppProperties appProperties;

    public RateLimitService(ProxyManager<String> proxyManager, AppProperties appProperties) {
        this.proxyManager = proxyManager;
        this.appProperties = appProperties;
    }

    public Bucket getBucket(String userId) {
        int rpm = appProperties.getRateLimit().getRpm();
        BucketConfiguration config = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(rpm)
                        .refillIntervally(rpm, Duration.ofMinutes(1))
                        .build())
                .build();
        return proxyManager.builder().build(userId, () -> config);
    }
}
