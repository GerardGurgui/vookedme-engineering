package com.vookedme.botmanager.common.event;

/**
 * ADR-002 — actor that originated an appointment mutation, passed explicitly
 * to {@code publishAppointmentEvent(...)} (explicit parameter, NOT ThreadLocal).
 * The audit log persists this in {@code triggered_by} + {@code actor_user_id}.
 *
 * <p>Invariant (enforced by the {@code chk_audit_actor_user_id} DB constraint):
 * {@code userId} is non-null <em>only</em> for authenticated panel roles
 * (OWNER/ADMIN/EMPLOYEE); {@code null} for CUSTOMER/BOT/SYSTEM/SCHEDULER
 * (these are not {@code User} entities in the domain).
 *
 * @param type   origin of the action ({@link SourceActor})
 * @param userId id of the authenticated {@code User}, or {@code null}
 */
public record EventActor(SourceActor type, Long userId) {

    /** Bot channel — the action is attributed to the end customer (no User id). */
    public static EventActor bot() {
        return new EventActor(SourceActor.CUSTOMER, null);
    }

    /** {@code @Scheduled} job (timeouts) — no human actor. */
    public static EventActor scheduler() {
        return new EventActor(SourceActor.SCHEDULER, null);
    }

    /** Backend reaction / maintenance — no human actor. Defensive fallback. */
    public static EventActor system() {
        return new EventActor(SourceActor.SYSTEM, null);
    }
}
