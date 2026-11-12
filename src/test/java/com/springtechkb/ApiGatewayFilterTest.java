package com.springtechkb;

import jakarta.servlet.ServletException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiGatewayFilterTest {

    @Test
    void aiEngineReportsActiveRequestsOnlyWhileTheyAreInFlight() throws ServletException, IOException {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ApiRateLimiter limiter = new ApiRateLimiter(
                (key, ttlSeconds) -> 1L, meters, 60, "test:gateway", Clock.systemUTC());
        ApiGatewayFilter filter = new ApiGatewayFilter(limiter, ApiGatewayFilter.RuntimeRole.AI_ENGINE, meters);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/search");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertThat(meters.get("gateway.requests.active").gauge().value()).isEqualTo(1);
            ((MockHttpServletResponse) res).setStatus(204);
        });

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(meters.get("gateway.requests.active").gauge().value()).isZero();
    }

    @Test
    void aiEngineDecrementsActiveRequestsWhenFilterChainFails() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ApiRateLimiter limiter = new ApiRateLimiter(
                (key, ttlSeconds) -> 1L, meters, 60, "test:gateway", Clock.systemUTC());
        ApiGatewayFilter filter = new ApiGatewayFilter(limiter, ApiGatewayFilter.RuntimeRole.AI_ENGINE, meters);

        assertThatThrownBy(() -> filter.doFilter(
                new MockHttpServletRequest("GET", "/api/search"),
                new MockHttpServletResponse(),
                (req, res) -> {
                    assertThat(meters.get("gateway.requests.active").gauge().value()).isEqualTo(1);
                    throw new IOException("downstream failure");
                })).isInstanceOf(IOException.class);

        assertThat(meters.get("gateway.requests.active").gauge().value()).isZero();
    }

    @Test
    void aiEngineTracksAsyncRequestsUntilCompletion() throws ServletException, IOException {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ApiRateLimiter limiter = new ApiRateLimiter(
                (key, ttlSeconds) -> 1L, meters, 60, "test:gateway", Clock.systemUTC());
        ApiGatewayFilter filter = new ApiGatewayFilter(limiter, ApiGatewayFilter.RuntimeRole.AI_ENGINE, meters);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            ((MockHttpServletRequest) req).startAsync();
        });

        assertThat(meters.get("gateway.requests.active").gauge().value()).isEqualTo(1);
        request.getAsyncContext().complete();
        assertThat(meters.get("gateway.requests.active").gauge().value()).isZero();
    }

    @Test
    void kbIndexerRoleBlocksApiAndMcpTraffic() throws ServletException, IOException {
        ApiRateLimiter limiter = new ApiRateLimiter(
                (key, ttlSeconds) -> 1L, new SimpleMeterRegistry(), 60, "test:gateway", Clock.systemUTC());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ApiGatewayFilter filter = new ApiGatewayFilter(limiter, ApiGatewayFilter.RuntimeRole.KB_INDEXER, meters);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/search");
        MockHttpServletResponse response = new MockHttpServletResponse();
        OncePerRequestFilter filterRef = filter;
        filterRef.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(204));
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("not_found");
        assertThat(meters.get("gateway.requests.active").gauge().value()).isZero();
    }

    @Test
    void forwardedHeadersCannotGiveOneSocketPeerMultipleRateLimitIdentities() throws ServletException, IOException {
        ConcurrentHashMap<String, AtomicLong> requests = new ConcurrentHashMap<>();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ApiRateLimiter limiter = new ApiRateLimiter(
                (key, ttl) -> requests.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet(),
                meters, 1, "test:gateway", Clock.systemUTC());
        ApiGatewayFilter filter = new ApiGatewayFilter(limiter, ApiGatewayFilter.RuntimeRole.AI_ENGINE, meters);

        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/search");
        first.setRemoteAddr("192.0.2.10");
        first.addHeader("X-Forwarded-For", "198.51.100.1");
        first.addHeader("X-Real-IP", "198.51.100.1");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, (req, res) -> ((MockHttpServletResponse) res).setStatus(204));

        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/search");
        second.setRemoteAddr("192.0.2.10");
        second.addHeader("X-Forwarded-For", "198.51.100.2");
        second.addHeader("X-Real-IP", "198.51.100.2");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, (req, res) -> ((MockHttpServletResponse) res).setStatus(204));

        assertThat(firstResponse.getStatus()).isEqualTo(204);
        assertThat(secondResponse.getStatus()).isEqualTo(429);
    }
}
