package com.lingua_app.backend;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

// @ConfigurationProperties binds all properties prefixed with "app" from application.yaml
// into this class automatically. For example, app.jwt.secret maps to jwt.secret field.
// This is the recommended way to read typed config — avoids scattering @Value annotations
// across the codebase and makes config easy to test and mock.
@ConfigurationProperties(prefix = "app")
// @Configuration is required so Spring registers this class as a bean, which is necessary
// for @ConfigurationProperties binding to be applied by the context.
@Configuration
@Getter @Setter
public class AppProperties {

    // Nested objects group related config keys. Each inner class maps to a sub-prefix:
    //   app.jwt.*        → Jwt
    //   app.api.*        → Api
    //   app.rate-limit.* → RateLimit
    private final Jwt jwt = new Jwt();
    private final Api api = new Api();
    private final RateLimit rateLimit = new RateLimit();

    public Jwt getJwt() { return jwt; }
    public Api getApi() { return api; }
    public RateLimit getRateLimit() { return rateLimit; }

    @Getter @Setter
    public static class Jwt {
        private String secret;          // Base64-encoded HS256 signing key (min 32 bytes / 256 bits)
        private int expirySeconds;      // Access token TTL — keep short (e.g. 900 = 15 min)
        private int refreshExpiryDays;  // Refresh token TTL — longer-lived (e.g. 30 days)
    }

    @Getter @Setter
    public static class Api {
        private String claudeKey;   // Anthropic API key for morphological analysis
        private String deeplKey;    // DeepL API key for translation
        private String verdictKey;  // Dictionary API key
    }

    @Getter @Setter
    public static class RateLimit {
        private int rpm; // Max requests per minute per user, enforced via Bucket4j + Redis
    }

}
