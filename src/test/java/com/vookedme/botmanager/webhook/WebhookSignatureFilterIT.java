package com.vookedme.botmanager.webhook;

import com.vookedme.botmanager.schedule.entity.Schedule;
import com.vookedme.botmanager.webhook.security.WebhookTestSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link com.vookedme.botmanager.webhook.security.WebhookSignatureFilter}.
 *
 * <p>Exercises the full filter chain against a real Spring context backed
 * by PostgreSQL (Testcontainers, via {@code BaseIntegrationTest}).
 *
 * <p>Three contracts are verified:
 * <ol>
 *   <li><b>GET with only X-API-Key (no HMAC) → 200</b> (GET bypass).</li>
 *   <li><b>POST without HMAC → 401</b> (write contract still enforced).</li>
 *   <li><b>POST with correct HMAC → 200</b> (write happy path).</li>
 * </ol>
 *
 * <p>The GET exemption reflects the deliberate design decision documented in
 * {@link com.vookedme.botmanager.webhook.security.WebhookSignatureFilter}:
 * n8n uses GET requests for read-only queries (offerings, schedules,
 * employees, availability) that carry no user input in the body. These
 * are already protected by {@code WebhookApiKeyFilter} (X-API-Key). Requiring
 * HMAC on GETs would add operational complexity in n8n with no security
 * benefit — there is no body to protect against tampering.
 *
 * <p>The POST exercise uses {@code /availability/{instance}/check} — the
 * lightest write-side endpoint (no appointment setup required, just a
 * body with {@code datetime} + {@code duration}). A minimal Schedule is
 * persisted in {@link #setUp()} so the GET endpoint returns real slots.
 *
 * <p><em>Note: this class extends {@code BaseIntegrationTest}, which
 * provides Testcontainers PostgreSQL, MockMvc, the shared test fixtures
 * ({@code testBusiness}, {@code scheduleRepository}), and the full Spring
 * Security context. The base class will be published in a subsequent batch.
 * {@code WebhookTestSigner} (used to attach the correct HMAC header in test 3)
 * is a test utility helper that will also be published separately.</em>
 *
 * <p><b>Requires Docker</b> (Testcontainers PostgreSQL 16-alpine). Without
 * Docker this test fails at bootstrap; the rest of the suite continues.
 */
class WebhookSignatureFilterIT extends BaseIntegrationTest {

    private static final String API_KEY_HEADER = "X-API-Key";

    @Value("${webhook.api-key}")
    private String apiKey;

    @Value("${webhook.hmac-secret}")
    private String hmacSecret;

    private LocalDate testDate;

    @BeforeEach
    void setUp() {
        // Two weeks ahead — same pattern as the other webhook integration tests.
        testDate = LocalDate.now().plusWeeks(2);

        // Minimal schedule so the availability endpoint returns real slots.
        scheduleRepository.save(Schedule.builder()
                .business(testBusiness)
                .dayOfWeek(testDate.getDayOfWeek().getValue())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .active(true)
                .capacity(5)
                .build());
    }

    @Test
    @DisplayName("(IT-1) GET with only X-API-Key (no HMAC) → 200 (GET bypass)")
    void getWithOnlyApiKeyBypassesHmacAndReaches200() throws Exception {
        // Verifies the GET bypass: webhook GETs are exempt from HMAC. n8n
        // calling /offerings, /employees, /schedules, etc. only needs X-API-Key.
        mockMvc.perform(get("/api/webhook/availability/{instance}", testBusiness.getInstance())
                        .header(API_KEY_HEADER, apiKey)
                        // Deliberately no X-Hub-Signature-256.
                        .param("date", testDate.toString())
                        .param("duration", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("(IT-2) POST without HMAC → 401 (write contract still enforced)")
    void postWithoutHmacReturns401() throws Exception {
        // The GET bypass does not extend to POSTs — write requests are always
        // required to carry a valid HMAC signature.
        LocalDateTime when = LocalDateTime.of(testDate, LocalTime.of(10, 0));
        String body = String.format(
                "{\"datetime\":\"%s\",\"duration\":30}",
                when.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        mockMvc.perform(post("/api/webhook/availability/{instance}/check",
                        testBusiness.getInstance())
                        .header(API_KEY_HEADER, apiKey)
                        // No X-Hub-Signature-256.
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthorized"));
    }

    @Test
    @DisplayName("(IT-3) POST with correct HMAC → 200 (write happy path)")
    void postWithCorrectHmacReaches200() throws Exception {
        LocalDateTime when = LocalDateTime.of(testDate, LocalTime.of(10, 0));
        String body = String.format(
                "{\"datetime\":\"%s\",\"duration\":30}",
                when.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        mockMvc.perform(post("/api/webhook/availability/{instance}/check",
                        testBusiness.getInstance())
                        .header(API_KEY_HEADER, apiKey)
                        .with(WebhookTestSigner.hmacSigned(hmacSecret))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
