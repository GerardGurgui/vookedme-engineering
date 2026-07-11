package com.vookedme.botmanager.appointment;

import com.vookedme.botmanager.appointment.dto.AppointmentResponse;
import com.vookedme.botmanager.appointment.repository.AppointmentRepository;
import com.vookedme.botmanager.appointment.service.AppointmentService;
import com.vookedme.botmanager.business.entity.Business;
import com.vookedme.botmanager.customer.entity.Customer;
import com.vookedme.botmanager.employee.entity.EmployeeSchedule;
import com.vookedme.botmanager.employee.repository.EmployeeScheduleRepository;
import com.vookedme.botmanager.offering.entity.Offering;
import com.vookedme.botmanager.schedule.entity.Schedule;
import com.vookedme.botmanager.webhook.dto.WebhookAppointmentRequest;
import com.vookedme.botmanager.webhook.security.WebhookTestSigner;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Webhook idempotency integration test — partial UNIQUE INDEX as authoritative source of
 * truth for duplicate webhook event detection.
 *
 * <p>The idempotency model: {@code appointment.webhook_event_id} carries a VARCHAR(64)
 * identifier from the webhook caller (e.g., a WhatsApp message ID). A partial UNIQUE
 * INDEX on {@code (business_id, webhook_event_id) WHERE webhook_event_id IS NOT NULL}
 * ensures that duplicate event IDs within a tenant produce exactly one appointment row,
 * regardless of whether the pre-check or the UNIQUE constraint catches the duplicate.
 *
 * <p>The critical design point: <b>the PostgreSQL UNIQUE INDEX is the authoritative
 * source of truth</b>. The pre-check is a fast-path optimisation, not the correctness
 * mechanism. IT-2 specifically validates that the UNIQUE constraint catches the race
 * even when both concurrent callers pass the pre-check simultaneously.
 *
 * <p>Test coverage matrix:
 * <ul>
 *   <li><b>IT-1</b> Sequential duplicate retry — same body + same eventId posted twice →
 *       both 200; same appointment.id; DB count == 1</li>
 *   <li><b>IT-2</b> Concurrent webhook race — 2 threads call simultaneously with the same
 *       eventId → both succeed idempotently; same appointment.id; DB count == 1</li>
 *   <li><b>IT-3</b> Cross-tenant same eventId — business A + B both post with the same
 *       eventId → both succeed; 2 rows (UNIQUE INDEX is scoped per business_id)</li>
 *   <li><b>IT-4</b> NULL eventId legacy path — POST without webhookEventId succeeds; row
 *       persists with NULL; PARTIAL INDEX exempts NULL values</li>
 *   <li><b>IT-5</b> Different eventId same data — distinct eventIds with the same
 *       datetime/phone → the second hits the existing customer-overlap guard (not the
 *       webhook dedup path)</li>
 *   <li><b>IT-6</b> Service-level pre-check — direct service call; second invocation with
 *       the same eventId returns the existing appointment via the fast-path pre-check</li>
 * </ul>
 *
 * <p>Depends on {@code BaseIntegrationTest} (Testcontainers PostgreSQL, fixture
 * repositories) and {@code WebhookTestSigner} (HMAC signing helper) — both published in
 * subsequent source batches.
 */
@DisplayName("Webhook Idempotency — partial UNIQUE INDEX as authoritative source of truth")
class BotWebhookIdempotencyIT extends BaseIntegrationTest {

    private static final String API_KEY_HEADER = "X-API-Key";

    @org.springframework.beans.factory.annotation.Value("${webhook.api-key}")
    private String apiKey;

    @org.springframework.beans.factory.annotation.Value("${webhook.hmac-secret}")
    private String hmacSecret;

    @Autowired
    private EmployeeScheduleRepository employeeScheduleRepository;

    @Autowired
    private AppointmentService appointmentService;

    @PersistenceContext
    private EntityManager em;

    private Offering testOffering;
    private LocalDate testDate;

