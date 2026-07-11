package com.vookedme.botmanager.bot;

import com.vookedme.botmanager.appointment.entity.Appointment;
import com.vookedme.botmanager.appointment.entity.AppointmentSource;
import com.vookedme.botmanager.appointment.entity.AppointmentStatus;
import com.vookedme.botmanager.appointment.service.AppointmentService;
import com.vookedme.botmanager.customer.entity.Customer;
import com.vookedme.botmanager.offering.entity.Offering;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the re-anchoring read: {@code findRecentRelevantByInstanceAndPhone}.
 *
 * <p>The re-anchoring read is the mechanism by which the bot determines whether a
 * customer has an active appointment before starting a new booking flow. It implements
 * a 24-hour sliding window over active appointment statuses, filtering out both
 * terminal statuses and appointments that fall outside the window.
 *
 * <p><b>What this test verifies (R1 + R2):</b>
 * <ul>
 *   <li><b>R1 — Inclusion by active status</b>: appointments in
 *       {@code PENDING}, {@code CONFIRMED}, and {@code CANCELLATION_REQUESTED} states
 *       created within the 24-hour window are returned. Appointments in terminal states
 *       ({@code CANCELLED}, {@code COMPLETED}, {@code REJECTED}) are excluded even when
 *       created within the window. Appointments outside the window are excluded regardless
 *       of status. Appointments for a different customer phone are excluded.</li>
 *   <li><b>R2 — Verified absence for terminal states</b>: a recently-created {@code CANCELLED}
 *       appointment is absent from the result set — confirming that the query does not
 *       simply return all recent activity but specifically filters by the active-status
 *       predicate.</li>
 * </ul>
 *
 * <p><b>Design context</b>: the re-anchoring read is described in ADR-012. The 24-hour
 * window is a deliberate trade-off between coherence (avoid proposing a duplicate
 * appointment for an active booking) and staleness (do not block new bookings due to
 * terminal appointments from the previous day). The window slides continuously — there
 * is no daily reset.
 *
 * <p><b>Test scope</b>: this test operates at the {@code AppointmentService} boundary
 * (not the repository level) to verify that the service-layer query contract matches the
 * re-anchoring specification end-to-end, including the persistence layer's handling of
 * the time-window predicate.
 *
 * <p>Depends on {@code BaseIntegrationTest} (Testcontainers PostgreSQL, fixture
 * repositories, and {@code testBusiness} seed) — published in a subsequent source batch.
 */
@DisplayName("Re-anchoring read: row minimisation by status and 24-hour moving window")
class BotRecentRelevantReadIT extends BaseIntegrationTest {

    /**
     * Synthetic test phone number. Does not identify any real customer.
     * Used consistently across all fixture rows to exercise the
     * per-phone filter in {@code findRecentRelevantByInstanceAndPhone}.
     */
    private static final String PHONE = "+34600999888";

    /**
     * Synthetic phone for the cross-customer exclusion test. Must differ from {@link #PHONE}.
     */
    private static final String OTHER_PHONE = "+34600000001";

    @Autowired
    private AppointmentService appointmentService;

    private Customer testCustomer;
    private Customer otherCustomer;
    private Offering testOffering;

    @BeforeEach
    void setUpFixtures() {
        testCustomer = customerRepository.save(Customer.builder()
                .business(testBusiness)
                .phone(PHONE)
                .name("Test Customer")
                .build());
        otherCustomer = customerRepository.save(Customer.builder()
                .business(testBusiness)
                .phone(OTHER_PHONE)
                .name("Other Customer")
                .build());
        testOffering = offeringRepository.save(Offering.builder()
                .business(testBusiness)
                .name("Test Offering")
                .durationMinutes(30)
                .price(BigDecimal.valueOf(20))
                .active(true)
                .build());
    }

    @AfterEach
    void tearDownFixtures() {
        appointmentRepository.deleteAll();
        offeringRepository.deleteAll();
        customerRepository.deleteAll();
    }

