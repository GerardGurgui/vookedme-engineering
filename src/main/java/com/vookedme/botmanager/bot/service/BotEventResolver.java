package com.vookedme.botmanager.bot.service;

import com.vookedme.botmanager.appointment.entity.Appointment;
import com.vookedme.botmanager.appointment.entity.AppointmentSource;
import com.vookedme.botmanager.appointment.entity.AppointmentStatus;
import com.vookedme.botmanager.bot.domain.BotEvent;
import com.vookedme.botmanager.bot.domain.BotEventType;
import com.vookedme.botmanager.config.observability.ObservabilityHelper;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PURE function: derives {@link BotEvent}s from {@link Appointment} state.
 *
 * <p><b>Purity guarantees</b>:
 * <ul>
 *   <li>No {@code @Service} / {@code @Component} / {@code @Autowired} annotations</li>
 *   <li>No repository fields</li>
 *   <li>No clock access ({@code LocalDateTime.now()} / {@code Instant.now()})</li>
 *   <li>No mutable state — all methods are static</li>
 *   <li>No masking logic (lives in {@code BotPhoneMaskingService})</li>
 *   <li>No narrative rendering (lives in {@code BotNarrativeRenderer})</li>
 *   <li>No actor name resolution (lives in the impure adapter layer)</li>
 *   <li>Deterministic: same appointment state → same {@code List<BotEvent>}</li>
 * </ul>
 *
 * <p>{@code Slf4j} is used only for the DEFAULT-branch fail-loud canary (see the
 * exhaustive guard at the end of {@link #resolve}). Logging is technically a
 * side effect but is required for invariant enforcement; tests verify the call
 * happens and treat the output as observation-only. A Mockito spy does not count
 * as mutation of resolver state.
 *
 * <p>Note: {@code ObservabilityHelper} is a background observability utility
 * referenced by the DEFAULT-branch canary. It will be published in a subsequent
 * source batch.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * List<BotEvent> events = BotEventResolver.resolve(appointment);
 * }</pre>
 *
 * <p>The caller (impure adapter layer) is responsible for:
 * fetching the appointment with required associations loaded, applying
 * query-time filtering, resolving actor user names from the user repository,
 * and projecting to audit or activity DTOs.
 */
@Slf4j
public final class BotEventResolver {

    // ────────────────────────────────────────────────────────────────────
    // Sentinel constants — keep magic strings out of branch conditions
    // ────────────────────────────────────────────────────────────────────

    private static final String ACTOR_BOT = "BOT";
    private static final String ACTOR_OWNER = "OWNER";
    private static final String ACTOR_SYSTEM = "SYSTEM";
    private static final String SOURCE_PANEL = "PANEL";
    private static final String SOURCE_BOT = "BOT";
    private static final String REASON_PREFIX_TIMEOUT_BOT_APPROVAL = "TIMEOUT_BOT_APPROVAL";

    /**
     * Cutoff for the pre-approval-audit-columns fallback heuristic.
     *
     * <p>Appointments created before this instant with {@code approved_at IS NULL}
     * are classified as "approved before audit columns were available" when the
     * status is CONFIRMED and no rejection or cancellation lineage is present.
     *
     * <p>Value: 2026-05-23 12:00 Madrid wall-clock — the deployment that introduced
     * the approval audit columns ({@code approved_at}, {@code approved_by_user_id})
     * was confirmed operational at noon on 2026-05-23. The 12-hour margin after
     * midnight covers the window between 00:00 and actual deploy time, preventing
     * rows created in that window from being incorrectly classified as
     * BOT_AUTO_CONFIRMED when they were genuinely awaiting owner approval at
     * creation time.
     *
     * <p>Documented as a constant (not an environment variable) to preserve
     * purity — reading from environment is a side effect.
     */
    public static final LocalDateTime V69_DEPLOY_TIMESTAMP =
            LocalDateTime.of(2026, 5, 23, 12, 0);

    // Private constructor — pure utility class, no instances.
    private BotEventResolver() {
        throw new UnsupportedOperationException("BotEventResolver is pure — no instances");
    }

    /**
     * Derives bot events from an appointment row.
     *
     * @param appointment non-null appointment; associations must be preloaded
     *     by the caller (this method reads fields directly without triggering
     *     Hibernate lazy fetches — the caller's responsibility)
     * @return immutable list of derived events, sorted by occurredAt ASC,
     *     then by type ordinal ASC, then by appointmentId ASC.
     *     Returns an empty list when the appointment is not bot-relevant
     *     (i.e. {@code source != BOT} and {@code cancellationRequestedActor != BOT}).
     */
    public static List<BotEvent> resolve(Appointment appointment) {
        Objects.requireNonNull(appointment, "appointment");

        // ─── Pre-check: bot-relevance ────────────────────────────────────
        boolean botRelevant = appointment.getSource() == AppointmentSource.BOT
                || ACTOR_BOT.equals(appointment.getCancellationRequestedActor());
        if (!botRelevant) {
            return List.of();
        }

        List<BotEvent> events = new ArrayList<>(4);

        // ─── BRANCH 1 — BOT_PROPOSED / BRANCH 2 — BOT_AUTO_CONFIRMED ─────
        // Mutually exclusive. Determined by source=BOT plus discriminators
        // identifying which mode produced the row.
        if (appointment.getSource() == AppointmentSource.BOT) {
            if (shouldEmitBotProposed(appointment)) {
                events.add(buildCreationEvent(appointment, BotEventType.BOT_PROPOSED));
            } else if (shouldEmitBotAutoConfirmed(appointment)) {
                events.add(buildCreationEvent(appointment, BotEventType.BOT_AUTO_CONFIRMED));
            }
        }

        // ─── BRANCH 3 — OWNER_APPROVED (approved_at != null) ────────────
        if (appointment.getSource() == AppointmentSource.BOT
                && appointment.getApprovedAt() != null) {
            events.add(buildEvent(
                    BotEventType.OWNER_APPROVED,
                    appointment.getApprovedAt(),
                    ACTOR_OWNER,
                    appointment.getApprovedByUserId(),
                    appointment.getId(),
                    AppointmentStatus.CONFIRMED.name(),
                    null,
                    metadataFor(appointment, BotEventType.OWNER_APPROVED)
            ));
        }

        // ─── BRANCH 4 — OWNER_REJECTED (PENDING→CANCELLED by OWNER) ─────
        // 3-actor cancellation model: rejection of a PENDING proposal (no CR involved).
        // The cancellationRequestedActor IS NULL check disambiguates from
        // OWNER_APPROVED_CANCEL (CR-approve scenario where the same actor/source
        // pattern appears but a cancellation request was involved — handled by BRANCH 6a).
        if (appointment.getSource() == AppointmentSource.BOT
                && appointment.getStatus() == AppointmentStatus.CANCELLED
                && ACTOR_OWNER.equals(appointment.getCancellationActor())
                && SOURCE_PANEL.equals(appointment.getCancellationSource())
                && appointment.getApprovedAt() == null
                && appointment.getRevokedAt() == null
                && appointment.getCancellationRequestedActor() == null) {
            events.add(buildEvent(
                    BotEventType.OWNER_REJECTED,
                    appointment.getCancelledAt(),
                    ACTOR_OWNER,
                    appointment.getCancelledByUserId(),
                    appointment.getId(),
                    AppointmentStatus.CANCELLED.name(),
                    appointment.getCancellationReason(),
                    null
            ));
        }

        // ─── BRANCH 5 — BOT_PENDING_EXPIRED (SYSTEM timeout) ────────────
        if (appointment.getSource() == AppointmentSource.BOT
                && appointment.getStatus() == AppointmentStatus.CANCELLED
                && ACTOR_SYSTEM.equals(appointment.getCancellationActor())
                && appointment.getCancellationReason() != null
                && appointment.getCancellationReason()
                        .startsWith(REASON_PREFIX_TIMEOUT_BOT_APPROVAL)) {
            events.add(buildEvent(
                    BotEventType.BOT_PENDING_EXPIRED,
                    appointment.getCancelledAt(),
                    ACTOR_SYSTEM,
                    null,
                    appointment.getId(),
                    AppointmentStatus.CANCELLED.name(),
                    null,
                    null
            ));
        }

        // ─── BRANCH 6 — BOT_CANCEL_REQUESTED + 6a/6b/6c (CR resolution) ─
        if (ACTOR_BOT.equals(appointment.getCancellationRequestedActor())
                && appointment.getCancellationRequestedAt() != null) {
            events.add(buildEvent(
                    BotEventType.BOT_CANCEL_REQUESTED,
                    appointment.getCancellationRequestedAt(),
                    ACTOR_BOT,
                    null,
                    appointment.getId(),
                    AppointmentStatus.CANCELLATION_REQUESTED.name(),
                    appointment.getCancellationRequestReason(),
                    null
            ));

            // 6a: OWNER_APPROVED_CANCEL — owner approved the CR
            if (appointment.getCancellationRequestDecidedAt() != null
                    && appointment.getStatus() == AppointmentStatus.CANCELLED) {
                events.add(buildEvent(
                        BotEventType.OWNER_APPROVED_CANCEL,
                        appointment.getCancellationRequestDecidedAt(),
                        ACTOR_OWNER,
                        appointment.getCancellationRequestDecidedByUserId(),
                        appointment.getId(),
                        AppointmentStatus.CANCELLED.name(),
                        appointment.getCancellationRequestDecisionReason(),
                        null
                ));
            }
            // 6b: OWNER_REJECTED_CANCEL — owner rejected the CR, appointment reverted to CONFIRMED
            else if (appointment.getCancellationRequestDecidedAt() != null
                    && appointment.getStatus() == AppointmentStatus.CONFIRMED) {
                events.add(buildEvent(
                        BotEventType.OWNER_REJECTED_CANCEL,
                        appointment.getCancellationRequestDecidedAt(),
                        ACTOR_OWNER,
                        appointment.getCancellationRequestDecidedByUserId(),
                        appointment.getId(),
                        AppointmentStatus.CONFIRMED.name(),
                        appointment.getCancellationRequestDecisionReason(),
                        null
                ));
            }
            // 6c: CR_TIMEOUT_EXPIRED — CR expired without an owner decision
            else if (appointment.getCancellationRequestExpiredAt() != null
                    && appointment.getCancellationRequestDecidedAt() == null) {
                events.add(buildEvent(
                        BotEventType.CR_TIMEOUT_EXPIRED,
                        appointment.getCancellationRequestExpiredAt(),
                        ACTOR_SYSTEM,
                        null,
                        appointment.getId(),
                        AppointmentStatus.CONFIRMED.name(),
                        null,
                        null
                ));
            }
        }

        // ─── BRANCH 10 — BOT_CANCELLED (AUTO_CONFIRM direct cancel) ─────
        if (appointment.getStatus() == AppointmentStatus.CANCELLED
                && ACTOR_BOT.equals(appointment.getCancellationActor())
                && SOURCE_BOT.equals(appointment.getCancellationSource())) {
            events.add(buildEvent(
                    BotEventType.BOT_CANCELLED,
                    appointment.getCancelledAt(),
                    ACTOR_BOT,
                    null,
                    appointment.getId(),
                    AppointmentStatus.CANCELLED.name(),
                    appointment.getCancellationReason(),
                    null
            ));
        }

        // ─── BRANCH 11 — BOT_REVOKED (emergency revoke) ─────────────────
        // When a revoke occurred: emit BOT_REVOKED and apply the revoke filter.
        if (appointment.getRevokedAt() != null
                && appointment.getStatus() == AppointmentStatus.CONFIRMED) {
            Map<String, Object> revokeMeta = new HashMap<>();
            if (appointment.getCancellationActor() != null) {
                revokeMeta.put("previous_cancel_actor", appointment.getCancellationActor());
            }
            if (appointment.getCancellationReason() != null) {
                revokeMeta.put("previous_cancel_reason", appointment.getCancellationReason());
            }
            events.add(buildEvent(
                    BotEventType.BOT_REVOKED,
                    appointment.getRevokedAt(),
                    ACTOR_OWNER,
                    appointment.getRevokedByUserId(),
                    appointment.getId(),
                    AppointmentStatus.CONFIRMED.name(),
                    null,
                    revokeMeta
            ));

            // Revoke filter: skip cancel-derived events that would contradict the
            // current CONFIRMED status. BOT_REVOKED + those events would produce
            // contradictory narrative ("cancelled + revoked").
            // Historical lineage events — BOT_PROPOSED, OWNER_APPROVED,
            // BOT_CANCEL_REQUESTED, OWNER_REJECTED_CANCEL, CR_TIMEOUT_EXPIRED —
            // are preserved because they describe the historical sequence that led
            // to the revoke, not the current state.
            events.removeIf(e -> e.type() == BotEventType.OWNER_REJECTED
                    || e.type() == BotEventType.BOT_PENDING_EXPIRED
                    || e.type() == BotEventType.BOT_CANCELLED
                    || e.type() == BotEventType.OWNER_APPROVED_CANCEL);
        }

        // ─── Defensive null filter ──────────────────────────────────────
        // buildEvent() returns null when occurredAt is null (degenerate data
        // path — e.g. a CANCELLED row with a missing cancelledAt timestamp).
        // Remove nulls before sorting so the Comparator never encounters a
        // null BotEvent::occurredAt.
        events.removeIf(Objects::isNull);

        // ─── DEFAULT — exhaustive guard ──────────────────────────────────
        // A bot-relevant row produced zero events — a taxonomy gap. Log for
        // observability and return empty. Never throw: a gap in the taxonomy
        // must not break the panel for every other row.
        if (events.isEmpty()) {
            log.error("BotEventResolver unhandled state: appointmentId={}, status={}, "
                    + "source={}, cancellationActor={}, cancellationSource={}, "
                    + "cancellationRequestedActor={}",
                    appointment.getId(), appointment.getStatus(), appointment.getSource(),
                    appointment.getCancellationActor(), appointment.getCancellationSource(),
                    appointment.getCancellationRequestedActor());
            ObservabilityHelper.reportBackgroundFailure(
                    "bot-event-resolver-unhandled-state", null);
        }

        // ─── Sort: occurredAt ASC, type ordinal ASC, appointmentId ASC ──
        events.sort(Comparator
                .comparing(BotEvent::occurredAt)
                .thenComparingInt(e -> e.type().ordinal())
                .thenComparing(BotEvent::appointmentId));

        return List.copyOf(events);
    }

    // ────────────────────────────────────────────────────────────────────
    // Pre-audit-columns fallback heuristic
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns true if the appointment is a pre-cutoff approval that cannot be
     * distinguished from an auto-confirm creation by the approval audit column
     * alone (because the column did not exist at creation time).
     *
     * <p>Heuristic: bot source + CONFIRMED status + no approval audit column value
     * + no cancel or CR lineage + created before the audit column deploy cutoff.
     *
     * <p>Package-private visibility for direct testing.
     */
    static boolean isPreV69ApprovalFallback(Appointment a) {
        return a.getSource() == AppointmentSource.BOT
                && a.getStatus() == AppointmentStatus.CONFIRMED
                && a.getApprovedAt() == null
                && a.getCancellationActor() == null
                && a.getCancellationRequestedAt() == null
                && a.getCreatedAt() != null
                && a.getCreatedAt().isBefore(V69_DEPLOY_TIMESTAMP);
    }

    // ────────────────────────────────────────────────────────────────────
    // Helpers — private and side-effect-free
    // ────────────────────────────────────────────────────────────────────

    private static boolean shouldEmitBotProposed(Appointment a) {
        return a.getStatus() == AppointmentStatus.PENDING
                || a.getApprovedAt() != null
                || isPreV69ApprovalFallback(a)
                || isRevokedPriorProposal(a);
    }

    /**
     * After a revoke, the status flips back to CONFIRMED but the preserved
     * cancel fields describe the original cancellation. If those preserved fields
     * match the rejected-proposal or expired-proposal pattern, the row originated
     * as a proposal — emit BOT_PROPOSED rather than BOT_AUTO_CONFIRMED to
     * preserve historical lineage in the event feed.
     */
    private static boolean isRevokedPriorProposal(Appointment a) {
        if (a.getRevokedAt() == null) return false;
        if (a.getApprovedAt() != null) return false;
        boolean ownerRejected = ACTOR_OWNER.equals(a.getCancellationActor())
                && SOURCE_PANEL.equals(a.getCancellationSource())
                && a.getCancellationRequestedActor() == null;
        boolean systemTimeoutExpired = ACTOR_SYSTEM.equals(a.getCancellationActor())
                && a.getCancellationReason() != null
                && a.getCancellationReason().startsWith(REASON_PREFIX_TIMEOUT_BOT_APPROVAL);
        return ownerRejected || systemTimeoutExpired;
    }

    private static boolean shouldEmitBotAutoConfirmed(Appointment a) {
        // Exclusion conditions for BOT_AUTO_CONFIRMED — the row was originally
        // a PROPOSAL (approval-required mode):
        // - status==PENDING: still awaiting owner decision
        // - approved_at!=null: was PROPOSED then approved (BRANCH 1 path 2)
        // - pre-audit-columns fallback: historical approval without audit column
        // - OWNER+CANCELLED no-CR: rejected proposal
        // - SYSTEM+CANCELLED TIMEOUT: expired proposal (the system timeout job
        //   only acts on PENDING+BOT rows, so the row was originally PROPOSED)
        // - Revoked prior-proposal: preserved cancel fields identify a former
        //   PROPOSED-then-revoked row
        if (a.getStatus() == AppointmentStatus.PENDING) return false;
        if (a.getApprovedAt() != null) return false;
        if (isPreV69ApprovalFallback(a)) return false;
        if (isRevokedPriorProposal(a)) return false;
        if (a.getStatus() == AppointmentStatus.CANCELLED
                && ACTOR_OWNER.equals(a.getCancellationActor())
                && a.getCancellationRequestedActor() == null) {
            return false;
        }
        if (a.getStatus() == AppointmentStatus.CANCELLED
                && ACTOR_SYSTEM.equals(a.getCancellationActor())
                && a.getCancellationReason() != null
                && a.getCancellationReason().startsWith(REASON_PREFIX_TIMEOUT_BOT_APPROVAL)) {
            return false;
        }
        return true;
    }

    private static BotEvent buildCreationEvent(Appointment a, BotEventType type) {
        Map<String, Object> meta = null;
        if (type == BotEventType.BOT_PROPOSED && isPreV69ApprovalFallback(a)) {
            meta = Map.of("pre_v69", true);
        }
        return buildEvent(
                type,
                a.getCreatedAt(),
                ACTOR_BOT,
                null,
                a.getId(),
                a.getStatus() != null ? a.getStatus().name() : null,
                null,
                meta
        );
    }

    private static Map<String, Object> metadataFor(Appointment a, BotEventType type) {
        if (type == BotEventType.OWNER_APPROVED) {
            Map<String, Object> m = new HashMap<>();
            if (a.getApprovalDecisionSource() != null) {
                m.put("approval_decision_source", a.getApprovalDecisionSource().name());
            }
            return m;
        }
        return null;
    }

    private static BotEvent buildEvent(
            BotEventType type,
            LocalDateTime occurredAt,
            String actorType,
            Long actorUserId,
            Long appointmentId,
            String result,
            String reason,
            Map<String, Object> metadata
    ) {
        // Defensive: occurredAt may be null in degenerate cases — e.g. legacy
        // data where the cancelledAt timestamp is absent despite status=CANCELLED.
        // Rather than emit an event with a null timestamp (which breaks sorting
        // and audit display), skip it. The caller's null filter removes the
        // return value, and the DEFAULT branch fires if the list is empty.
        if (occurredAt == null) {
            log.warn("BotEventResolver skipping event with null occurredAt: "
                    + "appointmentId={}, type={}", appointmentId, type);
            return null;
        }
        return new BotEvent(
                BotEvent.generateId(appointmentId, type, occurredAt),
                occurredAt,
                type,
                actorType,
                actorUserId,
                appointmentId,
                result,
                reason,
                metadata
        );
    }
}
