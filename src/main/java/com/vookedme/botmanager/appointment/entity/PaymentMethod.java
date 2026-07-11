package com.vookedme.botmanager.appointment.entity;

/**
 * Operational payment-method tag recorded against an appointment.
 *
 * <p><b>Not a fiscal field.</b> This is representational metadata for
 * analytics and day-to-day bookkeeping (e.g. "how many appointments were
 * paid in cash"), never an input to invoicing, tax calculation, or payment
 * reconciliation. The single-money-field invariant —
 * {@code appointment.price} + {@code appointment.paid} — is preserved;
 * this enum is a label attached to the existing {@code paid=true} state,
 * not a second money field.
 *
 * <p>Stored in PostgreSQL as {@code VARCHAR(16)} with a CHECK constraint
 * listing these values verbatim. Adding a new value requires both a new
 * enum constant here and a Flyway migration that rewrites the CHECK
 * constraint — Flyway migrations are forward-only and must not be edited
 * after deployment.
 */
public enum PaymentMethod {
    /** Cash handed over at the counter. */
    CASH,
    /** Card payment via physical terminal. */
    CARD,
    /** Bank transfer (SEPA / manual). */
    TRANSFER,
    /** Bizum — Spanish instant mobile payment. */
    BIZUM,
    /** Delegated payment link (Stripe Checkout, Mercado Pago, etc.). */
    LINK,
    /** Catch-all for any payment method not listed above. */
    OTHER
}
