package com.vookedme.botmanager.appointment;

import com.vookedme.botmanager.appointment.entity.Appointment;
import com.vookedme.botmanager.appointment.entity.AppointmentAuditLog;
import com.vookedme.botmanager.appointment.entity.AppointmentSource;
import com.vookedme.botmanager.appointment.entity.AppointmentStatus;
import com.vookedme.botmanager.appointment.repository.AppointmentAuditLogRepository;
import com.vookedme.botmanager.appointment.service.AppointmentService;
import com.vookedme.botmanager.auth.security.UserPrincipal;
import com.vookedme.botmanager.common.event.AppointmentEvent;
import com.vookedme.botmanager.common.event.SourceActor;
import com.vookedme.botmanager.customer.entity.Customer;
import com.vookedme.botmanager.offering.entity.Offering;
import com.vookedme.botmanager.schedule.dto.ScheduleRequest;
import com.vookedme.botmanager.schedule.entity.Schedule;
import com.vookedme.botmanager.schedule.service.ScheduleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the appointment audit pipeline (ADR-002).
 *
 * <p>Verifies that a real appointment mutation publishes an {@link AppointmentEvent} that
 * {@code AppointmentAuditListener} persists synchronously to {@code appointment_audit_log}
 * within the same transaction, with correct actor attribution per origin path:
 *
 * <ul>
 *   <li>PANEL / OWNER → {@code togglePaid} → PAID_TOGGLED, actorUserId required;</li>
 *   <li>BOT → {@code cancelFromBot} → CANCELLED, triggeredBy = CUSTOMER, actorUserId NULL;</li>
 *   <li>SCHEDULER → {@code expireStaleCancellationRequests} → CONFIRMED,
 *       triggeredBy = SCHEDULER, actorUserId NULL;</li>
 *   <li>PANEL / OWNER bulk day-close → {@code ScheduleService.update} → N × CANCELLED
 *       with a shared correlationId across all affected appointments.</li>
 * </ul>
 *
 * <p>The audit row is visible immediately after the service call within the test
 * transaction, demonstrating the atomicity of the committed ⟺ audited invariant: the
 * synchronous {@code @EventListener} writes in the same transaction, so the row is visible
 * to the same transaction context without an explicit flush.
 *
 * <p>Depends on {@code BaseIntegrationTest} (Testcontainers PostgreSQL, fixture
 * repositories, and owner / employee user seeds) — published in a subsequent source batch.
 */
class AppointmentAuditFlowIT extends BaseIntegrationTest {

