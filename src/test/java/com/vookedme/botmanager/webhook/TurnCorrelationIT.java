package com.vookedme.botmanager.webhook;

import com.vookedme.botmanager.appointment.entity.AppointmentAuditLog;
import com.vookedme.botmanager.appointment.repository.AppointmentAuditLogRepository;
import com.vookedme.botmanager.common.event.AppointmentEvent;
import com.vookedme.botmanager.customer.entity.Customer;
import com.vookedme.botmanager.employee.entity.EmployeeSchedule;
import com.vookedme.botmanager.employee.repository.EmployeeScheduleRepository;
import com.vookedme.botmanager.offering.entity.Offering;
import com.vookedme.botmanager.schedule.entity.Schedule;
import com.vookedme.botmanager.webhook.controller.WebhookController;
import com.vookedme.botmanager.webhook.dto.WebhookAppointmentRequest;
import com.vookedme.botmanager.webhook.security.WebhookTestSigner;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the turn correlation substrate (ADR-012).
 *
 * <p>Specifies the forensic contract for {@code turn_id}:
 * <ul>
 *   <li><b>Valid UUID header</b> → {@code audit_log.turn_id} equals the UUID from the
 *       {@code X-Turn-Id} header (real n8n turn).</li>
 *   <li><b>No header</b> / <b>non-UUID header</b> → {@code turn_id} is NULL (correct
 *       degraded behaviour; the synthetic fallback identifier lives in logs only).
 *       Guarantees that turn correlation provides value independently of whether the
 *       caller sends the header.</li>
 *   <li><b>Same header across multiple mutations</b> → same turn_id in all audit rows
 *       (multi-call turn grouping).</li>
 *   <li><b>Re-anchor read</b> → the re-anchor log entry is correlated by {@code turnId}
 *       (MDC) and contains no customer phone number.</li>
 * </ul>
 *
 * <p>Forensic invariant (ADR-012): {@code turn_id} non-null ⟺ real n8n conversational
 * turn; the backend never fabricates forensic turn identifiers.
 *
 * <p>Depends on {@code BaseIntegrationTest} (Testcontainers PostgreSQL, fixture
 * repositories) and {@code WebhookTestSigner} (HMAC signing helper) — published in
 * subsequent source batches.
 */
@DisplayName("Turn correlation — forensic turn_id: persisted from header, NULL on fallback; re-anchor log contains no PII")
class TurnCorrelationIT extends BaseIntegrationTest {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String TURN_ID_HEADER = "X-Turn-Id";

    @Value("${webhook.api-key}")
    private String apiKey;

    @Value("${webhook.hmac-secret}")
    private String hmacSecret;

    @Autowired
    private EmployeeScheduleRepository employeeScheduleRepository;

    @Autowired
    private AppointmentAuditLogRepository auditRepository;

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

