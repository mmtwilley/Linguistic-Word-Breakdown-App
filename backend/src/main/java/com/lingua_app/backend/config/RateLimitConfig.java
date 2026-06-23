package com.lingua_app.backend.config;

import com.lingua_app.backend.AppProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

@Configuration
public class RateLimitConfig {

    private final AppProperties appProperties;
    private final ProxyManager<String> proxyManager;

    public RateLimitConfig(AppProperties appProperties, LettuceConnectionFactory connectionFactory) {
        this.appProperties = appProperties;
        RedisClient client = (RedisClient) connectionFactory.getNativeClient();
        var connection = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        this.proxyManager = LettuceBasedProxyManager.builderFor(connection)
                .withExpirationAfterWrite(Duration.ofMinutes(2))
                .build();
    }

    @Bean
    ProxyManager<String> rateLimitProxyManager() {
        return proxyManager;
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
