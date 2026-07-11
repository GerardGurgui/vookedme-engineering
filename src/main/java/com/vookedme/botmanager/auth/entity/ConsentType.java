package com.vookedme.botmanager.auth.entity;

/**
 * Type of GDPR consent event recorded in {@code consent_audit}.
 *
 * <p>Mirrors the {@code CHECK} constraint on the {@code consent_type} column.
 * Adding a new value here requires a corresponding schema change.
 *
 * <p><b>Architectural lock (2026-05-27):</b> EMPLOYEE acceptance ≠ DPA
 * acceptance. EMPLOYEE accepts platform-use terms and conditions plus their
 * own account privacy policy. Only the OWNER role accepts the DPA, business
 * contractual terms, processor agreement, and AI processing clauses.
 *
 * <p><b>Naming:</b> {@code TERMS} and {@code PRIVACY} are preserved verbatim
 * because existing {@code consent_audit} rows are immutable GDPR evidence —
 * renaming via UPDATE would alter the audit. {@code DPA_CORPORATE} uses an
 * explicit name for granular auditor clarity.
 */
public enum ConsentType {
    /**
     * Platform Terms of Service (individual — the user is the data subject).
     */
    TERMS,
    /**
     * Privacy Policy / personal data processing agreement (individual).
     */
    PRIVACY,
    /**
     * DPA / Data Processing Agreement Art. 28 GDPR — corporate acceptance
     * signed by the OWNER on behalf of the business.
     *
     * <p>Must NEVER be accepted by an EMPLOYEE (it would have no legal standing).
     * Corporate acceptance is tracked in parallel on {@code Business}:
     * {@code dpa_accepted_at}, {@code dpa_accepted_by_user_id},
     * and {@code dpa_signed_version}.
     */
    DPA_CORPORATE
}
