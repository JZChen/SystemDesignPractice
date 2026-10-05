package com.collabcanvas.config;

import com.collabcanvas.board.RateLimiter;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Security headers, HTTP method allow-list and REST rate limiting.
 *
 * <p>The page loads only same-origin scripts and styles (no inline code), so the CSP can be strict.
 * {@code connect-src 'self'} also covers same-origin ws:/wss: in current browsers (CSP Level 3).
 */
@Configuration
public class SecurityConfig implements WebMvcConfigurer {

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; "
            + "frame-ancestors 'none'";
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "HEAD");
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    @Value("${app.rate-limit.requests-per-minute:120}")
    private int requestsPerMinute;

    /** Board page: /b/{boardId} serves the same static app. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/b/{boardId}").setViewName("forward:/index.html");
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public Filter securityHeadersFilter() {
        return (req, res, chain) -> {
            HttpServletRequest request = (HttpServletRequest) req;
            HttpServletResponse response = (HttpServletResponse) res;
            if (!ALLOWED_METHODS.contains(request.getMethod())) {
                response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                return;
            }
            response.setHeader("Content-Security-Policy", CSP);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Referrer-Policy", "no-referrer");     // board links are capabilities
            response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
            if (request.getRequestURI().startsWith("/api/")) {
                response.setHeader("Cache-Control", "no-store");
            }
            chain.doFilter(req, res);
        };
    }

    /** Per-IP token bucket on /api/** (board creation and lookups). WebSocket traffic has its own limits. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public Filter apiRateLimitFilter() {
        Map<String, RateLimiter> buckets = new ConcurrentHashMap<>();
        return (req, res, chain) -> {
            HttpServletRequest request = (HttpServletRequest) req;
            if (request.getRequestURI().startsWith("/api/")) {
                if (buckets.size() > MAX_TRACKED_CLIENTS) {
                    buckets.clear(); // TODO(security): bounded LRU instead of a coarse reset
                }
                RateLimiter bucket = buckets.computeIfAbsent(request.getRemoteAddr(),
                        k -> new RateLimiter(requestsPerMinute / 60.0, requestsPerMinute));
                if (!bucket.tryAcquire(1)) {
                    HttpServletResponse response = (HttpServletResponse) res;
                    response.setStatus(429);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"rate_limited\"}");
                    return;
                }
            }
            chain.doFilter(req, res);
        };
    }
}
