package com.springtechkb;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

class ApiGatewayFilter extends OncePerRequestFilter {

    private final ApiRateLimiter rateLimiter;
    private final RuntimeRole runtimeRole;
    private final AtomicInteger activeRequests = new AtomicInteger();

    ApiGatewayFilter(ApiRateLimiter rateLimiter, RuntimeRole runtimeRole, MeterRegistry meters) {
        this.rateLimiter = rateLimiter;
        this.runtimeRole = runtimeRole;
        io.micrometer.core.instrument.Gauge.builder(
                        "gateway.requests.active", activeRequests, AtomicInteger::get)
                .description("In-flight requests admitted by the API gateway")
                .register(meters);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String path = request.getRequestURI().substring(contextPath.length());
        return !(path.equals("/api")
                || path.startsWith("/api/")
                || path.equals("/mcp")
                || path.startsWith("/mcp/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (runtimeRole == RuntimeRole.KB_INDEXER) {
            response.setStatus(404);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"not_found\"}");
            return;
        }
        String clientAddress = request.getRemoteAddr();
        if (!rateLimiter.allow(clientAddress)) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(
                    60 - Math.floorMod(Instant.now().getEpochSecond(), 60)));
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate_limit_exceeded\"}");
            return;
        }
        activeRequests.incrementAndGet();
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (request.isAsyncStarted()) {
                try {
                    request.getAsyncContext().addListener(new ActiveRequestListener(activeRequests));
                } catch (IllegalStateException completedBeforeListenerRegistration) {
                    activeRequests.decrementAndGet();
                }
            } else {
                activeRequests.decrementAndGet();
            }
        }
    }

    private static final class ActiveRequestListener implements AsyncListener {
        private final AtomicInteger activeRequests;
        private final AtomicBoolean completed = new AtomicBoolean();

        private ActiveRequestListener(AtomicInteger activeRequests) {
            this.activeRequests = activeRequests;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            complete();
        }

        @Override
        public void onTimeout(AsyncEvent event) {
        }

        @Override
        public void onError(AsyncEvent event) {
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }

        private void complete() {
            if (completed.compareAndSet(false, true)) {
                activeRequests.decrementAndGet();
            }
        }
    }

    enum RuntimeRole {
        ALL,
        AI_ENGINE,
        KB_INDEXER;

        static RuntimeRole from(String value) {
            if (value == null || value.isBlank()) return ALL;
            return switch (value.trim().replace('-', '_').toUpperCase()) {
                case "ALL" -> ALL;
                case "AI_ENGINE" -> AI_ENGINE;
                case "KB_INDEXER" -> KB_INDEXER;
                default -> throw new IllegalArgumentException(
                        "Unsupported SPRINGTECHKB_RUNTIME_ROLE: " + value
                                + ". Allowed values: all, ai-engine, kb-indexer.");
            };
        }
    }
}