    // ─── Fixture helper ──────────────────────────────────────────────────────

    /**
     * Saves an appointment with the given status and a {@code createdAt} offset
     * relative to now. A negative offset places the row in the past; a positive
     * offset places it in the future.
     *
     * @param customer  the appointment's customer
     * @param status    the appointment status to persist
     * @param createdAt the creation timestamp (determines 24h-window inclusion)
     */
    private Appointment save(Customer customer, AppointmentStatus status, LocalDateTime createdAt) {
        Appointment a = Appointment.builder()
                .business(testBusiness)
                .customer(customer)
                .offering(testOffering)
                .source(AppointmentSource.BOT)
                .status(status)
                .datetime(createdAt.plusDays(1)) // scheduled datetime is always in future
                .durationMinutes(30)
                .build();
        a.setCreatedAt(createdAt);
        return appointmentRepository.save(a);
    }

    // ════════════════════════════════════════════════════════════════════════
    // R1 — Inclusion and exclusion by active status within the window
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("R1 — Active statuses within the window are returned")
    class R1ActiveStatusesIncluded {

        @Test
        @DisplayName("PENDING, CONFIRMED, CANCELLATION_REQUESTED are all returned when within 24h")
        void threeActiveStatuses_allReturned() {
            LocalDateTime withinWindow = LocalDateTime.now().minusHours(6);
            save(testCustomer, AppointmentStatus.PENDING, withinWindow);
            save(testCustomer, AppointmentStatus.CONFIRMED, withinWindow.minusMinutes(10));
            save(testCustomer, AppointmentStatus.CANCELLATION_REQUESTED, withinWindow.minusMinutes(20));

            List<Appointment> result = appointmentService
                    .findRecentRelevantByInstanceAndPhone(testBusiness.getInstance(), PHONE);

            assertThat(result).hasSize(3);
            assertThat(result.stream().map(Appointment::getStatus))
                    .containsExactlyInAnyOrder(
                            AppointmentStatus.PENDING,
                            AppointmentStatus.CONFIRMED,
                            AppointmentStatus.CANCELLATION_REQUESTED);
        }

