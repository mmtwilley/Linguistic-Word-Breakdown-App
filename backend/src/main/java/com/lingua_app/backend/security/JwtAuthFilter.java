package com.lingua_app.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// OncePerRequestFilter guarantees this filter runs exactly once per HTTP request,
// even in servlet environments where a request might be dispatched multiple times internally.
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;

    public JwtAuthFilter(JwtService jwtService, UserDetailsServiceImpl userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // If no Bearer token is present, pass the request through without authenticating.
        // Spring Security will then enforce access rules downstream — a protected endpoint
        // will result in a 401 automatically, without this filter needing to know which
        // endpoints are public vs. protected.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Strip the "Bearer " prefix (7 characters) to get the raw token string.
        String token = authHeader.substring(7);

        // Invalid or expired token → pass through without setting authentication.
        // The downstream security rules will reject the request if the endpoint requires auth.
        if (!jwtService.validateToken(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Only set authentication if none already exists — avoids overwriting auth
        // that might have been set by an earlier filter in the chain.
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            // Loading UserDetails confirms the account still exists and is active,
            // even though the JWT signature already proved authenticity.
            UserDetails userDetails = userDetailsService.loadUserByUsername(
                    jwtService.extractEmail(token)
            );

            // UsernamePasswordAuthenticationToken with 3 args = authenticated principal.
            // The principal is the userId from the JWT "sub" claim (not the email), so
            // Authentication.getName() yields the stable UUID — used downstream as the
            // rate-limit bucket key and for any per-user resource ownership checks.
            // Credentials are null because authentication was confirmed by the JWT signature.
            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(
                            jwtService.extractUserId(token).toString(),
                            null,
                            userDetails.getAuthorities());

            // Attaches request metadata (IP address, session ID) to the auth token,
            // useful for audit logging and security events.
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
        }

        filterChain.doFilter(request, response);
    }
}
