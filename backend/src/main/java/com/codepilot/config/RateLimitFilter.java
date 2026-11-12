package com.codepilot.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Value("${codepilot.security.rate-limit.enabled:true}")
    private boolean enabled = true;

    @Value("${codepilot.security.rate-limit.auth-requests-per-minute:60}")
    private int authLimit = 60;

    @Value("${codepilot.security.rate-limit.webhook-requests-per-minute:300}")
    private int webhookLimit = 300;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        int limit = resolveLimit(path);
        if (limit <= 0) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = clientIp(request) + ":" + bucket(path);
        if (!allow(key, limit)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private int resolveLimit(String path) {
        if (path.startsWith("/api/v1/webhooks/github")) {
            return webhookLimit;
        }
        if (path.startsWith("/oauth2/") || path.startsWith("/login/oauth2/") || path.startsWith("/api/v1/auth/")) {
            return authLimit;
        }
        return -1;
    }

    private String bucket(String path) {
        return path.startsWith("/api/v1/webhooks/") ? "webhook" : "auth";
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean allow(String key, int limit) {
        long now = System.currentTimeMillis();
        if (windows.size() > MAX_TRACKED_KEYS) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().windowStart >= WINDOW_MS);
        }
        Window window = windows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart >= WINDOW_MS) {
                return new Window(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });
        return window.count.get() <= limit;
    }

    private record Window(long windowStart, AtomicInteger count) {}
}