    @BeforeEach
    void seedFixtures() {
        testDate = LocalDate.now().plusWeeks(2);

        scheduleRepository.save(Schedule.builder()
                .business(testBusiness)
                .dayOfWeek(testDate.getDayOfWeek().getValue())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .active(true)
                .capacity(5)
                .build());

        testOffering = offeringRepository.save(Offering.builder()
                .business(testBusiness)
                .name("Haircut")
                .durationMinutes(30)
                .price(BigDecimal.valueOf(25))
                .active(true)
                .build());

        customerRepository.save(Customer.builder()
                .business(testBusiness)
                .phone("+34611222333")
                .name("Test Customer")
                .build());

        employeeScheduleRepository.save(EmployeeSchedule.builder()
                .user(employeeUser)
                .business(testBusiness)
                .dayOfWeek(testDate.getDayOfWeek().getValue())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .active(true)
                .build());
    }

    private WebhookAppointmentRequest validRequest(LocalDateTime when, String webhookEventId) {
        return WebhookAppointmentRequest.builder()
                .customerPhone("+34699888777")
                .customerName("New Customer")
                .datetime(when)
                .offeringId(testOffering.getId())
                .notes("From bot")
                .webhookEventId(webhookEventId)
                .build();
    }

    private static final String SAMPLE_WAMID = "wamid.HBgLMzQ2OTk4ODg3NzcVAgARGBJBQzdF";

    // ────────────────────────────────────────────────────────────────────
    // IT-1..IT-5: HTTP-level idempotency
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("HTTP-level idempotency (controller + service E2E)")
    class HttpLevel {

        @Test
        @DisplayName("IT-1 — Sequential duplicate retry: 2 POSTs same eventId → same appointment.id, 1 row in DB")
        void it1_sequentialDuplicateReturnsSameAppointment() throws Exception {
            LocalDateTime when = testDate.atTime(10, 0);
            String body = objectMapper.writeValueAsString(validRequest(when, SAMPLE_WAMID));

            // First POST: creates the appointment.
            String response1 = mockMvc.perform(post("/api/webhook/appointments/{instance}", testBusiness.getInstance())
                            .header(API_KEY_HEADER, apiKey)
                            .with(WebhookTestSigner.hmacSigned(hmacSecret))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").isNumber())
                    .andReturn().getResponse().getContentAsString();
            Number id1 = com.jayway.jsonpath.JsonPath.read(response1, "$.data.id");

            // Second POST: same body + same eventId — simulates a webhook caller retry.
            String response2 = mockMvc.perform(post("/api/webhook/appointments/{instance}", testBusiness.getInstance())
                            .header(API_KEY_HEADER, apiKey)
                            .with(WebhookTestSigner.hmacSigned(hmacSecret))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").isNumber())
                    .andReturn().getResponse().getContentAsString();
            Number id2 = com.jayway.jsonpath.JsonPath.read(response2, "$.data.id");

            assertThat(id2.longValue())
                    .as("Idempotent retry must return the same appointment.id")
                    .isEqualTo(id1.longValue());

            em.flush();
            em.clear();
            long count = appointmentRepository.findByBusinessId(testBusiness.getId()).stream()
                    .filter(a -> SAMPLE_WAMID.equals(a.getWebhookEventId()))
                    .count();
            assertThat(count)
                    .as("Exactly 1 appointment with webhookEventId=%s expected", SAMPLE_WAMID)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("IT-3 — Cross-tenant same eventId: business A + B both succeed with the same wamid (UNIQUE INDEX is scoped per business_id)")
        void it3_crossTenantSameEventIdAllowed() throws Exception {
            Business otherBusiness = businessRepository.save(Business.builder()
                    .instance("other-tenant-instance")
                    .name("Other Tenant")
                    .active(true)
                    .minAdvanceMinutes(0)
                    .build());
            scheduleRepository.save(Schedule.builder()
                    .business(otherBusiness)
                    .dayOfWeek(testDate.getDayOfWeek().getValue())
                    .startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(18, 0))
                    .active(true)
                    .capacity(5)
                    .build());
            Offering otherOffering = offeringRepository.save(Offering.builder()
                    .business(otherBusiness)
                    .name("Service B")
                    .durationMinutes(30)
                    .price(BigDecimal.valueOf(20))
                    .active(true)
                    .build());
            com.vookedme.botmanager.auth.entity.User otherEmployee = userRepository.save(
                    com.vookedme.botmanager.auth.entity.User.builder()
                            .email("other-emp@test.com")
                            .passwordHash(passwordEncoder.encode(TEST_PASSWORD))
                            .name("Other Emp")
                            .role(employeeRole)
                            .business(otherBusiness)
                            .isActive(true)
                            .build());
            employeeScheduleRepository.save(EmployeeSchedule.builder()
                    .user(otherEmployee)
                    .business(otherBusiness)
                    .dayOfWeek(testDate.getDayOfWeek().getValue())
                    .startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(18, 0))
                    .active(true)
                    .build());

            LocalDateTime when = testDate.atTime(11, 0);

            // Business A — posts with the shared wamid.
            WebhookAppointmentRequest reqA = validRequest(when, SAMPLE_WAMID);
            reqA.setOfferingId(testOffering.getId());
            mockMvc.perform(post("/api/webhook/appointments/{instance}", testBusiness.getInstance())
                            .header(API_KEY_HEADER, apiKey)
                            .with(WebhookTestSigner.hmacSigned(hmacSecret))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reqA)))
                    .andExpect(status().isOk());

