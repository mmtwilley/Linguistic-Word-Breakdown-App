package com.lingua_app.backend.config;

import com.lingua_app.backend.security.JwtAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
// @EnableWebSecurity activates Spring Security's web security support and provides
// the integration with Spring MVC. In Spring Boot it is mostly implied, but declaring
// it explicitly makes the intent clear and enables additional tooling.
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final Environment environment;

    // Comma-separated list of allowed origins, e.g. "https://app.example.com,https://admin.example.com".
    // Wildcard * is intentionally not supported — setAllowedOrigins() rejects it when credentials are enabled.
    @Value("${ALLOWED_ORIGINS:}")
    private String allowedOriginsRaw;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, Environment environment) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.environment = environment;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // Constitution Principle II: HTTPS is mandatory outside local development.
        // With server.ssl.* active (prod profile), Tomcat's TLS connector already
        // rejects plain-HTTP requests with 400; this rule additionally covers
        // setups where TLS terminates at a proxy (honours X-Forwarded-Proto).
        if (environment.matchesProfiles("prod")) {
            http.redirectToHttps(Customizer.withDefaults());
        }
        return http
                // CSRF (Cross-Site Request Forgery) protection is disabled because this API
                // is stateless — it uses JWT Bearer tokens, not session cookies. CSRF attacks
                // rely on cookies being sent automatically by the browser, which doesn't apply here.
                .csrf(AbstractHttpConfigurer::disable)

                // Delegate CORS handling to corsConfigurationSource(). Origins not in the
                // ALLOWED_ORIGINS list are rejected at the preflight (OPTIONS) stage.
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // STATELESS tells Spring never to create or use an HTTP session.
                // Each request must authenticate itself via the JWT — no server-side session state.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // Auth endpoints are public — anyone can register or log in.
                        .requestMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
                        // Every other endpoint requires a valid JWT.
                        .anyRequest().authenticated())

                // JwtAuthFilter runs before UsernamePasswordAuthenticationFilter.
                // By the time Spring's built-in filter runs, the SecurityContext is already
                // populated (if a valid JWT was present), so form-based login is skipped.
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.stream(allowedOriginsRaw.split(","))
                .map(s -> s.trim())
                .filter(s -> !s.isEmpty())
                .toList();

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // Allow credentials so the Authorization header is readable cross-origin.
        // This is only valid with explicit origins — browsers reject credentials + wildcard.
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    // BCryptPasswordEncoder with strength 12 means 2^12 = 4096 hashing rounds.
    // Higher strength = slower hashing = harder brute-force. 12 is a standard production value.
    // This bean is injected into AuthService for hashing passwords at registration.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    // Exposing AuthenticationManager as a bean lets AuthService use it directly
    // to authenticate login requests (username + password) without wiring Spring Security
    // internals manually.
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
