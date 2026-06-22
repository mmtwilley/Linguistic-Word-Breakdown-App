package com.lingua_app.backend.config;

import com.lingua_app.backend.security.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
// @EnableWebSecurity activates Spring Security's web security support and provides
// the integration with Spring MVC. In Spring Boot it is mostly implied, but declaring
// it explicitly makes the intent clear and enables additional tooling.
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // CSRF (Cross-Site Request Forgery) protection is disabled because this API
                // is stateless — it uses JWT Bearer tokens, not session cookies. CSRF attacks
                // rely on cookies being sent automatically by the browser, which doesn't apply here.
                .csrf(AbstractHttpConfigurer::disable)

                // STATELESS tells Spring never to create or use an HTTP session.
                // Each request must authenticate itself via the JWT — no server-side session state.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // Auth endpoints are public — anyone can register or log in.
                        .requestMatchers("POST", "/api/auth/**").permitAll()
                        // Every other endpoint requires a valid JWT.
                        .anyRequest().authenticated())

                // JwtAuthFilter runs before UsernamePasswordAuthenticationFilter.
                // By the time Spring's built-in filter runs, the SecurityContext is already
                // populated (if a valid JWT was present), so form-based login is skipped.
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
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
