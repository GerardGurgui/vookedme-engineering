package com.vookedme.botmanager.customer.entity;

/**
 * Taxonomy for entries in the {@code customer_legitimation_audit_log} table.
 * One value per type of channel legitimacy state transition.
 *
 * <p>The column is enforced with a database CHECK constraint; adding a new value
 * requires a schema migration to extend the constraint.
 */
public enum CustomerLegitimationEventType {

    /** Channel legitimacy status transitions to {@code true} via bot inbound. */
    LEGITIMATED,

    /** Channel legitimacy status transitions to {@code false} via explicit customer opt-out. */
    OPT_OUT,

    /** Channel legitimacy status transitions to {@code true} after an opt-out, by customer action. */
    REACTIVATED,

    /** Business owner attestation recorded (channel legitimacy → {@code true} via administration panel). */
    ATTESTED
}
