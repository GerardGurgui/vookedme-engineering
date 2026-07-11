package com.vookedme.botmanager.webhook.security;

import com.vookedme.botmanager.config.observability.ObservabilityHelper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Verifies the HMAC-SHA256 signature on incoming webhook requests.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Every request <em>with a body</em> to {@code /api/webhook/**}
 *       (POST/PUT/PATCH/DELETE) must include the header
 *       {@code X-Hub-Signature-256: sha256=<64-hex>} where
 *       {@code hex = HMAC-SHA256(WEBHOOK_HMAC_SECRET, raw_body)}.</li>
 *   <li><b>GET requests are exempt.</b> Webhook GETs are read-only
 *       queries (n8n reads services, schedules, employees, availability)
 *       — they carry no user input in the body. They are already protected
 *       by {@link WebhookApiKeyFilter} (X-API-Key shared bearer). Signing
 *       every GET would be operational overhead with no security benefit
 *       — there is no body to manipulate. If a future GET ever accepts
 *       state-affecting query parameters, re-evaluate this exemption.</li>
 *   <li>Comparison is constant-time via {@link MessageDigest#isEqual}.
 *       Never use {@link String#equals(Object)} for cryptographic secrets
 *       (timing-attack mitigation).</li>
 *   <li>Failure response: 401 with a generic payload
 *       {@code {"success":false,"message":"Unauthorized","timestamp":"..."}}
 *       — failure causes are not differentiated (missing header vs invalid
 *       format vs wrong signature) to prevent attackers from fingerprinting
 *       the validator.</li>
 *   <li>Unexpected exceptions in the filter are reported via
 *       {@link ObservabilityHelper#reportBackgroundFailure} before
 *       re-throwing — filter exceptions do not reach
 *       {@code @RestControllerAdvice} and would otherwise be invisible
 *       to the observability layer.</li>
 * </ul>
 *
 * <h2>Defence in depth</h2>
 * <p>This filter composes with {@link WebhookApiKeyFilter} (X-API-Key).
 * Both checks must pass:
 * <ul>
 *   <li>X-API-Key: shared bearer token — identifies "an authorised caller".
 *       If this fails, the attacker cannot reach this filter.</li>
 *   <li>HMAC: cryptographic proof that the body was not tampered with and
 *       that the sender holds the secret. If this fails, the attacker can
 *       pass the API-Key check but cannot forge a valid signature.</li>
 * </ul>
 * {@code WEBHOOK_API_KEY} and {@code WEBHOOK_HMAC_SECRET} are intentionally
 * separate secrets — independent leak surfaces, independent rotation.
 *
 * <h2>Filter order in SecurityConfig</h2>
 * <pre>
 * rateLimit → webhookApiKey → webhookSignature → jwt → controller
 *              ^cheap reject  ^crypto check
 * </pre>
 *
 * <h2>Body caching</h2>
 * <p>The servlet input stream is one-shot by contract. To allow the HMAC to
 * be computed over the raw body <em>and</em> the controller to parse it as
 * {@code @RequestBody} downstream, the request is wrapped in a
 * {@link CachedBodyHttpServletRequest} that reads the body into a byte array
 * once and replays the stream on demand.
 *
 * <h2>Compatibility with WhatsApp Cloud API (Meta)</h2>
 * <p>This filter is compatible with Meta WhatsApp Business API webhooks
 * without code changes. When migrating from Evolution API + n8n to direct
 * Meta webhooks, only the value of {@code WEBHOOK_HMAC_SECRET} changes
 * (the "App Secret" from the Meta dashboard). The header name
 * {@code X-Hub-Signature-256} and the algorithm
 * {@code HMAC-SHA256(secret, raw_body)} are identical in Meta, GitHub,
 * Evolution API, and the standard signed-webhook convention.
 */
@Component
@Slf4j
public class WebhookSignatureFilter extends OncePerRequestFilter {

    private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String WEBHOOK_PATH_PREFIX = "/api/webhook";
    /** Expected hex length after {@code "sha256="}: 32 bytes → 64 hex characters. */
    private static final int EXPECTED_HEX_LENGTH = 64;

    @Value("${webhook.hmac-secret}")
    private String hmacSecret;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // Exceptions thrown inside a Spring filter do NOT pass through
        // @RestControllerAdvice — the filter chain handles them and the
        // resulting 5xx reaches the client without being picked up by the
        // observability layer. Capture unexpected exceptions and report them
        // before re-throwing.
        try {
            String path = request.getRequestURI();

            // Only apply to webhook routes — let everything else pass through.
            if (!path.startsWith(WEBHOOK_PATH_PREFIX)) {
                filterChain.doFilter(request, response);
                return;
            }

            // GET requests are exempt from HMAC validation — read-only queries
            // that n8n uses to fetch services/schedules/employees/availability.
            // They are already protected by X-API-Key (WebhookApiKeyFilter,
            // defence in depth). Signing each GET would add operational overhead
            // in n8n with no security gain — there is no body to manipulate.
            // If a future GET ever accepts state-affecting input via query params,
            // re-evaluate this bypass.
            if ("GET".equals(request.getMethod())) {
                log.debug("[webhook-signature] skip HMAC for GET {} (X-API-Key gate is sufficient)", path);
                filterChain.doFilter(request, response);
                return;
            }

            // Wrap before reading body — downstream must be able to re-read
            // it as @RequestBody.
            CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
            byte[] body = cached.getCachedBody();

            String header = cached.getHeader(SIGNATURE_HEADER);
            if (!isValidSignature(header, body)) {
                // Structured log without PII or the received/expected header values —
                // a log scrape could assist an attacker trying to fingerprint
                // the validator.
                log.warn("[webhook-signature] rejected {} {} reason=invalid-signature",
                        request.getMethod(), path);
                sendUnauthorized(response);
                return;
            }

            // Signature valid — continue with the WRAPPED request so the
            // controller can read the body.
            filterChain.doFilter(cached, response);

        } catch (Exception e) {
            ObservabilityHelper.reportBackgroundFailure(
                    "webhook-signature-filter", e);
            throw e;
        }
    }

    /**
     * Validates the HMAC header against the request body.
     *
     * <p><b>Constant-time comparison</b> — uses {@link MessageDigest#isEqual}
     * to prevent timing attacks that could infer the secret byte-by-byte.
     *
     * @return {@code true} only if the header is well-formed, the hex string
     *         is exactly 64 characters, and it matches the computed HMAC
     *         byte-for-byte.
     */
    private boolean isValidSignature(String header, byte[] body) {
        if (header == null || header.isBlank()) {
            return false;
        }
        if (!header.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        String providedHex = header.substring(SIGNATURE_PREFIX.length());
        if (providedHex.length() != EXPECTED_HEX_LENGTH) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    hmacSecret.getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM));
            byte[] computed = mac.doFinal(body);
            String computedHex = toHex(computed);

            // Constant-time compare over the UTF-8 bytes of the hex strings
            // (lengths already verified equal above). MessageDigest.isEqual
            // short-circuits only on length — the byte-level loop is always
            // constant-time.
            return MessageDigest.isEqual(
                    computedHex.getBytes(StandardCharsets.UTF_8),
                    providedHex.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is guaranteed by the JDK spec — this branch should
            // be unreachable. If it occurs it is a serious configuration bug;
            // prefer a 401 over a 5xx here, but let the outer catch report
            // it to the observability layer.
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    /** Lowercase hex encoding without external libraries. */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Sends a generic 401 response — identical payload for all failure paths
     * (missing header, malformed header, wrong signature). No information leak.
     */
    private void sendUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write(String.format(
                "{\"success\":false,\"message\":\"Unauthorized\",\"timestamp\":\"%s\"}",
                java.time.Instant.now().toString()
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CachedBodyHttpServletRequest — caches the request body in memory so that:
    // (a) the filter can compute HMAC over the raw bytes, and (b) the controller
    // can parse it as @RequestBody downstream.
    //
    // The servlet input stream is one-shot by contract — without this wrapper,
    // computing the HMAC in the filter would consume the stream and the
    // controller would receive an empty body.
    // ─────────────────────────────────────────────────────────────────────────
    static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] cachedBody;

        CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            // readAllBytes() returns an empty array for requests with no body —
            // correct behaviour for computing HMAC over an empty body.
            this.cachedBody = request.getInputStream().readAllBytes();
        }

        byte[] getCachedBody() {
            return cachedBody;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyServletInputStream(cachedBody);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(
                    getInputStream(), StandardCharsets.UTF_8));
        }
    }

    /** Replay-safe {@link ServletInputStream} backed by an in-memory byte array. */
    static class CachedBodyServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream buffer;

        CachedBodyServletInputStream(byte[] body) {
            this.buffer = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return buffer.read();
        }

        @Override
        public boolean isFinished() {
            return buffer.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // No-op — only used in async flows, which are not applicable here.
            throw new UnsupportedOperationException(
                    "Async I/O not supported on cached body");
        }
    }
}
