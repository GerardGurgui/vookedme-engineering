package com.vookedme.botmanager.appointment;

import com.vookedme.botmanager.appointment.dto.AppointmentResponse;
import com.vookedme.botmanager.appointment.entity.Appointment;
import com.vookedme.botmanager.appointment.repository.AppointmentRepository;
import com.vookedme.botmanager.appointment.service.AppointmentService;
import com.vookedme.botmanager.employee.entity.EmployeeSchedule;
import com.vookedme.botmanager.employee.repository.EmployeeScheduleRepository;
import com.vookedme.botmanager.offering.entity.Offering;
import com.vookedme.botmanager.schedule.entity.Schedule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Art.9 GDPR data minimisation for the bot booking path (ADR-015).
 *
 * <p>Petrifies the invariant that appointments created by the bot
 * ({@link AppointmentService#createFromBot}) never persist free text in
 * {@code appointments.notes}, regardless of the content. This is the backend's
 * categorical enforcement of ADR-015's principle: the compliance boundary is
 * deterministic code, not model instruction-following.
 *
 * <h3>Why two test batteries (special-category vs. operational)</h3>
 *
 * <p>The strength of the guarantee is that the system does not attempt to
 * distinguish sensitive health disclosures from benign operational context —
 * it discards all bot-path free text categorically. This is more robust and
 * legally defensible than a keyword filter, which would have false positives
 * ("sports massage" vs. "injured muscle") and false negatives ("I hurt myself").
 * Both batteries must produce {@code notes == null}:
 * <ol>
 *   <li><b>Special-category Art.9 data</b> — health conditions, pregnancy,
 *       disability. The class of input the measure must obviously block.</li>
 *   <li><b>Benign operational context</b> — schedule preferences, arrival notes.
 *       Demonstrates that the discard is content-agnostic, not pattern-matched.</li>
 * </ol>
 *
 * <p>Each test additionally verifies that the minimisation does not break the
 * booking: the appointment is still created successfully; only the note is
 * discarded.
 *
 * <p>These tests run against a real PostgreSQL instance (Testcontainers) via
 * {@code BaseIntegrationTest} *(published in a subsequent source batch)*.
 * {@code AppointmentService}, {@code EmployeeScheduleRepository} are also
 * published in a subsequent source batch.
 */
class BotNotesMinimizationIT extends BaseIntegrationTest {

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private EmployeeScheduleRepository employeeScheduleRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @PersistenceContext
    private EntityManager em;

    private Offering testOffering;
    private LocalDate testDate;

    @BeforeEach
    void seedFixtures() {
        // Future date: satisfies @Future validation and guarantees slot availability.
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

        // Employee schedule so AUTO_CONFIRM auto-assignment and capacity
        // validation paths have at least one available candidate.
        employeeScheduleRepository.save(EmployeeSchedule.builder()
                .user(employeeUser)
                .business(testBusiness)
                .dayOfWeek(testDate.getDayOfWeek().getValue())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .active(true)
                .build());
    }

    /**
     * Calls the bot booking path with the given note and returns the persisted row.
     * The booking must succeed: minimisation must not break scheduling functionality.
     */
    private Appointment createFromBotWithNote(String note) {
        AppointmentResponse result = appointmentService.createFromBot(
                testBusiness.getInstance(),
                "+34699888777",
                "Test Customer",
                testDate.atTime(10, 0),
                testOffering.getId(),
                note);

        assertThat(result.getId())
                .as("The appointment must be created successfully; minimisation must not break the booking flow")
                .isNotNull();

        em.flush();
        em.clear();
        return appointmentRepository.findById(result.getId()).orElseThrow();
    }

    // ── Battery 1: special-category Art.9 data (health / disability) ──────────

    /**
     * LOCALE OUTPUT (Spanish): the note strings below are realistic Spanish-language
     * inputs that a customer would type in a WhatsApp conversation. They are
     * intentionally preserved as locale-representative test fixtures rather than
     * translated to English. Translating them would reduce the test's
     * representativeness of real user input.
     */
    @ParameterizedTest(name = "Art.9 special-category note discarded: \"{0}\"")
    @ValueSource(strings = {
            "Tengo cáncer y necesito la cita cuanto antes",
            "Estoy embarazada de tres meses",
            "Soy VIH positivo",
            "Estoy tomando antidepresivos",
            "Tengo una hernia lumbar",
            "Mi hijo tiene autismo",
            "Necesito la cita porque tengo ansiedad",
            "Voy en silla de ruedas, necesito acceso adaptado"
    })
    @DisplayName("Bot never persists special-category Art.9 notes")
    void botNeverPersistsSpecialCategoryNotes(String sensitiveNote) {
        Appointment saved = createFromBotWithNote(sensitiveNote);

        assertThat(saved.getNotes())
                .as("Bot-created appointment must not persist any free text (Art.9 GDPR); received: <%s>",
                        sensitiveNote)
                .isNull();
    }

    // ── Battery 2: benign operational context (demonstrates content-agnostic discard) ──

    @ParameterizedTest(name = "Benign operational note also discarded: \"{0}\"")
    @ValueSource(strings = {
            "Prefiero por la tarde",   // LOCALE OUTPUT (Spanish): "I prefer the afternoon"
            "Llegaré 10 minutos tarde", // "I'll be 10 minutes late"
            "Llamadme antes de la cita", // "Call me before the appointment"
            "Vengo con mi hija"          // "I'm coming with my daughter"
    })
    @DisplayName("Bot also discards benign operational free text (content-agnostic)")
    void botNeverPersistsBenignOperationalNotesEither(String operationalNote) {
        Appointment saved = createFromBotWithNote(operationalNote);

        assertThat(saved.getNotes())
                .as("No free text from the bot path is persisted, regardless of content; received: <%s>",
                        operationalNote)
                .isNull();
    }

    // ── Edge cases ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("null note input produces null notes (no NullPointerException)")
    void nullNoteInputYieldsNull() {
        Appointment saved = createFromBotWithNote(null);
        assertThat(saved.getNotes()).isNull();
    }

    @Test
    @DisplayName("Blank note input is also discarded")
    void blankNoteInputYieldsNull() {
        Appointment saved = createFromBotWithNote("   ");
        assertThat(saved.getNotes()).isNull();
    }
}
