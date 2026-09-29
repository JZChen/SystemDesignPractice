package com.googledocs.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class WebSecurityConfig {

    @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://localhost:8080,http://127.0.0.1:8080}")
    private String allowedOrigins;

    @Value("${app.rate-limit.requests-per-minute:600}")
    private int requestsPerMinute;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        // If origins contains "*", allow wildcard, otherwise set exact origins
        if (origins.contains("*")) {
            config.addAllowedOriginPattern("*");
        } else {
            for (String origin : origins) {
                config.addAllowedOrigin(origin);
            }
        }

        config.setAllowCredentials(true);
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Session-Id", "Accept", "Origin"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    /**
     * Security Headers Filter: enforces nosniff, frame protection, and CSP.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public Filter securityHeadersFilter() {
        return new Filter() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                HttpServletResponse res = (HttpServletResponse) response;
                res.setHeader("X-Content-Type-Options", "nosniff");
                res.setHeader("X-Frame-Options", "SAMEORIGIN");
                res.setHeader("Content-Security-Policy",
                    "default-src 'self' 'unsafe-inline' https: http:; img-src 'self' data: https:; font-src 'self' https://fonts.gstatic.com;");
                chain.doFilter(request, response);
            }
        };
    }

    /**
     * Token Bucket Rate Limiting Filter: prevents denial-of-service / brute-force abuse on public tunnels.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public Filter rateLimitingFilter() {
        return new Filter() {
            private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                HttpServletRequest req = (HttpServletRequest) request;
                HttpServletResponse res = (HttpServletResponse) response;

                // Rate limit only API mutation / resource endpoints
                if (req.getRequestURI().startsWith("/api/")) {
                    String clientKey = req.getRemoteAddr();
                    String headerSession = req.getHeader("X-Session-Id");
                    if (headerSession != null && !headerSession.isBlank()) {
                        clientKey += ":" + headerSession;
                    }

                    TokenBucket bucket = buckets.computeIfAbsent(clientKey, k -> new TokenBucket(requestsPerMinute));
                    if (!bucket.tryConsume()) {
                        res.setStatus(429);
                        res.setContentType("application/json");
                        res.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Please slow down.\"}");
                        return;
                    }
                }
                chain.doFilter(request, response);
            }
        };
    }

    private static class TokenBucket {
        private final int capacity;
        private final AtomicInteger tokens;
        private long lastRefillTime;

        TokenBucket(int capacity) {
            this.capacity = capacity;
            this.tokens = new AtomicInteger(capacity);
            this.lastRefillTime = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            if (now - lastRefillTime >= 60000) {
                tokens.set(capacity);
                lastRefillTime = now;
            }
            int current = tokens.get();
            if (current > 0) {
                tokens.decrementAndGet();
                return true;
            }
            return false;
        }
    }
}