        employeeScheduleRepository.save(EmployeeSchedule.builder()
                .user(employeeUser)
                .business(testBusiness)
                .dayOfWeek(testDate.getDayOfWeek().getValue())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .active(true)
                .build());
    }

    private String createBody(LocalDateTime when, String phone) throws Exception {
        return objectMapper.writeValueAsString(WebhookAppointmentRequest.builder()
                .customerPhone(phone)
                .customerName("Turn Customer")
                .datetime(when)
                .offeringId(testOffering.getId())
                .notes("From bot")
                .webhookEventId("wamid.turn." + System.nanoTime())
                .build());
    }

    /** Signs and posts a create-from-bot webhook request, optionally including an X-Turn-Id header. Returns the appointment id. */
    private long postCreate(LocalDateTime when, String phone, String turnIdHeaderOrNull) throws Exception {
        var req = post("/api/webhook/appointments/{instance}", testBusiness.getInstance())
                .header(API_KEY_HEADER, apiKey)
                .with(WebhookTestSigner.hmacSigned(hmacSecret))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(when, phone));
        if (turnIdHeaderOrNull != null) {
            req = req.header(TURN_ID_HEADER, turnIdHeaderOrNull);
        }
        String response = mockMvc.perform(req)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.data.id")).longValue();
    }

    private AppointmentAuditLog createdRow(long appointmentId) {
        em.flush();
        em.clear();
        return auditRepository.findAll().stream()
                .filter(r -> r.getAppointmentId() != null
                        && r.getAppointmentId() == appointmentId
                        && r.getEventType() == AppointmentEvent.Type.CREATED)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no CREATED audit row for appointment " + appointmentId));
    }

    @Test
    @DisplayName("Valid X-Turn-Id header → audit_log.turn_id equals the header UUID (real n8n turn)")
    void headerValidPersistsTurnId() throws Exception {
        UUID turnId = UUID.randomUUID();
        long apptId = postCreate(testDate.atTime(10, 0), "+34699888777", turnId.toString());

        assertThat(createdRow(apptId).getTurnId())
                .as("forensic turn_id must equal the X-Turn-Id header value")
                .isEqualTo(turnId);
    }

    @Test
    @DisplayName("No X-Turn-Id header → audit_log.turn_id is NULL (synthetic fallback in logs only)")
    void noHeaderLeavesTurnIdNull() throws Exception {
        long apptId = postCreate(testDate.atTime(11, 0), "+34699888778", null);

        assertThat(createdRow(apptId).getTurnId())
                .as("no header → turn_id NULL (correct degraded behaviour)")
                .isNull();
    }

    @Test
    @DisplayName("Non-UUID X-Turn-Id header → audit_log.turn_id is NULL (backend never fabricates forensic turns)")
    void invalidHeaderLeavesTurnIdNull() throws Exception {
        long apptId = postCreate(testDate.atTime(12, 0), "+34699888779", "not-a-valid-uuid");

        assertThat(createdRow(apptId).getTurnId())
                .as("malformed header → synthetic fallback → turn_id NULL")
                .isNull();
    }

    @Test
    @DisplayName("Same X-Turn-Id across two mutations → same key in both audit rows (multi-call turn grouping)")
    void sameHeaderAcrossMutationsSharesTurnId() throws Exception {
        UUID turnId = UUID.randomUUID();
        long apptA = postCreate(testDate.atTime(13, 0), "+34699888780", turnId.toString());
        long apptB = postCreate(testDate.atTime(14, 0), "+34699888781", turnId.toString());

        assertThat(createdRow(apptA).getTurnId()).isEqualTo(turnId);
        assertThat(createdRow(apptB).getTurnId()).isEqualTo(turnId);
    }

    @Test
    @DisplayName("Re-anchor read: reanchorStatus log entry correlated by turnId (MDC), no customer phone in log")
    void recentRelevantReadLogsTurnIdWithoutPhone() throws Exception {
        UUID turnId = UUID.randomUUID();
        String phone = "+34600123123";

        Logger controllerLogger = (Logger) LoggerFactory.getLogger(WebhookController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        controllerLogger.addAppender(appender);
        try {
            mockMvc.perform(get("/api/webhook/appointments/{instance}/customer/{phone}/recent-relevant",
                            testBusiness.getInstance(), phone)
                            .header(API_KEY_HEADER, apiKey)
                            .header(TURN_ID_HEADER, turnId.toString()))
                    .andExpect(status().isOk());
        } finally {
            controllerLogger.detachAppender(appender);
        }

        List<ILoggingEvent> reanchorLines = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("re-anchor read=recent-relevant"))
                .toList();

        assertThat(reanchorLines).as("re-anchor log line was emitted").hasSize(1);
        ILoggingEvent event = reanchorLines.get(0);
        assertThat(event.getFormattedMessage())
                .as("status field present; no customer phone number in log")
                .contains("reanchorStatus=empty-legitimate")
                .doesNotContain(phone);
        assertThat(event.getMDCPropertyMap().get("turnId"))
                .as("turnId from header is propagated to the log MDC")
                .isEqualTo(turnId.toString());
    }
}
