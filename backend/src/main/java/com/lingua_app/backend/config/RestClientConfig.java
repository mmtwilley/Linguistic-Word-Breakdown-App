package com.lingua_app.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLParameters;
import java.net.http.HttpClient;

// Spring Boot 4 does not auto-configure a RestClient.Builder bean in this setup,
// so we expose one explicitly for injection into pipeline steps (e.g. DictionaryStep).
@Configuration
public class RestClientConfig {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);

    @Bean
    public RestClient.Builder restClientBuilder() {
        // Krdict's WAF returns 400 "Request Blocked" for requests it doesn't like:
        // force HTTP/1.1 (it appears to reject HTTP/2 negotiation) and send an
        // explicit browser-style User-Agent instead of the Java default.
        // Krdict's WAF also appears to fingerprint the TLS handshake and reject
        // Java's default TLS 1.3 ClientHello; forcing TLS 1.2 changes the
        // handshake shape enough to pass (curl/.NET clients are not blocked).
        SSLParameters tls12 = new SSLParameters();
        tls12.setProtocols(new String[] {"TLSv1.2"});
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .sslParameters(tls12)
                .build();
        return RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .defaultHeader("User-Agent", "Mozilla/5.0 (compatible; LinguaApp/0.1)")
                // The request factory otherwise adds Accept-Encoding as TWO separate
                // header lines (gzip / deflate), which Krdict's WAF rejects with
                // 400 "Request Blocked". Setting it explicitly keeps it a single line.
                .defaultHeader("Accept-Encoding", "gzip")
                .requestInterceptor((request, body, execution) -> {
                    log.debug("Outgoing request: {} {} headers={}",
                            request.getMethod(),
                            request.getURI().toString().replaceAll("key=[^&]*", "key=REDACTED"),
                            request.getHeaders());
                    return execution.execute(request, body);
                });
    }

    // Spring Boot 4 auto-configures Jackson 3 (tools.jackson), not Jackson 2.
    // ClaudeStep parses Anthropic SDK responses with the Jackson 2 ObjectMapper,
    // so we register one explicitly.
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
