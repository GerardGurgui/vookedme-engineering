package com.vookedme.botmanager.appointment.entity;

/**
 * Typed origin of an {@link Appointment} row — the formal enum counterpart
 * to the free-form {@link Appointment#getCreatedBy()} string.
 *
 * <p>Introduced to drive: (a) the approval queue filter
 * ({@code status=PENDING && source=BOT}), (b) the
 * {@code PendingApprovalTimeoutJob} (only acts on BOT appointments),
 * (c) the {@code BotPendingApprovalListener} (only fires for BOT-created
 * PENDING), and (d) the panel chip colour logic (PENDING+BOT amber vs
 * PENDING+PANEL yellow legacy).
 *
 * <p>The {@code created_by} string field continues to store the actor
 * identifier (email of OWNER/ADMIN/EMPLOYEE, or literal {@code "BOT"} /
 * {@code "PANEL"}). This enum is the parsed typed signal — a derived
 * classification, not a replacement.
 */
public enum AppointmentSource {

    /**
     * Created via the panel UI by OWNER / ADMIN / EMPLOYEE.
     * Default for all backfilled rows that predate this column
     * (except those with {@code created_by='BOT'}).
     */
    PANEL,

    /**
     * Created via the webhook flow
     * {@code POST /api/webhook/appointments/{instance}}
     * → {@code AppointmentService.createFromBot}. Bot mode branching
     * controls the initial status (CONFIRMED for AUTO_CONFIRM, PENDING
     * for APPROVAL_REQUIRED; TRIAGE delegates to BotRequest instead).
     */
    BOT,

    /**
     * Created via CSV import / migration (future use). Not yet wired —
     * reserved for the importer feature.
     */
    IMPORT,

    /**
     * Created via external API integration (future use, e.g. Google Calendar
     * sync, third-party booking platform). Not yet wired.
     */
    API
}
