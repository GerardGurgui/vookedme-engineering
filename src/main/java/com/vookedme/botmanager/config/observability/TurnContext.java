package com.vookedme.botmanager.config.observability;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Static MDC accessor for the conversational turn correlation identifier (ADR-012).
 *
 * <p><b>Why MDC rather than {@code @RequestScope}.</b> Populating a {@code @RequestScope}
 * bean from a servlet filter depends on {@code RequestContextHolder} being bound during
 * the filter chain — an ordering dependency that is fragile under Spring auto-configuration
 * and can produce a {@code ScopeNotActiveException} or, worse, a silent no-op where
 * {@code turn_id} is always null in the audit log. MDC is a plain thread-local, always
 * available on the filter thread, and the filter thread is the same thread that runs the
 * {@code @Transactional} business method and the synchronous {@code @EventListener} that
 * writes the audit row (committed ⟺ audited). No availability failure mode exists in this
 * design. In non-HTTP contexts (scheduler) {@code MDC.get} returns null, producing a null
 * turn identifier — which is the correct behaviour.
 *
 * <p><b>Thread safety and listener design.</b> The turn identifier is set explicitly on
 * the {@link com.vookedme.botmanager.common.event.AppointmentEvent} at publication time.
 * The audit listener reads the value from the event, never from MDC directly, preserving
 * the "no thread-local for audit attribution" design intent for the listener path while
 * still using MDC for log pattern correlation ({@code %X{turnId}}).
 *
 * <p><b>Forensic semantics.</b> {@code turn_id} is persisted to {@code audit_log} only
 * when the current request carried a real {@code X-Turn-Id} header
 * ({@link Source#HEADER}). A synthetic fallback identifier — generated when the header is
 * absent — is used for intra-request log correlation only and is never persisted.
 * This invariant is enforced by {@link #forensicTurnId()}: non-null return value ⟺
 * real n8n conversational turn; the backend never fabricates forensic turn identifiers.
 */
public final class TurnContext {

    /** MDC key for the turn identifier — consumed by {@code %X{turnId}} in log patterns. */
    public static final String MDC_TURN_ID = "turnId";

    /** MDC key for the origin of the turn identifier (HEADER vs SYNTHETIC) — governs forensic persistence. */
    private static final String MDC_TURN_ID_SOURCE = "turnIdSource";

    /** Origin of the turn identifier for the current request thread. */
    public enum Source { HEADER, SYNTHETIC }

    private TurnContext() {
        // Static accessor — no instances.
    }

    /**
     * Binds the turn identifier to the current thread (called by
     * {@link TurnCorrelationFilter} at the start of each webhook request). Idempotent on
     * the thread: a {@link #clear()} call in the filter's {@code finally} block prevents
     * MDC leaks caused by servlet thread-pool reuse.
     */
    public static void bind(UUID turnId, Source source) {
        MDC.put(MDC_TURN_ID, turnId.toString());
        MDC.put(MDC_TURN_ID_SOURCE, source.name());
    }

    /** Removes the turn identifier from the current thread (called by the filter in {@code finally}). */
    public static void clear() {
        MDC.remove(MDC_TURN_ID);
        MDC.remove(MDC_TURN_ID_SOURCE);
    }

    /**
     * Returns the forensic turn identifier — present only when the current request carried
     * a valid {@code X-Turn-Id} header ({@link Source#HEADER}). Returns {@code null} for
     * synthetic fallback identifiers, absent or malformed headers, and non-HTTP contexts
     * such as the scheduler. This is the value written to {@code audit_log.turn_id}.
     */
    public static UUID forensicTurnId() {
        if (!Source.HEADER.name().equals(MDC.get(MDC_TURN_ID_SOURCE))) {
            return null;
        }
        String raw = MDC.get(MDC_TURN_ID);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
