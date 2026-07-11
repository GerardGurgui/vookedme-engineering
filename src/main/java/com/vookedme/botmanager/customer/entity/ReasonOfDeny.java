package com.vookedme.botmanager.customer.entity;

/**
 * The reason channel legitimacy ({@code channelLegitimacyStatus}) is {@code false}.
 * Null when {@code channelLegitimacyStatus} is {@code true} or {@code null}.
 *
 * <p>This is an application-level enum over a {@code VARCHAR(30)} column, parity-enforced
 * with a database CHECK constraint. Adding new values is additive without a type migration.
 *
 * <p>The unevaluated default state — {@code channelLegitimacyStatus = null, reasonOfDeny = null}
 * — is represented by a {@code null} pair, not by any value of this enum.
 */
public enum ReasonOfDeny {

    /**
     * Denied for a reason other than an explicit customer opt-out. Note: the default
     * <em>unevaluated</em> state is {@code channelLegitimacyStatus = null, reasonOfDeny = null}
     * — NOT this value.
     */
    NEVER_LEGITIMATED,

    /**
     * Customer requested an explicit opt-out (STOP signal). Takes precedence over subsequent
     * business owner actions (Art. 21): the owner cannot override a customer-initiated denial.
     */
    CUSTOMER_EXPLICIT_STOP,

    /** Reserved: block derived from platform delivery callbacks. Not written by the legitimation service. */
    BLOCKED_BY_CUSTOMER
}