        @Test
        @DisplayName("CANCELLED within window is excluded (terminal state)")
        void cancelledWithinWindow_excluded() {
            LocalDateTime withinWindow = LocalDateTime.now().minusHours(3);
            save(testCustomer, AppointmentStatus.CANCELLED, withinWindow);

            List<Appointment> result = appointmentService
                    .findRecentRelevantByInstanceAndPhone(testBusiness.getInstance(), PHONE);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("COMPLETED within window is excluded (terminal state)")
        void completedWithinWindow_excluded() {
            LocalDateTime withinWindow = LocalDateTime.now().minusHours(2);
            save(testCustomer, AppointmentStatus.COMPLETED, withinWindow);

            List<Appointment> result = appointmentService
                    .findRecentRelevantByInstanceAndPhone(testBusiness.getInstance(), PHONE);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("REJECTED within window is excluded (terminal state)")
        void rejectedWithinWindow_excluded() {
            LocalDateTime withinWindow = LocalDateTime.now().minusHours(1);
            save(testCustomer, AppointmentStatus.REJECTED, withinWindow);

            List<Appointment> result = appointmentService
                    .findRecentRelevantByInstanceAndPhone(testBusiness.getInstance(), PHONE);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("CONFIRMED outside the 24h window is excluded")
        void confirmedOutsideWindow_excluded() {
            LocalDateTime outsideWindow = LocalDateTime.now().minusHours(25);
            save(testCustomer, AppointmentStatus.CONFIRMED, outsideWindow);

            List<Appointment> result = appointmentService
                    .findRecentRelevantByInstanceAndPhone(testBusiness.getInstance(), PHONE);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Appointment for a different customer phone is excluded")
        void differentPhone_excluded() {
            LocalDateTime withinWindow = LocalDateTime.now().minusHours(4);
            save(otherCustomer, AppointmentStatus.CONFIRMED, withinWindow);
            // No rows for PHONE
            List<Appointment> result = appointmentService
                    .findRecentRelevantByInstanceAndPhone(testBusiness.getInstance(), PHONE);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Mix: 2 active + 1 terminal + 1 out-of-window → only 2 active returned")
        void mixedFixture_onlyActiveReturned() {
            LocalDateTime withinWindow = LocalDateTime.now().minusHours(5);
            save(testCustomer, AppointmentStatus.PENDING, withinWindow);
            save(testCustomer, AppointmentStatus.CONFIRMED, withinWindow.minusMinutes(15));
            save(testCustomer, AppointmentStatus.CANCELLED, withinWindow.minusMinutes(30)); // terminal
            save(testCustomer, AppointmentStatus.CONFIRMED, LocalDateTime.now().minusHours(26)); // out-of-window

            List<Appointment> result = appointmentService
                    .findRecentRelevantByInstanceAndPhone(testBusiness.getInstance(), PHONE);

            assertThat(result).hasSize(2);
            assertThat(result.stream().map(Appointment::getStatus))
                    .containsExactlyInAnyOrder(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // R1 — 24-hour window boundary precision
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("R1 — 24-hour window boundary precision")
    class R1WindowBoundary {

        @Test
        @DisplayName("23h59m ago (inside window) → included")
        void twentyThreeHoursFiftyNineMinutes_inside() {
            LocalDateTime nearBoundary = LocalDateTime.now().minusHours(23).minusMinutes(59);
            save(testCustomer, AppointmentStatus.CONFIRMED, nearBoundary);

            List<Appointment> result = appointmentService
                    .findRecentRelevantByInstanceAndPhone(testBusiness.getInstance(), PHONE);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("24h01m ago (outside window) → excluded")
        void twentyFourHoursOneMinute_outside() {
            LocalDateTime justOutside = LocalDateTime.now().minusHours(24).minusMinutes(1);
            save(testCustomer, AppointmentStatus.CONFIRMED, justOutside);

            List<Appointment> result = appointmentService
                    .findRecentRelevantByInstanceAndPhone(testBusiness.getInstance(), PHONE);

            assertThat(result).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // R2 — Verified absence: CANCELLED is genuinely absent (not a query artefact)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("R2 — Verified absence of CANCELLED in recent window")
    class R2CancelledAbsence {

        @Test
        @DisplayName("CANCELLED row is in the DB within the window but absent from result set")
        void cancelledRecentRow_genuinelyAbsent() {
            LocalDateTime withinWindow = LocalDateTime.now().minusHours(2);

            // Persist a CANCELLED row that is recent (within the 24h window).
            // If the query returned ALL recent rows regardless of status, this would appear.
            Appointment cancelled = save(testCustomer, AppointmentStatus.CANCELLED, withinWindow);

            // Verify the row persisted successfully (it is in the DB).
            assertThat(appointmentRepository.findById(cancelled.getId())).isPresent();

            // Verify it does NOT appear in the re-anchoring read result.
            List<Appointment> result = appointmentService
                    .findRecentRelevantByInstanceAndPhone(testBusiness.getInstance(), PHONE);

            assertThat(result)
                    .as("CANCELLED appointment must be absent from re-anchoring read even when recent")
                    .isEmpty();
        }

        @Test
        @DisplayName("CONFIRMED recent + CANCELLED recent → only CONFIRMED returned")
        void confirmedPlusCancelled_onlyConfirmedReturned() {
            LocalDateTime withinWindow = LocalDateTime.now().minusHours(3);
            save(testCustomer, AppointmentStatus.CONFIRMED, withinWindow);
            save(testCustomer, AppointmentStatus.CANCELLED, withinWindow.minusMinutes(5));

            List<Appointment> result = appointmentService
                    .findRecentRelevantByInstanceAndPhone(testBusiness.getInstance(), PHONE);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        }
    }
}
