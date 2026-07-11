package com.vookedme.botmanager.appointment.entity;

/**
 * Origin of an approval decision on a {@code PENDING+BOT} appointment.
 *
 * <p>Stored as {@code VARCHAR(30)} in
 * {@code appointments.approval_decision_source} via
 * {@code @Enumerated(EnumType.STRING)}. A database CHECK constraint
 * enforces enum-value parity.
 *
 * <p>In the current implementation, only {@link #OWNER_PANEL} is written
 * by the service layer (see {@code AppointmentService.approveBotPending}).
 * The remaining three values are reserved for future flows:
 *
 * <ul>
 *   <li>{@link #AUTO_TIMEOUT} — future auto-approval when an owner fails to
 *       respond within a configurable grace window (not yet implemented;
 *       current behaviour is cancellation on timeout).</li>
 *   <li>{@link #SYSTEM_RECOVERY} — future backfill / data-repair operations
 *       (e.g. retroactively marking historical approvals post-migration).</li>
 *   <li>{@link #FUTURE_SUPERVISED} — Supervised bot mode approval path
 *       (owner approves messages individually; appointments get this source
 *       when confirmed through that flow).</li>
 * </ul>
 *
 * <p>Appointments that predate this column have
 * {@code approval_decision_source IS NULL}. The {@code BotEventResolver}
 * treats NULL as "no decision source recorded" and applies a pre-population
 * fallback heuristic.
 */
public enum ApprovalDecisionSource {

    /**
     * Current live value. Owner clicked "Approve" in the bot-approvals panel
     * surface (or via the
     * {@code POST /api/businesses/{businessId}/appointments/{id}/approve-bot-pending}
     * endpoint). Resolves to an {@code OWNER_APPROVED} event in
     * {@code BotEventResolver}.
     */
    OWNER_PANEL,

    /**
     * Future (not currently implemented): auto-approval when a configurable
     * owner-response timeout elapses without a decision. The existing timeout
     * job cancels the PENDING+BOT row; this value is reserved for a
     * hypothetical future "lenient timeout" mode that auto-approves instead.
     * Do not consume.
     */
    AUTO_TIMEOUT,

    /**
     * Future (not currently implemented): backfill / data-repair operations.
     * Reserved for migrations that retroactively populate {@code approved_at}
     * for pre-population appointments, distinguishing them from owner-decided
     * approvals.
     */
    SYSTEM_RECOVERY,

    /**
     * Future (not currently implemented): Supervised bot mode approval path.
     * Owner approves bot messages individually; if the conversation results
     * in an appointment, this value marks it as approved via the supervised
     * flow.
     */
    FUTURE_SUPERVISED
}