    @Autowired private AppointmentService appointmentService;
    @Autowired private ScheduleService scheduleService;
    @Autowired private AppointmentAuditLogRepository auditRepository;

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void authenticateAs(com.vookedme.botmanager.auth.entity.User user) {
        UserPrincipal principal = new UserPrincipal(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private Customer customer(String phone, String name) {
        return customerRepository.save(Customer.builder()
                .business(testBusiness).phone(phone).name(name).build());
    }

    private Offering offering() {
        return offeringRepository.save(Offering.builder()
                .business(testBusiness).name("Haircut").durationMinutes(30)
                .price(BigDecimal.valueOf(20)).active(true).build());
    }

    private Appointment saveAppointment(Customer c, Offering o, AppointmentStatus status,
                                        AppointmentSource source, LocalDateTime when) {
        return appointmentRepository.save(Appointment.builder()
                .business(testBusiness).customer(c).offering(o)
                .source(source).status(status).datetime(when).durationMinutes(30)
                .build());
    }

    private List<AppointmentAuditLog> auditRows(Long appointmentId) {
        return auditRepository.findByAppointmentIdOrderByOccurredAtAsc(appointmentId);
    }

    // ── PANEL / OWNER → PAID_TOGGLED, actorUserId required ───────────────────
    @Test
    void panelTogglePaid_persistsPaidToggledRow_attributedToOwnerWithUserId() {
        Customer c = customer("+34600000001", "Customer A");
        Offering o = offering();
        Appointment appt = saveAppointment(c, o, AppointmentStatus.COMPLETED,
                AppointmentSource.PANEL, LocalDateTime.now().minusDays(1));
        authenticateAs(ownerUser);

        appointmentService.togglePaid(testBusiness.getId(), appt.getId(),
                Optional.empty(), Optional.empty());

        List<AppointmentAuditLog> rows = auditRows(appt.getId());
        assertThat(rows).hasSize(1);
        AppointmentAuditLog row = rows.get(0);
        assertThat(row.getEventType()).isEqualTo(AppointmentEvent.Type.PAID_TOGGLED);
        assertThat(row.getTriggeredBy()).isEqualTo(SourceActor.OWNER);
        assertThat(row.getActorUserId()).isEqualTo(ownerUser.getId());
        assertThat(row.getBusinessId()).isEqualTo(testBusiness.getId());
        assertThat(row.getOccurredAt()).isNotNull();
        assertThat(row.getDetail()).contains("paid_toggle");
    }

    // ── BOT → CANCELLED, triggeredBy = CUSTOMER, actorUserId NULL ────────────
    @Test
    void botCancellation_persistsCancelledRow_attributedToCustomerWithNullUserId() {
        Customer c = customer("+34600000002", "Customer B");
        Offering o = offering();
        Appointment appt = saveAppointment(c, o, AppointmentStatus.CONFIRMED,
                AppointmentSource.BOT, LocalDateTime.now().plusDays(10));
        // No SecurityContext: the bot is not an authenticated panel user.

        appointmentService.cancelFromBot(testBusiness.getInstance(), appt.getId(),
                c.getPhone(), "cannot attend");

        List<AppointmentAuditLog> rows = auditRows(appt.getId());
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getEventType()).isEqualTo(AppointmentEvent.Type.CANCELLED);
            assertThat(row.getTriggeredBy()).isEqualTo(SourceActor.CUSTOMER);
            assertThat(row.getActorUserId()).isNull();
        });
    }

    // ── SCHEDULER → CONFIRMED, triggeredBy = SCHEDULER, actorUserId NULL ─────
    @Test
    void schedulerExpiry_persistsConfirmedRow_attributedToSchedulerWithNullUserId() {
        testBusiness.setCancellationRequestTimeoutHours(24);
        testBusiness = businessRepository.save(testBusiness);

        Customer c = customer("+34600000003", "Customer C");
        Offering o = offering();
        Appointment appt = saveAppointment(c, o, AppointmentStatus.CANCELLATION_REQUESTED,
                AppointmentSource.PANEL, LocalDateTime.now().plusDays(10));
        appt.setCancellationRequestedAt(LocalDateTime.now().minusHours(48));
        appt.setCancellationRequestedByUserId(employeeUser.getId());
        appt.setCancellationRequestedActor("EMPLOYEE");
        appointmentRepository.save(appt);

        int expired = appointmentService.expireStaleCancellationRequests();

        assertThat(expired).isGreaterThanOrEqualTo(1);
        List<AppointmentAuditLog> rows = auditRows(appt.getId());
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getEventType()).isEqualTo(AppointmentEvent.Type.CONFIRMED);
            assertThat(row.getTriggeredBy()).isEqualTo(SourceActor.SCHEDULER);
            assertThat(row.getActorUserId()).isNull();
        });
    }

    // ── PANEL / OWNER bulk day-close → N × CANCELLED + shared correlationId ──
    @Test
    void panelBulkCancel_persistsCancelledRows_attributedToOwner_withSharedCorrelationId() {
        Customer c = customer("+34600000004", "Customer D");
        Offering o = offering();
        LocalDate target = LocalDate.now().plusDays(10);
        int dow = target.getDayOfWeek().getValue(); // 1..7 (Mon..Sun)

        Schedule schedule = scheduleRepository.save(Schedule.builder()
                .business(testBusiness).dayOfWeek(dow)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(20, 0))
                .active(true).capacity(1).build());

        Appointment a1 = saveAppointment(c, o, AppointmentStatus.CONFIRMED,
                AppointmentSource.PANEL, target.atTime(10, 0));
        Appointment a2 = saveAppointment(c, o, AppointmentStatus.CONFIRMED,
                AppointmentSource.PANEL, target.atTime(11, 0));

        authenticateAs(ownerUser);

        ScheduleRequest closeDay = ScheduleRequest.builder()
                .dayOfWeek(dow)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(20, 0))
                .active(false).capacity(1).build();

        scheduleService.update(testBusiness.getId(), schedule.getId(), closeDay, true);

        List<AppointmentAuditLog> rows1 = auditRows(a1.getId());
        List<AppointmentAuditLog> rows2 = auditRows(a2.getId());
        assertThat(rows1).hasSize(1);
        assertThat(rows2).hasSize(1);
        AppointmentAuditLog r1 = rows1.get(0);
        AppointmentAuditLog r2 = rows2.get(0);
        assertThat(r1.getEventType()).isEqualTo(AppointmentEvent.Type.CANCELLED);
        assertThat(r2.getEventType()).isEqualTo(AppointmentEvent.Type.CANCELLED);
        assertThat(r1.getTriggeredBy()).isEqualTo(SourceActor.OWNER);
        assertThat(r2.getTriggeredBy()).isEqualTo(SourceActor.OWNER);
        assertThat(r1.getActorUserId()).isEqualTo(ownerUser.getId());
        assertThat(r2.getActorUserId()).isEqualTo(ownerUser.getId());
        // correlationId is shared across all appointments cancelled in the same bulk operation.
        assertThat(r1.getCorrelationId()).isNotNull().isEqualTo(r2.getCorrelationId());
    }
}
