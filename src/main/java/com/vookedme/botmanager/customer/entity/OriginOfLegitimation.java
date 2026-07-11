package com.vookedme.botmanager.customer.entity;

/**
 * The origin that established channel legitimacy ({@code channelLegitimacyStatus == true}).
 * Null when {@code channelLegitimacyStatus} is {@code false} or {@code null}.
 *
 * <p>This is an application-level enum over a {@code VARCHAR(30)} column, parity-enforced
 * with a database CHECK constraint. Adding new values is additive without a type migration.
 */
public enum OriginOfLegitimation {

    /** Customer-initiated inbound (unambiguous positive action) — establishes legitimacy automatically. */
    BOT_ORIGIN,

    /** Business owner attestation from the administration panel (Art. 13 out-of-band notification). */
    ATTESTATION,

    /** Customer-initiated reactivation after an explicit opt-out (new inbound / opt-in signal). */
    REACTIVATED_BY_CUSTOMER
}
