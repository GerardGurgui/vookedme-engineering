package com.vookedme.botmanager.schedule.entity;

import com.vookedme.botmanager.business.entity.Business;
import com.vookedme.botmanager.common.entity.BaseEntity;
import com.vookedme.botmanager.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A time block that reserves capacity and prevents bookings during a specified
 * interval. Published in a subsequent source batch: {@code BlockedSlotService}
 * (transition validation), {@code BlockedSlotRepository} (overlap queries), and
 * the expiration job.
 *
 * <h3>State machine</h3>
 *
 * <p>The lifecycle is governed by {@link BlockedSlotStatus} per ADR-002. An
 * EMPLOYEE actor submits a request ({@code REQUESTED}); an OWNER/ADMIN approves
 * or rejects it. OWNER/ADMIN actors who create a block directly skip the
 * {@code REQUESTED} phase and enter {@code APPROVED} immediately.
 *
 * <h3>Calendar visibility</h3>
 *
 * <p>Only {@code APPROVED} blocks affect availability queries. A
 * {@code REQUESTED} block is invisible to the booking calendar; it appears only
 * to the requester and in the OWNER/ADMIN approval queue.
 *
 * <h3>Per-business employee permission toggle</h3>
 *
 * <p>Whether EMPLOYEE actors may submit block requests at all is governed by a
 * per-business flag ({@code allow_employee_block_requests}), evaluated by
 * {@code BlockedSlotPolicy} before the request is accepted.
 */
@Entity
@Table(name = "blocked_slots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BlockedSlot extends BaseEntity {

    /**
     * Optimistic locking version counter. Hibernate increments this on every
     * UPDATE; if two OWNER actors attempt to approve or cancel the same block
     * concurrently, the second operation receives
     * {@link org.springframework.orm.ObjectOptimisticLockingFailureException},
     * which the {@code GlobalExceptionHandler} maps to HTTP 409. Rows that
     * predate the version column start at {@code version = 0}; the first UPDATE
     * advances it to 1.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    /**
     * The employee this block applies to, or {@code null} if the block applies
     * to the entire business (no employee assigned, all capacity reserved).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private User employee;

    @Column(name = "start_datetime", nullable = false)
    private LocalDateTime startDatetime;

    @Column(name = "end_datetime", nullable = false)
    private LocalDateTime endDatetime;

    @Column(length = 255)
    private String reason;

    /**
     * The user who created this block. Nullable for legacy rows created before
     * the creator audit column was added. The delete-permission rule reads this
     * field to allow the creator to withdraw their own {@code REQUESTED} block;
     * legacy rows ({@code NULL}) fall back to OWNER/ADMIN-only permission.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private User createdByUser;

    // ── State machine (ADR-002) ────────────────────────────────────────────

    /**
     * Lifecycle state per ADR-002. Defaults to {@code APPROVED} for
     * OWNER/ADMIN direct-create paths and for all pre-state-machine legacy
     * rows (data migration set these to APPROVED retrospectively).
     * EMPLOYEE-originated requests start as {@code REQUESTED}.
     *
     * <p>Calendar overlap queries filter on {@code status = 'APPROVED'} —
     * only APPROVED blocks affect bookings. REQUESTED is invisible to the
     * booking calendar.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private BlockedSlotStatus status = BlockedSlotStatus.APPROVED;

    /**
     * When the block was requested. For OWNER/ADMIN direct-creates, this
     * collapses to the same instant as {@code approved_at} (no separate
     * REQUESTED phase). Populated on the create transition.
     */
    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    /** OWNER/ADMIN who approved (or auto-approved on direct create). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_user_id")
    private User approvedByUser;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /** OWNER/ADMIN who rejected. Mutually exclusive with approval fields. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rejected_by_user_id")
    private User rejectedByUser;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    /** Mandatory at the service layer when transitioning to {@code REJECTED}. */
    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    /** Whoever performed the cancellation (requester withdrawal or OWNER/ADMIN). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by_user_id")
    private User cancelledByUser;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /**
     * Deduplication flag for the urgent notification job. {@code true} once the
     * OWNER has been notified of an unresolved {@code BLOCK_REQUEST_URGENT}
     * alert for this request. The job filters on {@code urgent_notification_sent = false}
     * on each pass to avoid re-alerting for requests that remain {@code REQUESTED}
     * past the first notification window.
     *
     * <p>Defaults to {@code false}; flips to {@code true} after a successful
     * send. Never reset — the state machine does not allow re-entry to
     * {@code REQUESTED} from terminal states, so a stale {@code true} on an
     * APPROVED/REJECTED/CANCELLED/EXPIRED row is irrelevant (the job's query
     * filters by {@code status = REQUESTED}).
     */
    @Builder.Default
    @Column(name = "urgent_notification_sent", nullable = false)
    private Boolean urgentNotificationSent = false;
}