            // Business B — SAME wamid, different tenant — must succeed (UNIQUE INDEX scoped to businessId).
            WebhookAppointmentRequest reqB = WebhookAppointmentRequest.builder()
                    .customerPhone("+34699111222")
                    .customerName("Customer B")
                    .datetime(when)
                    .offeringId(otherOffering.getId())
                    .notes("From bot B")
                    .webhookEventId(SAMPLE_WAMID)
                    .build();
            mockMvc.perform(post("/api/webhook/appointments/{instance}", otherBusiness.getInstance())
                            .header(API_KEY_HEADER, apiKey)
                            .with(WebhookTestSigner.hmacSigned(hmacSecret))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reqB)))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();
            long countA = appointmentRepository.findByBusinessId(testBusiness.getId()).stream()
                    .filter(a -> SAMPLE_WAMID.equals(a.getWebhookEventId()))
                    .count();
            long countB = appointmentRepository.findByBusinessId(otherBusiness.getId()).stream()
                    .filter(a -> SAMPLE_WAMID.equals(a.getWebhookEventId()))
                    .count();
            assertThat(countA).as("Business A has 1 appointment with this wamid").isEqualTo(1);
            assertThat(countB).as("Business B has 1 appointment with the same wamid (cross-tenant scope)").isEqualTo(1);
        }

        @Test
        @DisplayName("IT-4 — NULL eventId legacy path: POST without webhookEventId succeeds; PARTIAL INDEX exempts NULL rows")
        void it4_nullEventIdLegacyPathStillWorks() throws Exception {
            LocalDateTime when = testDate.atTime(12, 0);

            WebhookAppointmentRequest req = WebhookAppointmentRequest.builder()
                    .customerPhone("+34699888888")
                    .customerName("Legacy Customer")
                    .datetime(when)
                    .offeringId(testOffering.getId())
                    .notes("Legacy payload without webhookEventId")
                    // webhookEventId intentionally absent (NULL)
                    .build();

            mockMvc.perform(post("/api/webhook/appointments/{instance}", testBusiness.getInstance())
                            .header(API_KEY_HEADER, apiKey)
                            .with(WebhookTestSigner.hmacSigned(hmacSecret))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").isNumber());

            em.flush();
            em.clear();
            long nullCount = appointmentRepository.findByBusinessId(testBusiness.getId()).stream()
                    .filter(a -> a.getWebhookEventId() == null)
                    .count();
            assertThat(nullCount)
                    .as("Legacy path persists row with NULL webhookEventId")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("IT-5 — Different eventIds, same datetime+phone: second hit triggers existing customer-overlap guard (not webhook dedup)")
        void it5_differentEventIdsSameDataHitsExistingOverlapGuard() throws Exception {
            LocalDateTime when = testDate.atTime(13, 0);

            String eventId1 = "wamid.firstEvent";
            String eventId2 = "wamid.secondEvent";

            // First POST — creates appointment with eventId1.
            mockMvc.perform(post("/api/webhook/appointments/{instance}", testBusiness.getInstance())
                            .header(API_KEY_HEADER, apiKey)
                            .with(WebhookTestSigner.hmacSigned(hmacSecret))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest(when, eventId1))))
                    .andExpect(status().isOk());

            // Second POST — distinct eventId but same datetime + customer phone.
            // Webhook dedup does not trigger (different eventId), but the customer-overlap
            // guard catches the duplicate slot and returns 400.
            mockMvc.perform(post("/api/webhook/appointments/{instance}", testBusiness.getInstance())
                            .header(API_KEY_HEADER, apiKey)
                            .with(WebhookTestSigner.hmacSigned(hmacSecret))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest(when, eventId2))))
                    .andExpect(status().isBadRequest());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // IT-2: concurrent race — UNIQUE constraint as authoritative arbitrator
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("UNIQUE constraint authoritative under race conditions")
    class RaceCondition {

        /**
         * IT-2 directly tests the UNIQUE constraint as the source of truth. Two concurrent
         * service calls with the same {@code (businessId, webhookEventId)} are forced via
         * threads. Both pass the pre-check simultaneously (race window), but only one INSERT
         * succeeds. The other catches the {@code DataIntegrityViolationException} and returns
         * the winner's existing row — both callers receive a successful, idempotent response.
         *
         * <p>Uses direct service invocation rather than MockMvc HTTP because MockMvc
         * serialises requests by default. Forcing real thread parallelism requires
         * {@code CountDownLatch} synchronisation around direct service calls.
         */
        @Test
        @DisplayName("IT-2 — Concurrent webhook race: 2 threads call simultaneously → both succeed idempotently, 1 row in DB")
        void it2_concurrentRaceProducesSingleRow() throws Exception {
            String raceEventId = "wamid.raceTest." + System.nanoTime();
            LocalDateTime when = testDate.atTime(14, 0);

            int threads = 2;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(threads);
            AtomicReference<Long> id1 = new AtomicReference<>();
            AtomicReference<Long> id2 = new AtomicReference<>();
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            Runnable webhookCall = () -> {
                try {
                    startGate.await();
                    AppointmentResponse resp = appointmentService.createFromBot(
                            testBusiness.getInstance(),
                            "+34699777666",
                            "Race Customer",
                            when,
                            testOffering.getId(),
                            "Race test",
                            null,
                            raceEventId);
                    if (!id1.compareAndSet(null, resp.getId())) {
                        id2.set(resp.getId());
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                }
                doneGate.countDown();
            };

            executor.submit(webhookCall);
            executor.submit(webhookCall);
            startGate.countDown();
            assertThat(doneGate.await(10, TimeUnit.SECONDS)).as("Both calls completed within timeout").isTrue();
            executor.shutdown();

            assertThat(successCount.get()).as("Both concurrent calls succeed (idempotent)").isEqualTo(2);
            assertThat(errorCount.get()).as("No errors leaked from race handling").isEqualTo(0);

            assertThat(id1.get()).as("First success captured an id").isNotNull();
            assertThat(id2.get())
                    .as("Second concurrent call returns the same id (UNIQUE constraint won the race)")
                    .isEqualTo(id1.get());

            long count = appointmentRepository.findByBusinessId(testBusiness.getId()).stream()
                    .filter(a -> raceEventId.equals(a.getWebhookEventId()))
                    .count();
            assertThat(count)
                    .as("UNIQUE constraint authoritative: race produced exactly 1 row")
                    .isEqualTo(1);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // IT-6: service-level pre-check (fast path)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Service-level idempotency (bypassing HTTP layer)")
    class ServiceLevel {

        @Test
        @DisplayName("IT-6 — Direct service call: second invocation with same eventId returns existing via pre-check (fast path)")
        void it6_serviceDirectPreCheckReturnsExisting() {
            String eventId = "wamid.preCheck." + System.nanoTime();
            LocalDateTime when = testDate.atTime(15, 0);

            AppointmentResponse first = appointmentService.createFromBot(
                    testBusiness.getInstance(),
                    "+34699111000",
                    "Pre-check Customer",
                    when,
                    testOffering.getId(),
                    "Pre-check test",
                    null,
                    eventId);
            assertThat(first.getId()).isNotNull();

            // Flush + clear to ensure the second call sees the persisted state.
            em.flush();
            em.clear();

            // Second call with the same eventId → pre-check hits → returns existing appointment.
            AppointmentResponse second = appointmentService.createFromBot(
                    testBusiness.getInstance(),
                    "+34699111000",
                    "Pre-check Customer",
                    when,
                    testOffering.getId(),
                    "Pre-check test",
                    null,
                    eventId);
            assertThat(second.getId())
                    .as("Pre-check optimisation returns the same id without re-creating")
                    .isEqualTo(first.getId());
        }
    }
}
