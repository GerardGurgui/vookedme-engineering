package com.vookedme.botmanager.appointment;

import com.vookedme.botmanager.appointment.entity.Appointment;
import com.vookedme.botmanager.appointment.entity.AppointmentStatus;
import com.vookedme.botmanager.appointment.repository.AppointmentRepository;
import com.vookedme.botmanager.customer.entity.Customer;
import com.vookedme.botmanager.customer.repository.CustomerRepository;
import com.vookedme.botmanager.employee.entity.EmployeeSchedule;
import com.vookedme.botmanager.employee.repository.EmployeeScheduleRepository;
import com.vookedme.botmanager.offering.entity.Offering;
import com.vookedme.botmanager.offering.repository.OfferingRepository;
import com.vookedme.botmanager.schedule.entity.Schedule;
import com.vookedme.botmanager.schedule.repository.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests verifying that the UNIQUE PARTIAL INDEX
 * {@code uk_appointments_employee_slot} prevents double-booking when two
 * concurrent requests attempt to create appointments for the same
 * {@code (employee_id, datetime)} in an active status.
 *
 * <p><b>{@code @Version} does not help for concurrent INSERTs.</b>
 * Optimistic locking only protects concurrent UPDATEs of the same row. The
 * real defence against double-booking is the database-level partial unique
 * index; these tests confirm it holds under actual concurrency.
 *
 * <p>Five scenarios are covered:
 * <ol>
 *   <li>Single-thread baseline (sanity check)</li>
 *   <li>Real race condition with 2 threads → exactly one survives</li>
 *   <li>CANCELLED rows are outside the index scope → rebooking the same slot succeeds</li>
 *   <li>Different employees at the same datetime → both succeed (no conflict)</li>
 *   <li>NULL employee_id is outside the index scope → multiple unassigned appointments at the same datetime succeed</li>
 * </ol>
 *
 * <p>Each operation runs in its own transaction
 * ({@code transactionTemplate.executeWithoutResult}) so that intermediate
 * commits are visible to subsequent operations (test 3) and concurrent
 * threads open independent transactions (test 2).
 *
 * <p>Post-race assertions use {@link org.springframework.jdbc.core.JdbcTemplate}
 * (inherited from {@code BaseConcurrencyIntegrationTest#jdbc}) rather than
 * JPA repositories: after a {@link DataIntegrityViolationException} the
 * transaction is poisoned in PostgreSQL, and any subsequent JPA query would
 * fail with {@code current transaction is aborted}.
 *
 * <p><em>Note: this class extends {@code BaseConcurrencyIntegrationTest},
 * which provides Testcontainers PostgreSQL, {@code transactionTemplate},
 * {@code jdbc}, and the shared test fixtures ({@code testBusiness},
 * {@code employeeUser}, {@code userRepository}, {@code passwordEncoder},
 * {@code TEST_PASSWORD}). The base class will be published in a subsequent
 * batch.</em>
 */
class AppointmentConcurrencyIntegrationTest extends BaseConcurrencyIntegrationTest {

    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private OfferingRepository offeringRepository;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private EmployeeScheduleRepository employeeScheduleRepository;

    private Long offeringId;
    private Long customer1Id;
    private Long customer2Id;
    private LocalDate testDate;

    @BeforeEach
    void setUp() {
        testDate = LocalDate.now().plusWeeks(2);

        transactionTemplate.executeWithoutResult(s -> {
            scheduleRepository.save(Schedule.builder()
                    .business(testBusiness)
                    .dayOfWeek(testDate.getDayOfWeek().getValue())
                    .startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(18, 0))
                    .active(true)
                    .capacity(10)
                    .build());

            Offering offering = offeringRepository.save(Offering.builder()
                    .business(testBusiness)
                    .name("Haircut")
                    .durationMinutes(30)
                    .price(new BigDecimal("25.00"))
                    .active(true)
                    .build());
            offeringId = offering.getId();

            // Employee schedule — needed for the upstream availability validation.
            employeeScheduleRepository.save(EmployeeSchedule.builder()
                    .user(employeeUser)
                    .business(testBusiness)
                    .dayOfWeek(testDate.getDayOfWeek().getValue())
                    .startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(18, 0))
                    .active(true)
                    .build());

            Customer c1 = customerRepository.save(Customer.builder()
                    .business(testBusiness)
                    .phone("+34600000001")
                    .name("Customer One")
                    .isActive(true)
                    .build());
            customer1Id = c1.getId();

            Customer c2 = customerRepository.save(Customer.builder()
                    .business(testBusiness)
                    .phone("+34600000002")
                    .name("Customer Two")
                    .isActive(true)
                    .build());
            customer2Id = c2.getId();
        });
    }

    @Test
    @DisplayName("[1] Baseline single-thread: create one appointment for slot A on employee X → success")
    void shouldCreateSingleAppointment() {
        LocalDateTime slot = testDate.atTime(10, 0);

        Long aptId = transactionTemplate.execute(s -> {
            Customer c = customerRepository.findById(customer1Id).orElseThrow();
            Offering o = offeringRepository.findById(offeringId).orElseThrow();
            Appointment apt = appointmentRepository.save(Appointment.builder()
                    .business(testBusiness)
                    .customer(c)
                    .offering(o)
                    .employee(employeeUser)
                    .datetime(slot)
                    .durationMinutes(30)
                    .status(AppointmentStatus.CONFIRMED)
                    .createdBy("PANEL")
                    .build());
            return apt.getId();
        });

        assertThat(aptId).isNotNull();
        // Assert via JdbcTemplate — new connection, no active transaction required.
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE id = ?", Long.class, aptId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("[2] Real race: two threads create appointment for same (employee, slot) → exactly one succeeds")
    void shouldRejectConcurrentDoubleBooking() throws Exception {
        LocalDateTime slot = testDate.atTime(11, 0);

        AtomicReference<Throwable> error1 = new AtomicReference<>();
        AtomicReference<Throwable> error2 = new AtomicReference<>();

        var executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> {
                try {
                    transactionTemplate.executeWithoutResult(s -> {
                        Customer c = customerRepository.findById(customer1Id).orElseThrow();
                        Offering o = offeringRepository.findById(offeringId).orElseThrow();
                        appointmentRepository.save(Appointment.builder()
                                .business(testBusiness).customer(c).offering(o)
                                .employee(employeeUser).datetime(slot).durationMinutes(30)
                                .status(AppointmentStatus.CONFIRMED).createdBy("THREAD-A").build());
                    });
                } catch (Throwable t) { error1.set(t); }
            }, executor);

            CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> {
                try {
                    transactionTemplate.executeWithoutResult(s -> {
                        Customer c = customerRepository.findById(customer2Id).orElseThrow();
                        Offering o = offeringRepository.findById(offeringId).orElseThrow();
                        appointmentRepository.save(Appointment.builder()
                                .business(testBusiness).customer(c).offering(o)
                                .employee(employeeUser).datetime(slot).durationMinutes(30)
                                .status(AppointmentStatus.CONFIRMED).createdBy("THREAD-B").build());
                    });
                } catch (Throwable t) { error2.set(t); }
            }, executor);

            CompletableFuture.allOf(f1, f2).get();

            // Assert via JdbcTemplate (new connection): the failing thread left its
            // connection poisoned in PostgreSQL; any subsequent JPA call would fail
            // with "current transaction is aborted".
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM appointments " +
                    "WHERE employee_id = ? AND datetime = ? AND status = 'CONFIRMED'",
                    Long.class, employeeUser.getId(), slot);
            assertThat(count)
                    .as("exactly one appointment should survive the race")
                    .isEqualTo(1);

            // The losing thread must have failed with a constraint violation.
            boolean oneFailed = (error1.get() != null) ^ (error2.get() != null);
            assertThat(oneFailed).as("exactly one thread should fail with constraint violation").isTrue();
            Throwable t = error1.get() != null ? error1.get() : error2.get();
            assertThat(t).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("[3] CANCELLED is outside the index scope: cancel and rebook the same slot → success")
    void shouldAllowRebookAfterCancellation() {
        LocalDateTime slot = testDate.atTime(12, 0);

        // Each step runs in its own transaction so subsequent steps see the commit.
        Long firstId = transactionTemplate.execute(s -> {
            Customer c = customerRepository.findById(customer1Id).orElseThrow();
            Offering o = offeringRepository.findById(offeringId).orElseThrow();
            return appointmentRepository.save(Appointment.builder()
                    .business(testBusiness).customer(c).offering(o)
                    .employee(employeeUser).datetime(slot).durationMinutes(30)
                    .status(AppointmentStatus.CONFIRMED).createdBy("PANEL").build()).getId();
        });

        // Cancel the first appointment (moves outside the partial index WHERE clause).
        transactionTemplate.executeWithoutResult(s -> {
            Appointment a = appointmentRepository.findById(firstId).orElseThrow();
            a.setStatus(AppointmentStatus.CANCELLED);
            appointmentRepository.save(a);
        });

        // Create a new appointment in the same slot — must succeed.
        Long secondId = transactionTemplate.execute(s -> {
            Customer c = customerRepository.findById(customer2Id).orElseThrow();
            Offering o = offeringRepository.findById(offeringId).orElseThrow();
            return appointmentRepository.save(Appointment.builder()
                    .business(testBusiness).customer(c).offering(o)
                    .employee(employeeUser).datetime(slot).durationMinutes(30)
                    .status(AppointmentStatus.CONFIRMED).createdBy("PANEL").build()).getId();
        });

        assertThat(secondId).isNotNull();
        assertThat(secondId).isNotEqualTo(firstId);
    }

    @Test
    @DisplayName("[4] Different employees at the same datetime → both succeed (no conflict)")
    void shouldAllowDifferentEmployeesSameSlot() {
        LocalDateTime slot = testDate.atTime(13, 0);

        // Create a second employee with a matching schedule in its own transaction.
        com.vookedme.botmanager.auth.entity.User employee2 = transactionTemplate.execute(s -> {
            com.vookedme.botmanager.auth.entity.User u = userRepository.save(
                    com.vookedme.botmanager.auth.entity.User.builder()
                            .email("emp2-concurrency@test.com")
                            .passwordHash(passwordEncoder.encode(TEST_PASSWORD))
                            .name("Emp 2")
                            .role(employeeRole)
                            .business(testBusiness)
                            .isActive(true)
                            .build());
            employeeScheduleRepository.save(EmployeeSchedule.builder()
                    .user(u).business(testBusiness)
                    .dayOfWeek(testDate.getDayOfWeek().getValue())
                    .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                    .active(true).build());
            return u;
        });

        Long a1Id = transactionTemplate.execute(s -> {
            Customer c = customerRepository.findById(customer1Id).orElseThrow();
            Offering o = offeringRepository.findById(offeringId).orElseThrow();
            return appointmentRepository.save(Appointment.builder()
                    .business(testBusiness).customer(c).offering(o)
                    .employee(employeeUser).datetime(slot).durationMinutes(30)
                    .status(AppointmentStatus.CONFIRMED).createdBy("PANEL").build()).getId();
        });

        Long a2Id = transactionTemplate.execute(s -> {
            Customer c = customerRepository.findById(customer2Id).orElseThrow();
            Offering o = offeringRepository.findById(offeringId).orElseThrow();
            return appointmentRepository.save(Appointment.builder()
                    .business(testBusiness).customer(c).offering(o)
                    .employee(employee2).datetime(slot).durationMinutes(30)
                    .status(AppointmentStatus.CONFIRMED).createdBy("PANEL").build()).getId();
        });

        assertThat(a1Id).isNotNull();
        assertThat(a2Id).isNotNull();
        assertThat(a1Id).isNotEqualTo(a2Id);
    }

    @Test
    @DisplayName("[5] NULL employee_id is outside the index scope: two unassigned appointments at the same datetime → both succeed")
    void shouldAllowMultipleUnassignedAppointmentsAtSameSlot() {
        LocalDateTime slot = testDate.atTime(14, 0);

        Long a1Id = transactionTemplate.execute(s -> {
            Customer c = customerRepository.findById(customer1Id).orElseThrow();
            Offering o = offeringRepository.findById(offeringId).orElseThrow();
            return appointmentRepository.save(Appointment.builder()
                    .business(testBusiness).customer(c).offering(o)
                    .employee(null).datetime(slot).durationMinutes(30)
                    .status(AppointmentStatus.CONFIRMED).createdBy("PANEL").build()).getId();
        });

        Long a2Id = transactionTemplate.execute(s -> {
            Customer c = customerRepository.findById(customer2Id).orElseThrow();
            Offering o = offeringRepository.findById(offeringId).orElseThrow();
            return appointmentRepository.save(Appointment.builder()
                    .business(testBusiness).customer(c).offering(o)
                    .employee(null).datetime(slot).durationMinutes(30)
                    .status(AppointmentStatus.CONFIRMED).createdBy("PANEL").build()).getId();
        });

        // Both must be created — the partial unique index requires employee_id NOT NULL.
        // Capacity protection for unassigned appointments lives in the service layer
        // (validateAppointmentTime), outside the scope of the index guarantee.
        assertThat(a1Id).isNotNull();
        assertThat(a2Id).isNotNull();
    }
}
