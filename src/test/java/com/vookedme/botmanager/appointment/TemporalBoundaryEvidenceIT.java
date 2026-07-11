package com.vookedme.botmanager.appointment;

import com.vookedme.botmanager.appointment.entity.Appointment;
import com.vookedme.botmanager.appointment.entity.AppointmentSource;
import com.vookedme.botmanager.appointment.entity.AppointmentStatus;
import com.vookedme.botmanager.appointment.entity.AppointmentAuditLog;
import com.vookedme.botmanager.appointment.repository.AppointmentAuditLogRepository;
import com.vookedme.botmanager.appointment.service.AppointmentService;
import com.vookedme.botmanager.business.entity.BotBookingMode;
import com.vookedme.botmanager.customer.entity.Customer;
import com.vookedme.botmanager.notification.entity.reminders.Reminder;
import com.vookedme.botmanager.notification.entity.reminders.ReminderChannel;
import com.vookedme.botmanager.notification.entity.reminders.ReminderStatus;
import com.vookedme.botmanager.notification.entity.reminders.ReminderType;
import com.vookedme.botmanager.notification.repository.ReminderRepository;
import com.vookedme.botmanager.notification.service.N8nWebhookService;
import com.vookedme.botmanager.notification.service.ReminderScheduler;
import com.vookedme.botmanager.offering.entity.Offering;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adversarial audit evidence for ADR-011 Temporal Boundary. Complements
 * {@link TemporalBoundaryIT} (which covers the core enforcement policy) with
 * scenarios that would otherwise require manual smoke testing in production:
 *
 * <ul>
 *   <li>Exact boundary instant and ±margin — the {@code isPast()} predicate contract</li>
 *   <li>Idempotency of double job execution</li>
 *   <li>Scheduler audit row attribution (SCHEDULER actor, null user_id)</li>
 *   <li>Pending-expiry query with SQL-backdated {@code created_at}</li>
 *   <li>Zombie reminder sweep with empirically verified zero outbound sends</li>
 *   <li>Positive control: late but future reminder is sent exactly once</li>
 *   <li>Panel CR queue cleared by the real status endpoint after boundary expiry</li>
 *   <li>Bot-path temporal guard: cancel and reschedule reject past appointments</li>
 *   <li>Role matrix: OWNER/ADMIN/EMPLOYEE behaviour at the temporal boundary</li>
 * </ul>
 *
 * <p>These tests run against a real PostgreSQL instance (Testcontainers) via
 * {@code BaseIntegrationTest} *(published in a subsequent source batch)*.
 *
 * <p>Unpublished dependencies referenced in this class: {@code AppointmentService},
 * {@code ReminderScheduler}, {@code N8nWebhookService}, {@code AppointmentAuditLogRepository},
 * {@code EmployeeScheduleRepository} — published in a subsequent source batch.
 */
