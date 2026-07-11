package com.vookedme.botmanager.appointment;

import com.vookedme.botmanager.appointment.entity.Appointment;
import com.vookedme.botmanager.appointment.entity.AppointmentSource;
import com.vookedme.botmanager.appointment.entity.AppointmentStatus;
import com.vookedme.botmanager.appointment.service.AppointmentService;
import com.vookedme.botmanager.customer.entity.Customer;
import com.vookedme.botmanager.notification.entity.reminders.Reminder;
import com.vookedme.botmanager.notification.outbound.BotOutboundNotificationService;
import com.vookedme.botmanager.notification.entity.reminders.ReminderChannel;
import com.vookedme.botmanager.notification.entity.reminders.ReminderStatus;
import com.vookedme.botmanager.notification.entity.reminders.ReminderType;
import com.vookedme.botmanager.notification.repository.ReminderRepository;
import com.vookedme.botmanager.notification.service.ReminderScheduler;
import com.vookedme.botmanager.offering.entity.Offering;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the ADR-011 Temporal Boundary (Principio de Frontera
 * Temporal — PFT). Covers the enforcement model established following a
 * production incident in 2026-06 that exposed three classes of boundary
 * violation: uncancelled reminder dispatch for past appointments (PFT-4),
 * owner decisions on past cancellation requests (PFT-3), and cancellation
 * request expiry cutoff behaviour (PFT-1).
 *
 * <p>These tests run against a real PostgreSQL instance (Testcontainers) via
 * {@code BaseIntegrationTest} *(published in a subsequent source batch)*.
 *
 * <h3>Companion test class</h3>
 *
 * <p>{@link TemporalBoundaryEvidenceIT} extends coverage with adversarial
 * scenarios: exact boundary instant, job idempotency, audit row attribution,
 * SQL-backdated pending-expiry selection, outbound transport mock, panel queue,
 * bot-path temporal guard, and the OWNER/ADMIN/EMPLOYEE role matrix.
 *
 * <p>Unpublished dependencies referenced in this class:
 * {@code AppointmentService}, {@code ReminderScheduler},
 * {@code BotOutboundNotificationService}, {@code ReminderRepository} —
 * published in a subsequent source batch.
 */
