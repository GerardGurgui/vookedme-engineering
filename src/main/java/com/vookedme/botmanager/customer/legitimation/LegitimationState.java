package com.vookedme.botmanager.customer.legitimation;

/**
 * Coarse-grained contactability state — the {@code fromState}/{@code toState} vocabulary
 * of the customer legitimation audit log. Represents the three-valued state of
 * {@code Customer.channelLegitimacyStatus}:
 * {@code null → UNEVALUATED}, {@code true → LEGITIMATE}, {@code false → DENIED}.
 *
 * <p>The <em>why</em> of each transition lives in separate audit log columns ({@code reason}
 * and {@code origin}) — no duplication. This is an application-level enum; the audit log
 * column is stored as {@code String} without a CHECK constraint, following the same
 * pattern as {@code fromState}/{@code toState} in the appointment audit log.
 */
public enum LegitimationState {

    /** {@code channelLegitimacyStatus == null} — not yet evaluated; default-deny applies (Art. 25.2). */
    UNEVALUATED,

    /** {@code channelLegitimacyStatus == true} — lawfully contactable. */
    LEGITIMATE,

    /** {@code channelLegitimacyStatus == false} — not contactable; reason in {@code reasonOfDeny}. */
    DENIED;

    /** Maps the three-valued {@code channelLegitimacyStatus} field to its vocabulary state. */
    public static LegitimationState of(Boolean channelLegitimacyStatus) {
        if (channelLegitimacyStatus == null) {
            return UNEVALUATED;
        }
        return channelLegitimacyStatus ? LEGITIMATE : DENIED;
    }
}
