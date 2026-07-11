package com.vookedme.botmanager.schedule.entity;

/**
 * Lifecycle states for {@link BlockedSlot} (ADR-002).
 *
 * <h3>Transition rules</h3>
 *
 * <p>Enforced by {@code BlockedSlotService.validateTransition}
 * *(published in a subsequent source batch)*:
 * <pre>
 *   REQUESTED → APPROVED   (actor: OWNER/ADMIN, not the original requester)
 *   REQUESTED → REJECTED   (actor: OWNER/ADMIN, requires rejection_reason)
 *   REQUESTED → CANCELLED  (actor: original requester withdrawing, or OWNER/ADMIN)
 *   APPROVED  → CANCELLED  (actor: OWNER/ADMIN, or EMPLOYEE on self-owned blocks)
 *   APPROVED  → EXPIRED    (actor: system — BlockedSlotExpirationJob)
 *   REJECTED  → terminal
 *   CANCELLED → terminal
 *   EXPIRED   → terminal
 * </pre>
 *
 * <h3>Calendar visibility rule</h3>
 *
 * <p>Only {@link #APPROVED} blocks affect availability queries. {@code REQUESTED}
 * blocks are invisible to the booking calendar; they appear only to the original
 * requester and to the OWNER/ADMIN approval queue.
 */
public enum BlockedSlotStatus {
    /** Submitted by EMPLOYEE; awaiting OWNER/ADMIN approval. Does NOT block the calendar. */
    REQUESTED,

    /** Active block. Affects calendar overlap queries. */
    APPROVED,

    /** OWNER/ADMIN rejected the request. Terminal. */
    REJECTED,

    /** Cancelled by the requester before approval, or by OWNER/ADMIN after approval. Terminal. */
    CANCELLED,

    /** Auto-expired by scheduled job: an APPROVED block whose {@code end_datetime ≤ now()}. Terminal. */
    EXPIRED
}
