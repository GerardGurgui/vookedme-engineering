package com.vookedme.botmanager.common.event;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Mutation event published on the Spring application event bus after every state change
 * in the appointment domain. Each field exists to allow a downstream listener to make a
 * routing or filtering decision without re-loading the appointment entity from the database.
 *
 * <p><b>Field evolution.</b> The event started with the minimal mutation context:
 * {@code type}, {@code appointmentId}, {@code businessId}, and the denormalised customer
 * and offering details needed for outbound notifications. Fields were added incrementally
 * as listeners needed to discriminate paths that share the same event {@code type}:
 *
 * <ul>
 *   <li>{@code triggeredBy} and {@code actorUserId} — actor attribution for forensic audit,
 *       captured at publication time via explicit parameter (not thread-local); persisted
 *       to {@code appointment_audit_log} by {@link AppointmentAuditListener}.</li>
 *   <li>{@code source} — typed origin of the appointment (PANEL or BOT). Allows listeners
 *       to activate only on bot-origin events without a database round-trip per event.</li>
 *   <li>{@code cancellationRequestedActor} — discriminates who initiated a cancellation
 *       request (EMPLOYEE vs CUSTOMER via bot vs OWNER), allowing notification listeners
 *       to route to the correct recipient with the appropriate copy.</li>
 *   <li>{@code correlationId} — UUID that groups events produced by the same bulk business
 *       operation (e.g., cancelling all appointments when a business closes a day).
 *       Notification listeners use it to suppress individual notifications in favour of a
 *       single bulk summary directed at the owner.</li>
 *   <li>{@code previousStatus} — the appointment's FSM state before the transition. Allows
 *       listeners receiving a {@link Type#CONFIRMED} event to distinguish a pending booking
 *       approval from a cancellation request rejection — two semantically different outcomes
 *       that share the same destination state.</li>
 *   <li>{@code turnId} — conversational turn identifier from the n8n orchestrator's
 *       {@code X-Turn-Id} header. Non-null only for mutations inside a real bot webhook
 *       request; persisted to {@code audit_log.turn_id} for forensic traceability. Null
 *       for panel, scheduler, and system-origin mutations.</li>
 * </ul>
 *
 * <p>The event is immutable (all fields final, built via Lombok {@code @Builder}). Actor
 * attribution ({@code triggeredBy}, {@code actorUserId}) is populated by the service layer
 * that publishes the event — never derived from thread-local context by the listener — so
 * attribution is correct across synchronous, asynchronous, and batch processing paths.
 *
 * @see AppointmentAuditListener — synchronous forensic listener that persists this event
 *     to {@code appointment_audit_log} atomically within the same transaction
 * @see com.vookedme.botmanager.common.event.SourceActor — actor type enum used by
 *     {@code triggeredBy}
 */
@Getter
@Builder
public class AppointmentEvent {

    /**
     * Type of appointment mutation that triggered this event.
     *
     * <p>Listeners that activate on multiple event types use this field to route to
     * the appropriate notification or audit path.
     */
    public enum Type {
        CREATED, CONFIRMED, CANCELLED, COMPLETED, NO_SHOW, CANCELLATION_REQUESTED, RESCHEDULED,
        EMPLOYEE_ASSIGNED, EMPLOYEE_UNASSIGNED,
        /** ADR-002 — payment flag toggle on a COMPLETED appointment (audit path). */
        PAID_TOGGLED,
    }

    private final Type type;

    /**
     * ADR-002 — actor who originated the mutation, captured at the point of event
     * publication (explicit parameter, not thread-local). Reuses the {@link SourceActor}
     * enum. Defensive fallback to {@code SYSTEM} if not resolved at the call site.
     * Persisted to {@code triggered_by} by the audit listener.
     */
    private final SourceActor triggeredBy;

    /**
     * ADR-002 — the authenticated {@code User.id} when
     * {@code triggeredBy ∈ {OWNER, ADMIN, EMPLOYEE}}; {@code null} for
     * CUSTOMER / BOT / SYSTEM / SCHEDULER (these are not panel users). The
     * {@code chk_audit_actor_user_id} database constraint enforces this invariant at the
     * storage layer; the audit listener enforces it redundantly at the application layer.
     */
    private final Long actorUserId;

    private final Long appointmentId;
    private final Long businessId;
    private final String businessInstance;
    private final String customerName;
    private final String customerPhone;
    private final String offeringName;
    private final LocalDateTime datetime;
    private final String status;

    /** Cancellation reason text; present only on CANCELLED / CANCELLATION_REQUESTED events. */
    private final String reason;

    /**
     * Typed origin of the appointment — a stringified {@code AppointmentSource} value
     * (PANEL, BOT, IMPORT, API). Carried in the event so that listeners can filter by
     * origin without re-loading the appointment entity from the database.
     *
     * <p>A listener that should activate only on bot-created pending bookings checks
     * {@code type == CREATED && "BOT".equals(source) && "PENDING".equals(status)}.
     * Without this field, every CREATED listener would require a database round-trip to
     * determine the appointment origin.
     *
     * <p>Backwards-compatible: null is accepted from callers that do not set this field;
     * listeners must handle null defensively.
     */
    private final String source;

    /**
     * Actor who initiated a cancellation request — populated only on
     * {@link Type#CANCELLATION_REQUESTED} events. Possible values:
     *
     * <ul>
     *   <li>{@code "EMPLOYEE"} — an employee requested cancellation through the panel</li>
     *   <li>{@code "BOT"} — a customer requested cancellation via the WhatsApp bot</li>
     *   <li>{@code "OWNER"} — reserved for owner-initiated cancellation requests</li>
     * </ul>
     *
     * <p>Allows notification listeners to route to the correct recipient: an
     * employee-initiated request notifies the owner; a customer-initiated request via bot
     * notifies the owner with different notification copy. Null for all other event types.
     */
    private final String cancellationRequestedActor;

    // Populated only for EMPLOYEE_ASSIGNED / EMPLOYEE_UNASSIGNED events.
    private final Long employeeId;
    private final String employeeName;
    private final String previousEmployeeName;

    /**
     * UUID grouping events produced by the same bulk business operation — for example,
     * cancelling all appointments when a business closes a working day. When non-null,
     * notification listeners targeting the business owner can suppress individual-appointment
     * notifications in favour of a single bulk summary. Reminder notifications directed at
     * customers are not suppressed (different recipient, handled by a separate listener path).
     *
     * <p>Backwards-compatible: null for non-bulk events (the majority). Listeners must
     * handle null defensively and follow the standard single-notification path.
     */
    private final UUID correlationId;

    /**
     * Conversational turn identifier from the n8n orchestrator's {@code X-Turn-Id} request
     * header. Non-null only when the mutation occurred inside a webhook request that carried
     * the header (real bot turns); null for panel, scheduler, and system-origin mutations.
     *
     * <p>Persisted to {@code audit_log.turn_id} by {@link AppointmentAuditListener} as
     * forensic observability metadata (ADR-012 compatible: observability field, not
     * conversational state). Forensic invariant: non-null ⟺ real n8n turn; the backend
     * never synthesises forensic turn identifiers.
     *
     * <p>Semantically distinct from {@link #correlationId}, which groups bulk business
     * operations rather than conversational turns.
     */
    private final UUID turnId;

    /**
     * FSM status of the appointment before the transition that published this event. Allows
     * listeners to discriminate semantically different paths that share the same destination
     * state:
     *
     * <ul>
     *   <li>{@code "PENDING"} → a pending booking was confirmed by the owner. The
     *       confirmation is communicated to the customer via the bot approval notification
     *       path. Reminder listeners should suppress a redundant confirmation reminder to
     *       avoid duplicate customer communication.</li>
     *   <li>{@code "CANCELLATION_REQUESTED"} → a cancellation request was rejected or
     *       expired; the appointment returns to CONFIRMED. A confirmation notification is
     *       the only communication the customer will receive for this transition; a reminder
     *       should be scheduled.</li>
     *   <li>{@code null} — backwards-compatible; set only where its value is a meaningful
     *       discriminator. Listeners must not assume any specific path when this field is
     *       null.</li>
     * </ul>
     */
    private final String previousStatus;
}
