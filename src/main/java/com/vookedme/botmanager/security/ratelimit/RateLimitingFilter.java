package com.vookedme.botmanager.security.ratelimit;

import com.vookedme.botmanager.common.dto.ApiResponse;
import com.vookedme.botmanager.config.observability.ObservabilityHelper;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory per-IP rate limiter for sensitive authentication endpoints.
 *
 * <p><b>Architectural decision:</b> this filter uses {@link ConcurrentHashMap}
 * and {@link AtomicReference} — in-memory, single-instance, no Redis. This is
 * sufficient for the current single-instance deployment. The class comment
 * documents exactly what changes when the deployment scales to multiple nodes:
 * replace {@link ConcurrentHashMap} with a {@code RedisTemplate}-backed
 * distributed store, preserving the same public interface.
 *
 * <p><b>Algorithm:</b> fixed sliding window per (path, IP). When the limit is
 * exceeded, the filter responds with HTTP 429 and a {@code Retry-After}
 * header without invoking the rest of the chain. The window resets per key
 * when it expires; a scheduled cleanup job removes expired entries every
 * 30 minutes to prevent memory leaks from ephemeral IPs.
 *
 * <p><b>Protected limits:</b>
 * <ul>
 *   <li>{@code POST /auth/login}              → 5 requests / 2 min</li>
 *   <li>{@code POST /auth/refresh}            → 10 requests / 2 min</li>
 *   <li>{@code POST /auth/register/business}  → 3 requests / 10 min</li>
 *   <li>{@code POST /auth/forgot-password}    → 3 requests / 1 h</li>
 *   <li>{@code POST /auth/reset-password}     → 5 requests / 10 min</li>
 * </ul>
 *
 * <p>The 429 response message is in Spanish (the operating locale output for
 * user-facing errors in this deployment).
 *
 * <p><b>Note on Jackson import:</b> Spring Boot 4 auto-configures
 * Jackson 3 ({@code tools.jackson.databind}), not the legacy
 * {@code com.fasterxml.jackson.databind}. This filter uses the same
 * Jackson 3 bean as the rest of the application.
 *
 * <p>{@link ObservabilityHelper} is published in a subsequent source batch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitingFilter extends OncePerRequestFilter {

    /**
     * Configuration for a protected endpoint: method + exact path + bucket limits.
     * Path is compared by exact match against {@link HttpServletRequest#getRequestURI()}.
     */
    private record RateLimitRule(String method, String path, int capacity, Duration window) {}

    private static final List<RateLimitRule> RULES = List.of(
            new RateLimitRule("POST", "/auth/login",             5,  Duration.ofMinutes(2)),
            new RateLimitRule("POST", "/auth/refresh",          10,  Duration.ofMinutes(2)),
            new RateLimitRule("POST", "/auth/register/business", 3,  Duration.ofMinutes(10)),
            // Per-IP rate limit on password reset endpoints.
            // 3/hour on forgot-password prevents email-bombing;
            // 5/10min on reset-password covers legitimate retries (password typos).
            new RateLimitRule("POST", "/auth/forgot-password",   3,  Duration.ofHours(1)),
            new RateLimitRule("POST", "/auth/reset-password",    5,  Duration.ofMinutes(10))
    );

    /**
     * User-facing 429 response message. Spanish locale output for this deployment.
     *
     * @see <a href="#locale-output">Locale output annotation</a>
     */
    // LOCALE OUTPUT (Spanish): user-facing rate-limit error message
    static final String MESSAGE_TOO_MANY_REQUESTS =
            "Demasiados intentos. Por favor espera unos minutos antes de intentarlo de nuevo.";

    /** Map: rulePath → map(ip → bucket). */
    private final Map<String, Map<String, AtomicReference<Bucket>>> buckets = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        RateLimitRule rule = matchRule(request);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = extractIp(request);
        Map<String, AtomicReference<Bucket>> perIp =
                buckets.computeIfAbsent(rule.path(), k -> new ConcurrentHashMap<>());
        AtomicReference<Bucket> ref =
                perIp.computeIfAbsent(ip, k -> new AtomicReference<>(Bucket.fresh(rule)));

        Instant now = Instant.now();
        boolean allowed = tryConsume(ref, rule, now);

        if (allowed) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = secondsUntilReset(ref, rule, now);
        writeTooManyRequests(response, retryAfterSeconds);
        // Log without the full IP: hash for correlation without exposing PII to the
        // log stream. The full IP is recorded only in the consent audit row.
        log.warn("Rate limit exceeded path={} ipHash={}", rule.path(), Integer.toHexString(ip.hashCode()));
    }

    /**
     * CAS loop over the bucket: if the current window has expired, replace it
     * with a fresh one. Otherwise, attempt to decrement the token count.
     * Returns {@code true} if the request consumed a token; {@code false} if
     * the window is live and no tokens remain.
     */
    private boolean tryConsume(AtomicReference<Bucket> ref, RateLimitRule rule, Instant now) {
        while (true) {
            Bucket current = ref.get();
            if (current.isExpired(rule, now)) {
                Bucket fresh = Bucket.consumedFresh(rule, now);
                if (ref.compareAndSet(current, fresh)) {
                    return true;
                }
                // Another thread also renewed the bucket — re-read and retry.
                continue;
            }
            if (current.tokensRemaining <= 0) {
                return false;
            }
            Bucket next = new Bucket(current.tokensRemaining - 1, current.windowStart);
            if (ref.compareAndSet(current, next)) {
                return true;
            }
            // CAS lost — retry.
        }
    }

    private long secondsUntilReset(AtomicReference<Bucket> ref, RateLimitRule rule, Instant now) {
        Bucket b = ref.get();
        Instant resetAt = b.windowStart.plus(rule.window());
        long diff = Duration.between(now, resetAt).getSeconds();
        return Math.max(diff, 1);
    }

    private RateLimitRule matchRule(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        for (RateLimitRule rule : RULES) {
            if (rule.method().equals(method) && rule.path().equals(uri)) {
                return rule;
            }
        }
        return null;
    }

    private String extractIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String remote = request.getRemoteAddr();
        return remote != null ? remote : "unknown";
    }

    private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));

        ApiResponse<Void> body = ApiResponse.error(MESSAGE_TOO_MANY_REQUESTS);
        objectMapper.writeValue(response.getWriter(), body);
    }

    /**
     * Periodic cleanup of IPs whose window has expired. Prevents memory leaks
     * from ephemeral IPs (mobile NAT, rotating scrapers). Runs every 30 minutes.
     *
     * <p>No global lock: each {@link ConcurrentHashMap} is individually
     * thread-safe and the cleanup is tolerant of an entry that is "saved"
     * between the check and the remove.
     *
     * <p>Failures are reported to the observability infrastructure via
     * {@link ObservabilityHelper} rather than crashing the scheduler thread.
     * {@code ObservabilityHelper} is published in a subsequent source batch.
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000L) // 30 minutes
    void cleanupExpired() {
        try {
            Instant now = Instant.now();
            int removed = 0;
            for (Map.Entry<String, Map<String, AtomicReference<Bucket>>> entry : buckets.entrySet()) {
                String path = entry.getKey();
                RateLimitRule rule = findRuleByPath(path);
                if (rule == null) continue;
                Map<String, AtomicReference<Bucket>> perIp = entry.getValue();
                for (Map.Entry<String, AtomicReference<Bucket>> ipEntry : perIp.entrySet()) {
                    if (ipEntry.getValue().get().isExpired(rule, now)) {
                        perIp.remove(ipEntry.getKey(), ipEntry.getValue());
                        removed++;
                    }
                }
            }
            if (removed > 0) {
                log.debug("Rate-limit cleanup removed {} expired buckets", removed);
            }
        } catch (Exception e) {
            ObservabilityHelper.reportBackgroundFailure("ratelimit-cleanup", e);
        }
    }

    private RateLimitRule findRuleByPath(String path) {
        for (RateLimitRule rule : RULES) {
            if (rule.path().equals(path)) return rule;
        }
        return null;
    }

    /**
     * Test-only hook to clear in-memory state between tests.
     *
     * <p><b>NOT a production API.</b> Public only so that {@code BaseIntegrationTest}
     * (in a different package) can call it in {@code @BeforeEach} and
     * {@code @AfterEach} to prevent bucket state from bleeding across test classes
     * that share the same Spring context.
     *
     * <p>Calling this from production code is a bug — it does not appear in any
     * controller, service, or filter chain.
     */
    public void resetForTests() {
        buckets.clear();
    }

    /** Immutable snapshot of a bucket: tokens remaining and window start. */
    private record Bucket(int tokensRemaining, Instant windowStart) {
        static Bucket fresh(RateLimitRule rule) {
            return new Bucket(rule.capacity(), Instant.now());
        }

        static Bucket consumedFresh(RateLimitRule rule, Instant now) {
            return new Bucket(rule.capacity() - 1, now);
        }

        boolean isExpired(RateLimitRule rule, Instant now) {
            return now.isAfter(windowStart.plus(rule.window()));
        }
    }
}
