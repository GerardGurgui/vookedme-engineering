package com.vookedme.botmanager.webhook.security;

import com.vookedme.botmanager.config.observability.ObservabilityHelper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Validates the {@code X-API-Key} shared bearer token on all requests to
 * {@code /api/webhook/**}.
 *
 * <p>This is the first line of defence on the webhook path — a cheap
 * identity check that rejects any caller that does not hold the shared
 * secret, before the more expensive HMAC-SHA256 validation in
 * {@link WebhookSignatureFilter} is reached.
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li><b>Generic 401 on all failure paths.</b> Missing header, blank
 *       value, and invalid key all return the same payload
 *       {@code {"success":false,"message":"Unauthorized","timestamp":"..."}}
 *       with no differentiation. Failure causes are logged internally
 *       (kebab-case {@code reason=} tag, no PII) but never surfaced to
 *       the caller — prevents attackers from fingerprinting the validator.</li>
 *   <li><b>Constant-time comparison.</b> The API key is compared via
 *       {@link MessageDigest#isEqual} rather than {@link String#equals},
 *       for the same timing-attack mitigation rationale as
 *       {@link WebhookSignatureFilter}. Consistent treatment across both
 *       filters reduces the attack surface.</li>
 *   <li><b>Exception handling.</b> Exceptions thrown inside a Spring filter
 *       do not reach {@code @RestControllerAdvice}. Unexpected exceptions
 *       are reported via {@link ObservabilityHelper#reportBackgroundFailure}
 *       before re-throwing, so they are visible to the observability layer.
 *       The 401 rejection path does not throw — it sets the response status
 *       and returns, so that path never reaches the catch block.</li>
 * </ul>
 */
@Component
@Slf4j
public class WebhookApiKeyFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String WEBHOOK_PATH_PREFIX = "/api/webhook";

    @Value("${webhook.api-key}")
    private String validApiKey;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        try {
            String path = request.getRequestURI();

            // Only validate webhook routes.
            if (!path.startsWith(WEBHOOK_PATH_PREFIX)) {
                filterChain.doFilter(request, response);
                return;
            }

            String apiKey = request.getHeader(API_KEY_HEADER);

            if (apiKey == null || apiKey.isBlank()) {
                log.warn("[webhook-api-key] rejected {} {} reason=missing-header",
                        request.getMethod(), path);
                sendUnauthorized(response);
                return;
            }

            if (!constantTimeEquals(validApiKey, apiKey)) {
                log.warn("[webhook-api-key] rejected {} {} reason=invalid-key",
                        request.getMethod(), path);
                sendUnauthorized(response);
                return;
            }

            // API key valid — continue down the filter chain.
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            ObservabilityHelper.reportBackgroundFailure(
                    "webhook-api-key-filter", e);
            // Re-throw: Spring must continue managing the HTTP response.
            // The observability report is for visibility, not recovery.
            throw e;
        }
    }

    /**
     * Sends a generic 401 response — identical payload for all failure paths.
     * No information leak.
     */
    private void sendUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write(String.format(
                "{\"success\":false,\"message\":\"Unauthorized\",\"timestamp\":\"%s\"}",
                java.time.Instant.now().toString()
        ));
    }

    /**
     * Constant-time string comparison via {@link MessageDigest#isEqual} over
     * UTF-8 bytes. Prevents timing attacks that could infer the key byte-by-byte.
     *
     * <p>If the two strings differ in length, {@code MessageDigest.isEqual}
     * short-circuits in O(1) — an acceptable micro-leak since the length
     * of the API key is an internal constant, not the secret itself.
     *
     * <p>Consistent with the same approach in {@link WebhookSignatureFilter}.
     */
    private static boolean constantTimeEquals(String expected, String provided) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }
}
