package com.vookedme.botmanager.bot.domain;

/**
 * Canonical taxonomy of events derived from {@code Appointment} state
 * by {@code BotEventResolver}.
 *
 * <p><b>Frozen taxonomy</b> — 11 active types, no placeholders. The enum
 * declaration order matches appointment lifecycle — earlier values represent
 * earlier transitions. This ordering serves as a secondary tie-breaker in
 * {@code BotEventResolver.resolve()} when two events on the same appointment
 * share the same {@code occurredAt} timestamp.
 *
 * <p><b>Exhaustive coverage invariant</b>: every combination of
 * {@code (source, status, cancellationActor, cancellationSource,
 * cancellationRequestedActor)} where {@code source='BOT'} or
 * {@code cancellationRequestedActor='BOT'} must produce at least one event
 * from this enum, or trigger the fail-loud DEFAULT branch in the resolver.
 * This invariant is enforced by the exhaustive test suite in
 * {@code BotEventResolverTest}.
 *
 * <p><b>Adding new types</b>: adding a type requires a formal graduation
 * process — a demand-driven requirement, a resolver branch, a renderer
 * template, exhaustive tests, taxonomy documentation update, and a status
 * update. Pull requests that add types without all six artefacts are
 * rejected.
 */
public enum BotEventType {

    // ─── Creation events ───────────────────────────────────────────────────

    /**
     * Bot created an appointment in approval-required mode (status=PENDING).
     *
     * <p>Discriminator: {@code source=BOT AND (status=PENDING OR approved_at IS NOT NULL
     * OR pre-audit-columns fallback heuristic matches)}.
     *
     * <p>Pre-cutoff data (appointments created before the approval audit columns
     * were introduced) without an {@code approved_at} value lands here with
     * {@code metadata.pre_v69=true}.
     */
    BOT_PROPOSED,

    /**
     * Bot created an appointment in auto-confirm mode (status=CONFIRMED directly,
     * no owner approval required).
     *
     * <p>Discriminator: {@code source=BOT AND status!=PENDING AND approved_at IS NULL
     * AND NOT pre-audit-columns fallback AND NOT in cancel/reject path}.
     * Mutually exclusive with BOT_PROPOSED.
     */
    BOT_AUTO_CONFIRMED,

    // ─── Approval flow (approval-required mode) ────────────────────────────

    /**
     * Owner approved a PENDING bot-created appointment via the panel.
     *
     * <p>Discriminator: {@code source=BOT AND approved_at IS NOT NULL}.
     * Timestamp source: {@code appointment.approved_at} (approval audit column, exact).
     * Actor: {@code appointment.approved_by_user_id}.
     */
    OWNER_APPROVED,

    /**
     * Owner rejected a PENDING bot-created appointment via the panel.
     *
     * <p>Discriminator: {@code source=BOT AND status=CANCELLED AND
     * cancellationActor=OWNER AND cancellationSource=PANEL AND
     * approved_at IS NULL AND revoked_at IS NULL AND
     * cancellationRequestedActor IS NULL}.
     *
     * <p>The {@code cancellationRequestedActor IS NULL} check is essential —
     * it distinguishes this case (rejection of a PENDING proposal) from
     * OWNER_APPROVED_CANCEL (approval of a cancellation request), where the
     * same actor and source pattern may appear but a prior CR was involved.
     */
    OWNER_REJECTED,

    /**
     * The system timeout job expired a PENDING bot-created appointment that
     * was not approved or rejected within the configured approval window.
     *
     * <p>Discriminator: {@code source=BOT AND status=CANCELLED AND
     * cancellationActor=SYSTEM AND cancellationReason starts with
     * 'TIMEOUT_BOT_APPROVAL'}.
     */
    BOT_PENDING_EXPIRED,

    // ─── Cancellation request (CR) flow ───────────────────────────────────

    /**
     * A customer requested cancellation via the bot interface, producing a
     * cancellation request (status=CANCELLATION_REQUESTED) on a confirmed
     * appointment.
     *
     * <p>Discriminator: {@code cancellationRequestedActor=BOT AND
     * cancellationRequestedAt IS NOT NULL}.
     *
     * <p>Independent of the original appointment source — an owner-created
     * appointment can have a bot-initiated cancellation request if the
     * customer contacts via the bot to cancel an appointment originally
     * created via the panel.
     */
    BOT_CANCEL_REQUESTED,

    /**
     * Owner approved a cancellation request initiated by the bot.
     * Transition: CANCELLATION_REQUESTED → CANCELLED.
     *
     * <p>Discriminator: {@code cancellationRequestedActor=BOT AND
     * cancellationRequestDecidedAt IS NOT NULL AND status=CANCELLED}.
     *
     * <p>The 3-actor model is preserved: {@code cancellationActor=OWNER,
     * cancellationSource=PANEL}, but the discriminator uses the CR fields
     * rather than actor/source to correctly distinguish this case.
     */
    OWNER_APPROVED_CANCEL,

    /**
     * Owner rejected a cancellation request initiated by the bot.
     * Transition: CANCELLATION_REQUESTED → CONFIRMED (reverted to confirmed).
     *
     * <p>Discriminator: {@code cancellationRequestedActor=BOT AND
     * cancellationRequestDecidedAt IS NOT NULL AND status=CONFIRMED}.
     * {@code cancellationActor} and {@code cancellationSource} remain NULL
     * in this case (no cancellation was executed).
     */
    OWNER_REJECTED_CANCEL,

    /**
     * The system timeout job expired a CANCELLATION_REQUESTED row without
     * an owner decision within the configured CR resolution window. The
     * appointment reverts to CONFIRMED automatically.
     *
     * <p>Discriminator: {@code cancellationRequestExpiredAt IS NOT NULL AND
     * cancellationRequestDecidedAt IS NULL}.
     */
    CR_TIMEOUT_EXPIRED,

    /**
     * Bot cancelled an appointment directly (auto-confirm mode with a
     * customer-direct-cancel flow where no CR approval is required).
     *
     * <p>Discriminator: {@code status=CANCELLED AND cancellationActor=BOT AND
     * cancellationSource=BOT}.
     *
     * <p>Distinct from OWNER_APPROVED_CANCEL — that event has
     * {@code cancellationActor=OWNER} following CR approval.
     */
    BOT_CANCELLED,

    // ─── Emergency revoke ──────────────────────────────────────────────────

    /**
     * Emergency revoke: an appointment previously cancelled is restored to
     * CONFIRMED via the panel. Transition: CANCELLED → CONFIRMED.
     *
     * <p>Discriminator: {@code revoked_at IS NOT NULL AND status=CONFIRMED}.
     *
     * <p>Revoke audit preservation invariant: historical cancel fields
     * ({@code cancelledAt}, {@code cancellationActor}, {@code cancellationSource},
     * {@code cancellationReason}) are preserved on the row after revoke — they
     * record what happened before the revoke, not the current state.
     *
     * <p>The revoke filter in {@code BotEventResolver.resolve()} suppresses
     * cancel-derived events for this row to avoid presenting contradictory
     * "cancelled + revoked" events in the same feed. Historical lineage events
     * (BOT_PROPOSED, OWNER_APPROVED, BOT_CANCEL_REQUESTED, OWNER_REJECTED_CANCEL,
     * CR_TIMEOUT_EXPIRED) are preserved; only the events contradicting the
     * current CONFIRMED status are removed.
     */
    BOT_REVOKED
}