@DisplayName("ADR-011 — Adversarial audit evidence")
class TemporalBoundaryEvidenceIT extends BaseIntegrationTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    @Autowired
    private ReminderRepository reminderRepository;
    @Autowired
    private ReminderScheduler reminderScheduler;
    @Autowired
    private AppointmentService appointmentService;
    @Autowired
    private AppointmentAuditLogRepository appointmentAuditLogRepository;
    @Autowired
    private com.vookedme.botmanager.employee.repository.EmployeeScheduleRepository employeeScheduleRepository;
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Mock of the notification transport chokepoint. All outbound sends (reminders
     * and customer notifications) pass through {@code N8nWebhookService}. Mocking
     * it here allows asserting "zero outbound messages" empirically rather than by
     * inspection — the actual call site is never reached.
     */
    @MockitoBean
    private N8nWebhookService n8nWebhookService;

    private Customer customer;
    private Offering offering;

    @BeforeEach
    void setUpFixtures() {
        offering = offeringRepository.save(Offering.builder()
                .business(testBusiness)
                .name("Masaje relajante") // LOCALE OUTPUT (Spanish): fixture offering name
                .durationMinutes(60)
                .price(BigDecimal.valueOf(50))
                .active(true)
                .build());

        customer = customerRepository.save(Customer.builder()
                .business(testBusiness)
                .phone("+34600777666")
                .name("Test Customer")
                .build());

        // The bot booking service uses the business WhatsApp instance for routing.
        // Both the inbound instance (used in findByInstance) and the outbound instance
        // (used for send operations) must be set.
        testBusiness.setInstance("evidence-instance");
        testBusiness.setWhatsappInstance("evidence-instance");
        testBusiness.setBotBookingMode(BotBookingMode.APPROVAL_REQUIRED);
        testBusiness = businessRepository.save(testBusiness);
    }

    private Appointment appointment(AppointmentStatus status, LocalDateTime datetime) {
        return appointmentRepository.save(Appointment.builder()
                .business(testBusiness)
                .customer(customer)
                .offering(offering)
                .source(AppointmentSource.BOT)
                .status(status)
                .datetime(datetime)
                .durationMinutes(60)
                .build());
    }

    private Appointment botCancellationRequested(LocalDateTime datetime, LocalDateTime requestedAt) {
        Appointment a = appointment(AppointmentStatus.CANCELLATION_REQUESTED, datetime);
        a.setCancellationRequestedAt(requestedAt);
        a.setCancellationRequestedActor("BOT");
        a.setCancellationRequestReason("no puedo asistir"); // LOCALE OUTPUT (Spanish)
        return appointmentRepository.save(a);
    }

    private Reminder pendingReminder(Appointment a, LocalDateTime scheduledAt) {
        return reminderRepository.save(Reminder.builder()
                .appointment(a)
                .business(testBusiness)
                .type(ReminderType.REMINDER_1H)
                .channel(ReminderChannel.WHATSAPP)
                .status(ReminderStatus.PENDING)
                .scheduledAt(scheduledAt)
                .build());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(MADRID);
    }

    private void freshRead() {
        entityManager.flush();
        entityManager.clear();
    }

    // ── Exact boundary — isPast() contract ────────────────────────────────────

    @Nested
    @DisplayName("Exact boundary and ±margin — isPast() contract")
    class ExactBoundary {

        @Test
        @DisplayName("Exact start instant counts as past (any decision at exactly the appointment time is already too late)")
        void exactInstantCountsAsPast() {
            // Deterministic: now() inside isPast() is evaluated after the datetime is
            // fixed, so it is always >= datetime (the monotonic clock only advances).
            Appointment atBoundary = Appointment.builder()
                    .datetime(LocalDateTime.now(MADRID))
                    .build();
            assertThat(atBoundary.isPast()).isTrue();
        }

        @Test
        @DisplayName("Boundary +45s is still in the future (sub-minute margin is operable)")
        void fortyFiveSecondsAheadIsNotPast() {
            Appointment justFuture = Appointment.builder()
                    .datetime(LocalDateTime.now(MADRID).plusSeconds(45))
                    .build();
            assertThat(justFuture.isPast()).isFalse();
        }

        @Test
        @DisplayName("Boundary -45s is already past")
        void fortyFiveSecondsAgoIsPast() {
            Appointment justPast = Appointment.builder()
                    .datetime(LocalDateTime.now(MADRID).minusSeconds(45))
                    .build();
            assertThat(justPast.isPast()).isTrue();
        }
    }

    // ── Jobs — idempotency, audit attribution, pending-expiry selection ────────

    @Nested
    @DisplayName("Jobs — double execution, scheduler audit row, and pending-expiry selection")
    class JobsEvidence {

        @Test
        @DisplayName("Double CR-expiry execution: second pass = 0 rows, stable state (idempotent)")
        void crExpiryDoubleRunIsIdempotent() {
            Appointment pastCr = botCancellationRequested(now().minusHours(2), now().minusHours(1));

            int first = appointmentService.expireStaleCancellationRequests();
            int second = appointmentService.expireStaleCancellationRequests();

            assertThat(first).isGreaterThanOrEqualTo(1);
            assertThat(second).isZero();
            Appointment after = appointmentRepository.findById(pastCr.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        }

        @Test
        @DisplayName("Boundary expiry writes a SCHEDULER audit row with null actor_user_id")
        void boundaryExpiryWritesSchedulerAuditRow() {
            // The scheduler has no User identity; actor_user_id must be NULL in the
            // audit row. This is enforced by the chk_audit_actor_user_id database
            // constraint (verified separately in AppointmentAuditLogConstraintsIT).
            Appointment pastCr = botCancellationRequested(now().minusHours(2), now().minusHours(1));

            appointmentService.expireStaleCancellationRequests();

            List<AppointmentAuditLog> rows = appointmentAuditLogRepository.findAll().stream()
                    .filter(r -> pastCr.getId().equals(r.getAppointmentId()))
                    .toList();
            assertThat(rows).isNotEmpty();
            AppointmentAuditLog expiryRow = rows.get(rows.size() - 1);
            assertThat(expiryRow.getNewStatus()).isEqualTo("CONFIRMED");
            assertThat(expiryRow.getTriggeredBy().name()).isEqualTo("SCHEDULER");
            assertThat(expiryRow.getActorUserId()).isNull();
        }

        @Test
        @DisplayName("Pending-expiry query selects row with SQL-backdated created_at (> bot approval timeout)")
        void pendingExpirySelectionPicksBackdatedRow() {
            Appointment stalePending = appointment(AppointmentStatus.PENDING, now().plusDays(1));
            entityManager.flush();
            // Backdate created_at beyond the bot-approval timeout (default: 2 hours)
            // via native SQL — simulates elapsed time without waiting for real clock
            // advancement.
            entityManager.createNativeQuery(
                            "UPDATE appointments SET created_at = :ts WHERE id = :id")
                    .setParameter("ts", now().minusHours(3))
                    .setParameter("id", stalePending.getId())
                    .executeUpdate();
            freshRead();

            List<Appointment> selected = appointmentRepository
                    .findStaleBotPendingForExpire(now());

            assertThat(selected).extracting(Appointment::getId)
                    .contains(stalePending.getId());
        }
    }

    // ── Reminders — zombie sweep with zero outbound sends + positive control ───

    @Nested
    @DisplayName("Reminders — zombie sweep with empirically verified zero sends, and late-but-future send")
    class ReminderEvidence {

        @Test
        @DisplayName("Two PENDING reminders for past appointments → both CANCELLED, n8nWebhookService never called")
        void zombieSweepCancelsAllWithZeroSends() {
            Appointment pastConfirmed = appointment(AppointmentStatus.CONFIRMED, now().minusHours(3));
            Appointment pastCr = botCancellationRequested(now().minusHours(2), now().minusHours(18));
            Reminder r1 = pendingReminder(pastConfirmed, now().minusHours(4));
            Reminder r2 = pendingReminder(pastCr, now().minusHours(3));

            reminderScheduler.processReminders();

            freshRead();
            assertThat(reminderRepository.findById(r1.getId()).orElseThrow().getStatus())
                    .isEqualTo(ReminderStatus.CANCELLED);
            assertThat(reminderRepository.findById(r2.getId()).orElseThrow().getStatus())
                    .isEqualTo(ReminderStatus.CANCELLED);
            // Empirical verification: the transport chokepoint was never called,
            // not merely inspected.
            verify(n8nWebhookService, never()).sendReminder(any());
        }

        @Test
        @DisplayName("Positive control: overdue reminder for a future appointment → sent exactly once, status SENT")
        void lateButFutureReminderSendsOnce() {
            when(n8nWebhookService.sendReminder(any()))
                    .thenReturn(new N8nWebhookService.SendResult("ext-evidence-1", "msg"));
            Appointment future = appointment(AppointmentStatus.CONFIRMED, now().plusHours(2));
            Reminder due = pendingReminder(future, now().minusMinutes(10));

            reminderScheduler.processReminders();

            verify(n8nWebhookService).sendReminder(any());
            freshRead();
            Reminder after = reminderRepository.findById(due.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(ReminderStatus.SENT);
            assertThat(after.getSentAt()).isNotNull();
        }
    }

    // ── Panel queue — CR cleared after boundary expiry ─────────────────────────

    @Nested
    @DisplayName("Panel — CR queue cleared via real status endpoint after boundary expiry")
    class PanelQueueEvidence {

        @Test
        @DisplayName("GET /status/CANCELLATION_REQUESTED: past CR visible before expiry, absent after")
        void crQueueClearsAfterBoundaryExpiry() throws Exception {
            Appointment pastCr = botCancellationRequested(now().minusHours(2), now().minusHours(1));

            mockMvc.perform(get("/api/businesses/{bid}/appointments/status/{st}",
                            testBusiness.getId(), "CANCELLATION_REQUESTED")
                            .header("Authorization", "Bearer " + tokenFor(ownerUser)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id",
                            Matchers.hasItem(pastCr.getId().intValue())));

            appointmentService.expireStaleCancellationRequests();

            mockMvc.perform(get("/api/businesses/{bid}/appointments/status/{st}",
                            testBusiness.getId(), "CANCELLATION_REQUESTED")
                            .header("Authorization", "Bearer " + tokenFor(ownerUser)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id",
                            Matchers.not(Matchers.hasItem(pastCr.getId().intValue()))));
        }
    }

    // ── Bot — temporal boundary guard on cancel and reschedule ─────────────────

    @Nested
    @DisplayName("Bot — temporal guard: cancel and reschedule reject past appointments")
    class BotTemporalBoundaryGuard {

        @Test
        @DisplayName("cancelFromBot on past appointment → rejected=true, no CR created, state unchanged, no outbound")
        void cancelFromBotOnPastIsRejected() throws Exception {
            Appointment pastConfirmed = appointment(AppointmentStatus.CONFIRMED, now().minusHours(2));

            // The isPast() guard fires before the booking-mode branch, making the
            // rejection uniform across all booking modes.
            var resp = appointmentService.cancelFromBot("evidence-instance", pastConfirmed.getId(),
                    customer.getPhone(), "test reason");

            assertThat(resp.getRejected()).isTrue();
            assertThat(resp.getBotMessage()).contains("ya ha pasado"); // LOCALE OUTPUT (Spanish)

            freshRead();
            Appointment after = appointmentRepository.findById(pastConfirmed.getId()).orElseThrow();
            // No phantom CR is created: the state does not change.
            assertThat(after.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
            // Zero customer outbound.
            verify(n8nWebhookService, never()).sendOutboundMessage(any(), any(), any());
        }

        @Test
        @DisplayName("rescheduleFromBot on past appointment → rejected (the future target datetime does not save a past origin)")
        void rescheduleFromBotOnPastIsRejected() {
            Appointment pastConfirmed = appointment(AppointmentStatus.CONFIRMED, now().minusHours(2));

            // The origin appointment is past: the isPast() guard fires before
            // any validation of the new target slot.
            assertThatThrownBy(() -> appointmentService.rescheduleFromBot(
                    "evidence-instance", pastConfirmed.getId(), customer.getPhone(),
                    now().plusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0), null))
                    .hasMessageContaining("ya ha pasado"); // LOCALE OUTPUT (Spanish)
        }
    }

    // ── Role matrix — OWNER / ADMIN / EMPLOYEE at the temporal boundary ────────

    @Nested
    @DisplayName("Role matrix — OWNER, ADMIN, and EMPLOYEE behaviour at the temporal boundary")
    class RoleMatrixEvidence {

        @Test
        @DisplayName("EMPLOYEE closes their own past assigned appointment (COMPLETED) → 200; closure plane is open")
        void employeeClosesOwnPastAppointment() throws Exception {
            Appointment past = appointment(AppointmentStatus.CONFIRMED, now().minusHours(2));
            past.setEmployee(employeeUser);
            appointmentRepository.save(past);

            mockMvc.perform(patch("/api/businesses/{bid}/appointments/{id}/status",
                            testBusiness.getId(), past.getId())
                            .header("Authorization", "Bearer " + tokenFor(employeeUser))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"COMPLETED\"}"))
                    .andExpect(status().isOk());

            assertThat(appointmentRepository.findById(past.getId()).orElseThrow().getStatus())
                    .isEqualTo(AppointmentStatus.COMPLETED);
        }

        @Test
        @DisplayName("EMPLOYEE attempts to close a future assigned appointment → 400 (not yet in closure plane)")
        void employeeCannotCloseFutureAppointment() throws Exception {
            Appointment future = appointment(AppointmentStatus.CONFIRMED, now().plusDays(1));
            future.setEmployee(employeeUser);
            appointmentRepository.save(future);

            mockMvc.perform(patch("/api/businesses/{bid}/appointments/{id}/status",
                            testBusiness.getId(), future.getId())
                            .header("Authorization", "Bearer " + tokenFor(employeeUser))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"COMPLETED\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("ADMIN rejects past CR → 400 (same boundary, no asymmetry with OWNER)")
        void adminRejectPastCrBlockedSameAsOwner() throws Exception {
            Appointment pastCr = botCancellationRequested(now().minusHours(3), now().minusHours(18));

            mockMvc.perform(patch("/api/businesses/{bid}/appointments/{id}/reject-cancel",
                            testBusiness.getId(), pastCr.getId())
                            .header("Authorization", "Bearer " + tokenFor(adminUser))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"admin attempt\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", Matchers.containsString("ya pasó"))); // LOCALE OUTPUT
        }

        @Test
        @DisplayName("OWNER reassigns employee on a past appointment → 200 (deliberate: reassignment is a closure-plane operation)")
        void ownerReassignsPastAppointmentAsClosure() throws Exception {
            // Employee reassignment on a past appointment is an intentional closure-plane
            // permission: the OWNER may correct the record after the fact. The
            // temporal boundary does not prohibit closure-plane operations; it prohibits
            // operational-plane decisions whose consequence would be incoherent
            // (e.g. sending a notification about a past event).
            LocalDateTime pastDt = now().minusHours(2);

            // The reassignment validates real availability (business schedule +
            // employee schedule) even on past appointments.
            scheduleRepository.save(com.vookedme.botmanager.schedule.entity.Schedule.builder()
                    .business(testBusiness)
                    .dayOfWeek(pastDt.getDayOfWeek().getValue())
                    .startTime(java.time.LocalTime.MIN)
                    .endTime(java.time.LocalTime.of(23, 59))
                    .active(true)
                    .capacity(10)
                    .build());
            employeeScheduleRepository.save(
                    com.vookedme.botmanager.employee.entity.EmployeeSchedule.builder()
                            .business(testBusiness)
                            .user(employeeUser)
                            .dayOfWeek(pastDt.getDayOfWeek().getValue())
                            .startTime(java.time.LocalTime.MIN)
                            .endTime(java.time.LocalTime.of(23, 59))
                            .active(true)
                            .build());

            Appointment past = appointmentRepository.save(Appointment.builder()
                    .business(testBusiness)
                    .customer(customer)
                    .source(AppointmentSource.BOT)
                    .status(AppointmentStatus.CONFIRMED)
                    .datetime(pastDt)
                    .durationMinutes(60)
                    .build());

            mockMvc.perform(patch("/api/businesses/{bid}/appointments/{id}/assign-employee/{empId}",
                            testBusiness.getId(), past.getId(), employeeUser.getId())
                            .header("Authorization", "Bearer " + tokenFor(ownerUser))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());

            Appointment after = appointmentRepository.findById(past.getId()).orElseThrow();
            assertThat(after.getEmployee()).isNotNull();
            assertThat(after.getEmployee().getId()).isEqualTo(employeeUser.getId());
        }
    }
}
