package com.vookedme.botmanager.security;

import com.vookedme.botmanager.security.ratelimit.RateLimitingFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link RateLimitingFilter}. No Spring context — drives the
 * filter directly using {@link MockHttpServletRequest} /
 * {@link MockHttpServletResponse}.
 *
 * <p><b>Note on ObjectMapper:</b> Spring Boot 4 auto-configures Jackson 3
 * ({@code tools.jackson.databind}). Java time types are supported natively
 * without a JSR-310 module. {@code JsonMapper.builder()} mirrors the shape
 * of the production-injected bean.
 */
class RateLimitingFilterTest {

    private RateLimitingFilter filter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        filter = new RateLimitingFilter(objectMapper);
    }

    private MockHttpServletRequest req(String method, String uri, String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRemoteAddr(ip);
        return r;
    }

    @Nested
    @DisplayName("/auth/login (5 requests / 2 min)")
    class LoginLimit {

        @Test
        @DisplayName("allows the first 5 requests, blocks the 6th with 429")
        void blocksAfterFifth() throws Exception {
            FilterChain chain = mock(FilterChain.class);
            String ip = "203.0.113.10";

            for (int i = 1; i <= 5; i++) {
                MockHttpServletRequest req = req("POST", "/auth/login", ip);
                MockHttpServletResponse res = new MockHttpServletResponse();
                filter.doFilter(req, res, chain);
                assertThat(res.getStatus()).isEqualTo(HttpStatus.OK.value()); // mock default 200
            }
            // Chain invoked 5 times = 5 requests passed to the next filter.
            verify(chain, times(5)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

            MockHttpServletRequest sixth = req("POST", "/auth/login", ip);
            MockHttpServletResponse sixthRes = new MockHttpServletResponse();
            filter.doFilter(sixth, sixthRes, chain);

            assertThat(sixthRes.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            // Chain not invoked a 6th time.
            verify(chain, times(5)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("429 body has Spanish user-facing message and Retry-After header")
        void localeMessageAndRetryAfter() throws Exception {
            FilterChain chain = mock(FilterChain.class);
            String ip = "203.0.113.20";

            // Exhaust the bucket.
            for (int i = 0; i < 5; i++) {
                filter.doFilter(req("POST", "/auth/login", ip), new MockHttpServletResponse(), chain);
            }
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req("POST", "/auth/login", ip), res, chain);

            assertThat(res.getStatus()).isEqualTo(429);
            assertThat(res.getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
            long retryAfter = Long.parseLong(res.getHeader(HttpHeaders.RETRY_AFTER));
            assertThat(retryAfter).isPositive().isLessThanOrEqualTo(2 * 60);

            JsonNode body = objectMapper.readTree(res.getContentAsString());
            assertThat(body.get("success").asBoolean()).isFalse();
            // LOCALE OUTPUT (Spanish): assert on the locale-specific user-facing message
            assertThat(body.get("message").asText())
                    .isEqualTo(RateLimitingFilter.MESSAGE_TOO_MANY_REQUESTS)
                    .contains("Demasiados intentos");
        }

        @Test
        @DisplayName("different IPs have independent buckets")
        void perIpIsolation() throws Exception {
            FilterChain chain = mock(FilterChain.class);

            // IP A exhausts its bucket.
            for (int i = 0; i < 5; i++) {
                filter.doFilter(req("POST", "/auth/login", "1.1.1.1"), new MockHttpServletResponse(), chain);
            }
            MockHttpServletResponse aBlocked = new MockHttpServletResponse();
            filter.doFilter(req("POST", "/auth/login", "1.1.1.1"), aBlocked, chain);
            assertThat(aBlocked.getStatus()).isEqualTo(429);

            // IP B still has a full bucket.
            MockHttpServletResponse bOk = new MockHttpServletResponse();
            filter.doFilter(req("POST", "/auth/login", "2.2.2.2"), bOk, chain);
            assertThat(bOk.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("uses the first hop of X-Forwarded-For when present")
        void usesForwardedFor() throws Exception {
            FilterChain chain = mock(FilterChain.class);

            for (int i = 0; i < 5; i++) {
                MockHttpServletRequest r = req("POST", "/auth/login", "10.0.0.1");
                r.addHeader("X-Forwarded-For", "203.0.113.99, 10.0.0.5");
                filter.doFilter(r, new MockHttpServletResponse(), chain);
            }

            // Same real IP via XFF, different remoteAddr → should be blocked.
            MockHttpServletRequest sixth = req("POST", "/auth/login", "10.0.0.99");
            sixth.addHeader("X-Forwarded-For", "203.0.113.99");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(sixth, res, chain);

            assertThat(res.getStatus()).isEqualTo(429);
        }
    }

    @Nested
    @DisplayName("/auth/refresh (10 requests / 2 min) — independent bucket from login")
    class RefreshLimit {

        @Test
        @DisplayName("allows 10 then blocks 11th")
        void allowsTen() throws Exception {
            FilterChain chain = mock(FilterChain.class);
            String ip = "203.0.113.30";

            for (int i = 0; i < 10; i++) {
                MockHttpServletResponse res = new MockHttpServletResponse();
                filter.doFilter(req("POST", "/auth/refresh", ip), res, chain);
                assertThat(res.getStatus()).isEqualTo(200);
            }
            MockHttpServletResponse blocked = new MockHttpServletResponse();
            filter.doFilter(req("POST", "/auth/refresh", ip), blocked, chain);
            assertThat(blocked.getStatus()).isEqualTo(429);
        }

        @Test
        @DisplayName("login and refresh buckets are separate")
        void loginRefreshIsolation() throws Exception {
            FilterChain chain = mock(FilterChain.class);
            String ip = "203.0.113.40";

            // Exhaust login bucket.
            for (int i = 0; i < 5; i++) {
                filter.doFilter(req("POST", "/auth/login", ip), new MockHttpServletResponse(), chain);
            }
            MockHttpServletResponse loginBlocked = new MockHttpServletResponse();
            filter.doFilter(req("POST", "/auth/login", ip), loginBlocked, chain);
            assertThat(loginBlocked.getStatus()).isEqualTo(429);

            // Refresh bucket still free from the same IP.
            MockHttpServletResponse refreshOk = new MockHttpServletResponse();
            filter.doFilter(req("POST", "/auth/refresh", ip), refreshOk, chain);
            assertThat(refreshOk.getStatus()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("/auth/register/business (3 requests / 10 min)")
    class RegisterBusinessLimit {

        @Test
        @DisplayName("allows 3 then blocks 4th")
        void allowsThree() throws Exception {
            FilterChain chain = mock(FilterChain.class);
            String ip = "203.0.113.50";

            for (int i = 0; i < 3; i++) {
                MockHttpServletResponse res = new MockHttpServletResponse();
                filter.doFilter(req("POST", "/auth/register/business", ip), res, chain);
                assertThat(res.getStatus()).isEqualTo(200);
            }
            MockHttpServletResponse blocked = new MockHttpServletResponse();
            filter.doFilter(req("POST", "/auth/register/business", ip), blocked, chain);
            assertThat(blocked.getStatus()).isEqualTo(429);
        }
    }

    @Nested
    @DisplayName("Endpoints outside the rate-limit scope are not rate-limited")
    class OutOfScope {

        @Test
        @DisplayName("GET /auth/me passes through without bucket consumption")
        void getMeIsUnaffected() throws Exception {
            FilterChain chain = mock(FilterChain.class);
            for (int i = 0; i < 100; i++) {
                MockHttpServletResponse res = new MockHttpServletResponse();
                filter.doFilter(req("GET", "/auth/me", "1.1.1.1"), res, chain);
                assertThat(res.getStatus()).isEqualTo(200);
            }
            verify(chain, times(100)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("/api/businesses/... is not rate-limited")
        void businessApiIsUnaffected() throws Exception {
            FilterChain chain = mock(FilterChain.class);
            for (int i = 0; i < 50; i++) {
                MockHttpServletResponse res = new MockHttpServletResponse();
                filter.doFilter(req("GET", "/api/businesses/1/customers", "1.1.1.1"), res, chain);
                assertThat(res.getStatus()).isEqualTo(200);
            }
            verify(chain, times(50)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("GET /auth/login (wrong method) is not rate-limited")
        void wrongMethodNotLimited() throws Exception {
            FilterChain chain = mock(FilterChain.class);
            for (int i = 0; i < 20; i++) {
                MockHttpServletResponse res = new MockHttpServletResponse();
                filter.doFilter(req("GET", "/auth/login", "1.1.1.1"), res, chain);
            }
            verify(chain, times(20)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested
    @DisplayName("Cleanup job")
    class Cleanup {

        @Test
        @DisplayName("cleanupExpired does not throw on empty state")
        void emptyStateClean() {
            filter.cleanupExpired();
        }

        @Test
        @DisplayName("resetForTests clears in-memory state")
        void resetClearsState() throws Exception {
            FilterChain chain = mock(FilterChain.class);
            String ip = "203.0.113.60";

            for (int i = 0; i < 5; i++) {
                filter.doFilter(req("POST", "/auth/login", ip), new MockHttpServletResponse(), chain);
            }
            // Confirm bucket is exhausted.
            MockHttpServletResponse blocked = new MockHttpServletResponse();
            filter.doFilter(req("POST", "/auth/login", ip), blocked, chain);
            assertThat(blocked.getStatus()).isEqualTo(429);

            filter.resetForTests();

            MockHttpServletResponse afterReset = new MockHttpServletResponse();
            filter.doFilter(req("POST", "/auth/login", ip), afterReset, chain);
            assertThat(afterReset.getStatus()).isEqualTo(200);
        }
    }
}
