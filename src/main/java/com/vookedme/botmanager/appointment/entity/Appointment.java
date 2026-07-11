package com.vookedme.botmanager.appointment.entity;

import com.vookedme.botmanager.auth.entity.User;
import com.vookedme.botmanager.business.entity.Business;
import com.vookedme.botmanager.customer.entity.Customer;
import com.vookedme.botmanager.offering.entity.Offering;
import com.vookedme.botmanager.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Core aggregate root — represents a single appointment booking in the
 * multi-tenant scheduling domain.
 *
 * <p><b>State machine.</b> The {@link AppointmentStatus} field drives a
 * six-state FSM. Legal transitions and transition guards are defined in
 * {@code docs/governance/state-machines.md} and enforced at the service
 * layer. The temporal boundary ({@link #isPast()}) divides the lifecycle
 * into two planes: the <em>operational plane</em> (future appointments —
 * booking, confirmation, cancellation handling) and the <em>closure plane</em>
 * (past appointments — COMPLETED, NO_SHOW, accounting). See ADR-011.
 *
 * <p><b>Optimistic locking.</b> The {@code @Version} field prevents lost
 * updates on concurrent panel edits of the same row. It does not prevent
 * concurrent INSERT double-booking — that is handled by the database-level
 * partial unique index {@code uk_appointments_employee_slot}
 * ({@code (employee_id, datetime)} scoped to active statuses). The
 * concurrency test suite verifies both guarantees independently.
 *
 * <p><b>Bot approval audit.</b> When an appointment is created via the bot
 * in APPROVAL_REQUIRED mode, it enters PENDING state and waits for an
 * owner decision. The {@code approved_at} / {@code approved_by_user_id} /
 * {@code approval_decision_source} triplet records that decision for audit.
 * The {@code revoked_at} / {@code revoked_by_user_id} pair records emergency
 * reversals (CANCELLED → CONFIRMED) as described in
 * {@code docs/governance/state-machines.md}.
 */
@Entity
@Table(name = "appointments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Appointment extends BaseEntity {

    /** Optimistic locking — protects concurrent panel edits of the same row. */
    @Version
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /**
     * Assigned employee. Nullable — some businesses allow unassigned
     * appointments that are claimed later. The partial unique index
     * {@code uk_appointments_employee_slot} excludes NULL employee_id rows,
     * so multiple unassigned appointments can coexist at the same datetime.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private User employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offering_id")
    private Offering offering;

    @Column(name = "datetime", nullable = false)
    private LocalDateTime datetime;

    @Builder.Default
    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes = 30;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AppointmentStatus status = AppointmentStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /** Free-text actor identifier: {@code "BOT"}, {@code "PANEL"}, or the email of the creating user. */
    @Column(name = "created_by", length = 50)
    private String createdBy;

    /**
     * Webhook idempotency key — receives the WhatsApp message id (wamid) via
     * n8n on {@code POST /api/webhook/appointments/{instance}}.
     *
     * <p>Enforced by UNIQUE PARTIAL INDEX
     * {@code uk_appointments_webhook_event_id} scoped per
     * {@code (business_id, webhook_event_id) WHERE webhook_event_id IS NOT NULL}.
     * NULL for panel-created appointments — legacy rows coexist freely because
     * they are excluded from the uniqueness constraint by the WHERE clause.
     *
     * <p>Format: VARCHAR(64). No CHECK constraint — the backend is agnostic to
     * the specific wamid format (Evolution API beta versions may substitute UUIDs
     * when the Meta message_id is absent).
     */
    @Column(name = "webhook_event_id", length = 64)
    private String webhookEventId;

    /**
     * Typed origin classification — the formal enum counterpart to the
     * {@link #createdBy} string. Drives: the panel chip colour
     * (PENDING+BOT amber vs PENDING+PANEL yellow legacy), the approval queue
     * filter ({@code status=PENDING && source=BOT}), the
     * {@code PendingApprovalTimeoutJob} (only acts on {@code source=BOT}),
     * and the {@code BotPendingApprovalListener}.
     *
     * <p>Default {@link AppointmentSource#PANEL} via {@code @Builder.Default}
     * covers panel-created paths that do not explicitly set a source.
     * {@code createFromBot} explicitly sets {@code source=BOT}.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private AppointmentSource source = AppointmentSource.PANEL;

    @Builder.Default
    @Column(name = "reschedule_count", nullable = false)
    private Integer rescheduleCount = 0;

    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    @Builder.Default
    @Column(nullable = false)
    private Boolean paid = false;

    /**
     * Audit for manual paid-status toggles only. Auto-paid flips driven by
     * status transitions (PENDING/CONFIRMED → COMPLETED or reverts) leave
     * these NULL. Operational dispute resolution record — not fiscal data.
     */
    @Column(name = "paid_changed_by_user_id")
    private Long paidChangedByUserId;

    @Column(name = "paid_changed_at")
    private LocalDateTime paidChangedAt;

    /**
     * Optional free-text reason supplied when manually toggling paid status
     * (e.g. "Entry error"). NULL when no reason was provided or when the
     * flip was auto-triggered by a status transition.
     */
    @Column(name = "paid_change_reason", columnDefinition = "TEXT")
    private String paidChangeReason;

    /**
     * Optional operational payment-method tag. Descriptive label — not a
     * fiscal field. NULL when unpaid, when the method was not supplied
     * (auto-paid fallback), or on appointments that predate this column.
     * See {@link PaymentMethod}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 16)
    private PaymentMethod paymentMethod;

    // ==================== Cancellation metadata ====================

    /**
     * Wall-clock moment the appointment transitioned to
     * {@link AppointmentStatus#CANCELLED}. NULL while the appointment is
     * still live or in CANCELLATION_REQUESTED. Distinct from
     * {@link com.vookedme.botmanager.common.entity.BaseEntity#getUpdatedAt()},
     * which tracks the most recent mutation of any kind.
     */
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /**
     * Who triggered the final cancellation: {@code OWNER}, {@code EMPLOYEE},
     * {@code BOT}, or {@code SYSTEM}. NULL while still live.
     */
    @Column(name = "cancellation_actor", length = 20)
    private String cancellationActor;

    /**
     * Channel through which the cancellation happened: {@code PANEL},
     * {@code WHATSAPP}, {@code JOB}, or {@code API}.
     */
    @Column(name = "cancellation_source", length = 30)
    private String cancellationSource;

    /**
     * Free-text reason for the final cancellation. Minimum 6 characters
     * when supplied. NULL acceptable when the owner cancelled without
     * providing an explanation.
     */
    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    /**
     * Set by the {@code CancellationRequestTimeoutJob} when a
     * {@code CANCELLATION_REQUESTED} row expires without an owner decision.
     * The {@code status} reverts to {@code CONFIRMED}, but this column
     * preserves the audit trail that a request existed and expired.
     */
    @Column(name = "cancellation_request_expired_at")
    private LocalDateTime cancellationRequestExpiredAt;

    // ==================== Cancellation request lifecycle ====================

    /**
     * Moment the employee submitted the cancellation request
     * (CONFIRMED → CANCELLATION_REQUESTED). Drives the timeout job filter.
     */
    @Column(name = "cancellation_requested_at")
    private LocalDateTime cancellationRequestedAt;

    /**
     * Actor who submitted the request — {@code EMPLOYEE} (panel path),
     * {@code BOT} (customer-initiated cancel via APPROVAL_REQUIRED mode),
     * or {@code OWNER} (future).
     *
     * <p>Nullable when the actor is {@code BOT} (the bot has no User
     * identity or JWT). For EMPLOYEE and future OWNER paths, this is the
     * FK to {@code users.id}. Drives self-approval prevention and the
     * withdraw creator-check — both no-ops when the actor is BOT (no
     * user to compare against).
     */
    @Column(name = "cancellation_requested_by_user_id")
    private Long cancellationRequestedByUserId;

    /**
     * Discriminator for who originated the cancellation request:
     * {@code "EMPLOYEE"} (default — backfilled for pre-existing CR rows),
     * {@code "BOT"} (customer cancellation via APPROVAL_REQUIRED mode),
     * or {@code "OWNER"} (future).
     *
     * <p>Distinguishes BOT-initiated CR (no user id) from EMPLOYEE-initiated
     * CR (user id present). Used by listeners to route notifications with the
     * correct framing: EMPLOYEE-CR notifies the owner about an employee's
     * request; BOT-CR notifies the owner about a customer's request.
     *
     * <p>NULL for rows that are not in a cancellation request state.
     */
    @Column(name = "cancellation_requested_actor", length = 20)
    private String cancellationRequestedActor;

    /** Free-text reason supplied by the requestor. Minimum 6 characters, required at the service layer. */
    @Column(name = "cancellation_request_reason", length = 500)
    private String cancellationRequestReason;

    /** Moment the owner approved or rejected the request, or the system expired it. */
    @Column(name = "cancellation_request_decided_at")
    private LocalDateTime cancellationRequestDecidedAt;

    /**
     * Owner who made the decision. NULL when SYSTEM (job) expired the
     * request, or when the bot directly cancelled while in
     * CANCELLATION_REQUESTED (implicit approval — the customer is the
     * effective decider).
     */
    @Column(name = "cancellation_request_decided_by_user_id")
    private Long cancellationRequestDecidedByUserId;

    /**
     * Owner-supplied reason on REJECT (required, minimum 6 characters).
     * On APPROVE this stays NULL — the decision inherits
     * {@link #cancellationRequestReason}. Persisted on the row so the
     * audit trail survives notification archival.
     */
    @Column(name = "cancellation_request_decision_reason", length = 500)
    private String cancellationRequestDecisionReason;

    // ==================== Historical snapshots ====================

    /**
     * Offering name at create-time, frozen for historical reports.
     * The frontend falls back to the current {@code offering.name} when NULL
     * (legacy rows). Set in {@code AppointmentService.create} and
     * {@code AppointmentService.createFromBot}.
     */
    @Column(name = "service_name_snapshot", length = 100)
    private String serviceNameSnapshot;

    /**
     * Employee name at create-time, frozen for historical reports.
     * NULL when no employee was assigned at creation, or for legacy rows
     * that predate this column.
     */
    @Column(name = "employee_name_snapshot", length = 100)
    private String employeeNameSnapshot;

    // ==================== Bot approval and revoke audit ====================

    /**
     * Timestamp of the owner's approval of a PENDING+BOT appointment.
     * Set by {@code AppointmentService.approveBotPending} when an
     * OWNER/ADMIN approves the appointment. NULL for:
     * <ul>
     *   <li>Non-bot appointments ({@code source != BOT})</li>
     *   <li>PENDING+BOT appointments not yet approved</li>
     *   <li>Appointments approved before this column was added
     *       (handled by a pre-population fallback heuristic in
     *       {@code BotEventResolver})</li>
     * </ul>
     *
     * <p>Paired with {@link #approvedByUserId} following the L2 audit
     * pattern (same pattern as the {@code paid_changed_*} columns).
     */
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /**
     * FK to the OWNER/ADMIN user who executed the approval.
     * NULL when {@link #approvedAt} is NULL (pair invariant).
     */
    @Column(name = "approved_by_user_id")
    private Long approvedByUserId;

    /**
     * Origin of the approval decision. In the current implementation only
     * {@link ApprovalDecisionSource#OWNER_PANEL} is used; other values are
     * reserved for future flows (see {@link ApprovalDecisionSource} Javadoc).
     * NULL when {@link #approvedAt} is NULL.
     *
     * <p>A DB CHECK constraint enforces enum-value parity.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_decision_source", length = 30)
    private ApprovalDecisionSource approvalDecisionSource;

    /**
     * FK to the OWNER/ADMIN user who rejected a PENDING+BOT appointment
     * (PENDING+BOT → CANCELLED) or performed a direct cancel of CONFIRMED
     * (future). NULL when cancelled by BOT/SYSTEM (no user identity).
     * Fills the audit gap for OWNER_REJECTED events in {@code BotEventResolver}.
     */
    @Column(name = "cancelled_by_user_id")
    private Long cancelledByUserId;

    /**
     * Timestamp of an emergency revoke (CANCELLED → CONFIRMED), as defined
     * in {@code docs/governance/state-machines.md §1 reversal exception}.
     *
     * <p>The cancel fields ({@code cancelledAt}, {@code cancellationActor},
     * {@code cancellationSource}, {@code cancellationReason},
     * {@link #cancelledByUserId}) are <b>preserved</b> on revoke — they
     * describe a real historical event. {@code BotEventResolver} emits a
     * {@code BOT_REVOKED} event when {@code revoked_at IS NOT NULL AND
     * status == CONFIRMED}, and skips cancel-derived events for that row.
     */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /**
     * FK to the OWNER/ADMIN user who executed the emergency revoke.
     * Paired with {@link #revokedAt} — updated in the same transaction.
     */
    @Column(name = "revoked_by_user_id")
    private Long revokedByUserId;

    // ==================== Derived helpers ====================

    /**
     * Returns {@code true} if this row was expired by the
     * {@code PendingApprovalTimeoutJob}: status CANCELLED, cancelled by
     * SYSTEM, with reason {@code TIMEOUT_BOT_APPROVAL}.
     *
     * <p>Semantically distinct from owner-rejected CANCELLED rows: no
     * one decided to cancel — the approval deadline elapsed. The panel
     * surfaces this via a "Expired" tab and a grey badge instead of
     * the red "cancelled" badge.
     *
     * <p>Do not revoke this row back to CONFIRMED — the slot may already
     * be taken. Create a new appointment for the customer instead.
     *
     * <p>Cross-references:
     * {@code docs/governance/destructive-actions.md} (Appointment cancel
     * sub-entry for TIMEOUT_BOT_APPROVAL),
     * {@code docs/governance/state-machines.md} (asymmetry note vs.
     * {@code CancellationRequestTimeoutJob}).
     */
    public boolean isBotApprovalExpired() {
        return this.status == AppointmentStatus.CANCELLED
                && "SYSTEM".equals(this.cancellationActor)
                && "TIMEOUT_BOT_APPROVAL".equals(this.cancellationReason);
    }

    /**
     * ADR-011 — temporal boundary of the appointment. Returns {@code true}
     * when {@code datetime} is no longer in the future (including the exact
     * start instant: an operational decision made exactly at the appointment
     * time arrives too late).
     *
     * <p>This is a derived state, not materialised — an explicit design
     * decision (ADR-011): the condition is a pure function of existing data;
     * a background job that flipped rows would introduce an inconsistency
     * window. Same pattern as {@link #isBotApprovalExpired()}: no consumer
     * should re-implement {@code datetime.isBefore(now())} inline — always
     * use this helper.
     *
     * <p>Divides the appointment lifecycle into two planes (ADR-011): the
     * <em>operational plane</em> (before — book, decide, remind, reschedule)
     * and the <em>closure plane</em> (after — COMPLETED/NO_SHOW/CANCELLED
     * accounting).
     */
    public boolean isPast() {
        return !this.datetime.isAfter(java.time.LocalDateTime.now());
    }
}