@DisplayName("ADR-011 Temporal Boundary — PFT enforcement")
class TemporalBoundaryIT extends BaseIntegrationTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    @Autowired
    private ReminderRepository reminderRepository;
    @Autowired
    private ReminderScheduler reminderScheduler;
    @Autowired
    private AppointmentService appointmentService;

    /**
     * The cancellation-reminder cancellation job uses bulk {@code @Modifying}
     * queries that update the database directly, bypassing the persistence
     * context cache. Without an explicit flush and clear, a re-read within the
     * same test transaction would return the stale cached entity (PENDING) rather
     * than the committed state (CANCELLED).
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Mock of the outbound notification service. Used to assert that the system
     * suppresses WhatsApp notifications to customers for past appointments
     * (PFT-5). Mocking here does not affect other tests in the class: the real
     * outbound service points to a non-production endpoint and fails silently
     * in the test environment.
     */
    @MockitoBean
    private BotOutboundNotificationService botOutboundNotificationService;

    private void freshRead() {
        entityManager.flush();
        entityManager.clear();
    }

    private Customer customer;
    private Offering offering;

    @BeforeEach
    void setUpFixtures() {
        offering = offeringRepository.save(Offering.builder()
                .business(testBusiness)
                .name("Masaje relajante") // LOCALE OUTPUT (Spanish): offering name in fixtures
                .durationMinutes(60)
                .price(BigDecimal.valueOf(50))
                .active(true)
                .build());

        customer = customerRepository.save(Customer.builder()
                .business(testBusiness)
                .phone("+34600999888")
                .name("Test Customer")
                .build());
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

    /**
     * Creates an appointment in {@code CANCELLATION_REQUESTED} state initiated
     * by the bot. The bot does not have a {@code User} identity, so
     * {@code cancellation_by_user_id} remains NULL.
     */
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

    // ── PFT-4 — reminders never cross the temporal boundary ───────────────────

    @Nested
    @DisplayName("PFT-4 — reminders for past appointments are cancelled, never sent")
    class ReminderBoundary {

        @Test
        @DisplayName("PENDING reminder for a past CONFIRMED appointment → CANCELLED terminal, never sent")
        void pastAppointmentReminderIsCancelledNotSent() {
            Appointment past = appointment(AppointmentStatus.CONFIRMED, now().minusHours(2));
            Reminder reminder = pendingReminder(past, now().minusHours(3));

            reminderScheduler.processReminders();

            freshRead();
            Reminder after = reminderRepository.findById(reminder.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(ReminderStatus.CANCELLED);
            assertThat(after.getSentAt()).isNull();
        }

        @Test
        @DisplayName("Regression (production incident): reminder suppressed by active CR with past appointment → CANCELLED (breaks re-poll loop)")
        void suppressedReminderOfPastAppointmentIsCancelled() {
            // Exact state of the production incident: active cancellation request,
            // appointment already in the past, reminder still PENDING and re-polled
            // every minute. If the CR were later resolved to CONFIRMED, the now-absent
            // reminder would prevent a spurious "your appointment is in 1 hour" message.
            Appointment pastCr = botCancellationRequested(now().minusHours(2), now().minusHours(18));
            Reminder reminder = pendingReminder(pastCr, now().minusHours(3));

            reminderScheduler.processReminders();

            freshRead();
            Reminder after = reminderRepository.findById(reminder.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(ReminderStatus.CANCELLED);
            assertThat(after.getSentAt()).isNull();
        }

        @Test
        @DisplayName("Regression: deferred reminder for a future appointment with active CR stays PENDING")
        void futureCrReminderStaysDeferred() {
            Appointment futureCr = botCancellationRequested(now().plusHours(3), now().minusMinutes(30));
            Reminder reminder = pendingReminder(futureCr, now().minusMinutes(5));

            reminderScheduler.processReminders();

            freshRead();
            Reminder after = reminderRepository.findById(reminder.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(ReminderStatus.PENDING);
        }
    }

    // ── PFT-3 — decisions on past cancellation requests → 400 ─────────────────

    @Nested
    @DisplayName("PFT-3 — approve/reject a cancellation request for a past appointment → 400")
    class CancellationRequestDecisionBoundary {

        @Test
        @DisplayName("Production incident path: reject CR for past appointment → 400, state unchanged, no outbound")
        void rejectPastCrIsBlocked() throws Exception {
            Appointment pastCr = botCancellationRequested(now().minusHours(5), now().minusHours(18));

            mockMvc.perform(patch("/api/businesses/{bid}/appointments/{id}/reject-cancel",
                            testBusiness.getId(), pastCr.getId())
                            .header("Authorization", "Bearer " + tokenFor(ownerUser))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"test\"}"))
                    .andExpect(status().isBadRequest())
                    // LOCALE OUTPUT (Spanish): error message returned to the panel UI
                    .andExpect(jsonPath("$.message", Matchers.containsString("ya pasó")));

            Appointment after = appointmentRepository.findById(pastCr.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(AppointmentStatus.CANCELLATION_REQUESTED);
            assertThat(after.getCancellationRequestDecidedAt()).isNull();
        }

        @Test
        @DisplayName("Approve CR for past appointment → 400, state unchanged")
        void approvePastCrIsBlocked() throws Exception {
            Appointment pastCr = botCancellationRequested(now().minusHours(5), now().minusHours(18));

            mockMvc.perform(patch("/api/businesses/{bid}/appointments/{id}/approve-cancel",
                            testBusiness.getId(), pastCr.getId())
                            .header("Authorization", "Bearer " + tokenFor(ownerUser))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", Matchers.containsString("ya pasó"))); // LOCALE OUTPUT

            Appointment after = appointmentRepository.findById(pastCr.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(AppointmentStatus.CANCELLATION_REQUESTED);
        }

        @Test
        @DisplayName("Regression: reject CR for a future appointment still works → CONFIRMED")
        void rejectFutureCrStillWorks() throws Exception {
            Appointment futureCr = botCancellationRequested(now().plusDays(2), now().minusHours(1));

            mockMvc.perform(patch("/api/businesses/{bid}/appointments/{id}/reject-cancel",
                            testBusiness.getId(), futureCr.getId())
                            .header("Authorization", "Bearer " + tokenFor(ownerUser))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"valid reason\"}"))
                    .andExpect(status().isOk());

            Appointment after = appointmentRepository.findById(futureCr.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
            assertThat(after.getCancellationRequestDecidedAt()).isNotNull();
        }
    }

    // ── PFT-1 — CR expiry cutoff = min(requestedAt + timeout, datetime) ────────

    @Nested
    @DisplayName("PFT-1 — CR expiry cutoff: min(requestedAt + timeout, appointment datetime)")
    class CancellationRequestExpiryBoundary {

        @Test
        @DisplayName("CR inside its response window but appointment already past → expires immediately to CONFIRMED")
        void crExpiresAtBoundaryEvenInsideResponseWindow() {
            // Counterfactual of the production incident: the CR was submitted 1 hour ago
            // (the 24-hour response window is still open by 23 hours) but the appointment
            // passed 2 hours ago. Before PFT-1, this CR would remain active for another
            // 23 hours, crossing the temporal boundary.
            Appointment pastCr = botCancellationRequested(now().minusHours(2), now().minusHours(1));

            int expired = appointmentService.expireStaleCancellationRequests();

            assertThat(expired).isGreaterThanOrEqualTo(1);
            Appointment after = appointmentRepository.findById(pastCr.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
            assertThat(after.getCancellationRequestExpiredAt()).isNotNull();
            // "Silence is not approval" is preserved: the appointment was not cancelled.
            assertThat(after.getCancelledAt()).isNull();
        }

        @Test
        @DisplayName("Regression: CR for a future appointment inside its response window does not expire")
        void futureCrInsideWindowDoesNotExpire() {
            Appointment futureCr = botCancellationRequested(now().plusDays(3), now().minusHours(1));

            appointmentService.expireStaleCancellationRequests();

            Appointment after = appointmentRepository.findById(futureCr.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(AppointmentStatus.CANCELLATION_REQUESTED);
            assertThat(after.getCancellationRequestExpiredAt()).isNull();
        }
    }

    // ── PFT-5 — outbound to customer only if appointment is in the future ──────

    @Nested
    @DisplayName("PFT-5 — outbound notifications suppressed for past appointments")
    class OutboundTemporalCondition {

        @Test
        @DisplayName("Direct OWNER cancel on past CR → CANCELLED (closure plane), no customer outbound")
        void directCancelOnPastCrSuppressesCustomerOutbound() throws Exception {
            // Cancelling a CR directly (rather than approving or rejecting it) is a
            // valid closure-plane operation even for past appointments. PFT-5 ensures
            // the customer does not receive a "your cancellation is confirmed" message
            // about an appointment that has already passed.
            Appointment pastCr = botCancellationRequested(now().minusHours(5), now().minusHours(18));

            mockMvc.perform(patch("/api/businesses/{bid}/appointments/{id}/cancel",
                            testBusiness.getId(), pastCr.getId())
                            .header("Authorization", "Bearer " + tokenFor(ownerUser))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());

            Appointment after = appointmentRepository.findById(pastCr.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
            verify(botOutboundNotificationService, never()).notifyCancellationApproved(any());
        }

        @Test
        @DisplayName("Positive control: approve a future CR → notifyCancellationApproved is called")
        void approveFutureCrSendsCustomerOutbound() throws Exception {
            Appointment futureCr = botCancellationRequested(now().plusDays(2), now().minusHours(1));

            mockMvc.perform(patch("/api/businesses/{bid}/appointments/{id}/approve-cancel",
                            testBusiness.getId(), futureCr.getId())
                            .header("Authorization", "Bearer " + tokenFor(ownerUser))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());

            verify(botOutboundNotificationService).notifyCancellationApproved(any());
        }
    }

    // ── Decisions on past PENDING + BOT appointments ───────────────────────────

    @Nested
    @DisplayName("Bot-pending decision boundary — 15-minute decision window")
    class BotPendingDecisionBoundary {

        /**
         * A {@code PENDING+BOT} appointment whose datetime is 1 hour in the past —
         * outside the 15-minute post-start decision window — but whose
         * {@code created_at} is now (so the bot-approval expiry, which checks
         * creation time against a 2-hour timeout, has not yet fired).
         *
         * <p>This reproduces the exact state under test: the temporal boundary
         * guard (checking {@code datetime}) must reject the operation independently
         * of the approval expiry mechanism (checking {@code created_at}).
         */
        private Appointment pastBotPending() {
            Appointment a = appointment(AppointmentStatus.PENDING, now().minusHours(1));
            return appointmentRepository.save(a);
        }

        @Test
        @DisplayName("Approve PENDING+BOT appointment more than 15 min past start → 400, state unchanged, no outbound")
        void approvePastBotPendingIsBlocked() throws Exception {
            Appointment pending = pastBotPending();

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/businesses/{bid}/appointments/{id}/approve-bot-pending",
                                    testBusiness.getId(), pending.getId())
                            .header("Authorization", "Bearer " + tokenFor(ownerUser))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", Matchers.containsString("ya ha pasado"))); // LOCALE OUTPUT

            Appointment after = appointmentRepository.findById(pending.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(AppointmentStatus.PENDING);
            verify(botOutboundNotificationService, never()).notifyApproved(any(), any(), any());
        }

        @Test
        @DisplayName("Reject PENDING+BOT appointment more than 15 min past start → 400, state unchanged, no outbound")
        void rejectPastBotPendingIsBlocked() throws Exception {
            Appointment pending = pastBotPending();

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/businesses/{bid}/appointments/{id}/reject-bot-pending",
                                    testBusiness.getId(), pending.getId())
                            .header("Authorization", "Bearer " + tokenFor(ownerUser))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", Matchers.containsString("ya ha pasado"))); // LOCALE OUTPUT

            Appointment after = appointmentRepository.findById(pending.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(AppointmentStatus.PENDING);
            verify(botOutboundNotificationService, never()).notifyRejected(any(), any());
        }

        @Test
        @DisplayName("Reject within the 15-minute window (appointment started 5 min ago) → 200 + timely customer notification")
        void rejectWithinGraceSucceeds() throws Exception {
            // Within the 15-minute decision window the customer may still be at
            // the premises. Deciding (approve or reject) is still a valid
            // operational-plane action and the notification to the customer is
            // timely and coherent, not absurd.
            Appointment justStarted = appointment(AppointmentStatus.PENDING, now().minusMinutes(5));

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/businesses/{bid}/appointments/{id}/reject-bot-pending",
                                    testBusiness.getId(), justStarted.getId())
                            .header("Authorization", "Bearer " + tokenFor(ownerUser))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());

            Appointment after = appointmentRepository.findById(justStarted.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
            verify(botOutboundNotificationService).notifyRejected(any(), any());
        }
    }
}
