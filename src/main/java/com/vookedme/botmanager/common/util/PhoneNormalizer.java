package com.vookedme.botmanager.common.util;

/**
 * Canonical E.164 phone number normaliser.
 *
 * <h3>Contract</h3>
 *
 * <p>{@link #normalize(String)} returns exactly one of three outcomes:
 * <ol>
 *   <li>A canonical E.164 string ({@code "+34612345678"}, no separators).</li>
 *   <li>{@code null} — when the input is {@code null} or blank after trimming.</li>
 *   <li>Throws {@link IllegalArgumentException} — when the input cannot be
 *       coerced to E.164.</li>
 * </ol>
 *
 * <p>The method never returns a "nearly-E.164" value or the original input as a
 * fallback. This is intentional: when the normaliser is used in a defensive layer
 * (e.g. a JPA {@code @PrePersist} hook), an invalid input should abort the
 * transaction rather than allow corrupt data to reach the database.
 *
 * <h3>Coercion rules</h3>
 *
 * <ul>
 *   <li>Pre-cleaning: spaces, hyphens, dots, and parentheses are removed. The
 *       DTO setters perform the same cleaning on inbound JSON, but the normaliser
 *       repeats it for idempotency and for safety when called from
 *       {@code @PrePersist} (where unsanitised input may arrive).</li>
 *   <li>If the cleaned value matches {@code ^\+[1-9]\d{7,14}$} it is already
 *       canonical — returned as-is.</li>
 *   <li>If it matches {@code ^[1-9]\d{7,14}$} (same digits, missing "+") — the
 *       "+" is prepended and the result returned.</li>
 *   <li>Any other pattern throws {@link IllegalArgumentException}.</li>
 * </ul>
 *
 * <p>The digit-count range (8–15 total, including country code) matches the
 * tolerance applied by the request DTOs and the phone input component in the
 * frontend.
 *
 * <h3>Unicode separator handling</h3>
 *
 * <p>The separator pattern uses {@code \p{Z}} to cover all Unicode space
 * separators — including non-breaking space (U+00A0), en-quad (U+2000), em-space
 * (U+2003), and similar characters that Java's {@code \s} does not match without
 * the {@code UNICODE_CHARACTER_CLASS} flag. Without this, a phone number pasted
 * from a word processor or email client with a non-breaking space between the
 * country code and subscriber number would be rejected as invalid.
 *
 * <h3>PII in error messages</h3>
 *
 * <p>The {@link IllegalArgumentException} thrown for invalid input does not
 * include the raw phone value in its message. Phone numbers are personal data;
 * including the value would cause it to appear in log files, Sentry events,
 * stack traces, and any other downstream log sinks. The call site in the stack
 * trace is sufficient for debugging.
 *
 * <h3>Usage</h3>
 *
 * <p>This class is {@code final} with a private constructor — it must not be
 * instantiated or subclassed. The entire API is static so the normaliser can be
 * called from {@code @PrePersist} hooks without Spring injection.
 */
public final class PhoneNormalizer {

    /** Matches a canonical E.164 number: "+" + non-zero digit + 7–14 further digits. */
    private static final java.util.regex.Pattern E164_CANONICAL =
            java.util.regex.Pattern.compile("^\\+[1-9]\\d{7,14}$");

    /** Matches an E.164 number that is missing the leading "+". */
    private static final java.util.regex.Pattern E164_MISSING_PLUS =
            java.util.regex.Pattern.compile("^[1-9]\\d{7,14}$");

    /**
     * Characters removed before format evaluation. Uses {@code \p{Z}} to cover
     * all Unicode space separator categories in addition to ASCII whitespace,
     * ensuring correct handling of non-breaking spaces pasted from external
     * sources.
     */
    private static final java.util.regex.Pattern SEPARATORS =
            java.util.regex.Pattern.compile("[\\p{Z}\\s\\-().]+");

    private PhoneNormalizer() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    /**
     * Normalises a raw phone number to canonical E.164.
     *
     * @param raw the raw input value (may contain separators, may be
     *            {@code null} or blank)
     * @return canonical E.164 string, or {@code null} if the input was empty
     * @throws IllegalArgumentException if the input cannot be coerced to E.164
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        String cleaned = SEPARATORS.matcher(trimmed).replaceAll("");
        if (cleaned.isEmpty()) {
            // Input consisted entirely of separator characters ("   ", "()", "-").
            return null;
        }

        if (E164_CANONICAL.matcher(cleaned).matches()) {
            return cleaned;
        }
        if (E164_MISSING_PLUS.matcher(cleaned).matches()) {
            return "+" + cleaned;
        }

        // Any other pattern — repeated signs, letters, too short/long,
        // leading zero — is rejected. Throwing rather than returning the
        // original prevents corrupt data from bypassing CHECK constraints
        // in bulk-load or un-validated call paths.
        //
        // The raw value is intentionally excluded from the exception message
        // to avoid propagating personal data into log files, Sentry events,
        // and other downstream sinks. The call-site stack trace is sufficient
        // to identify the source of the invalid input.
        throw new IllegalArgumentException("Invalid phone number format");
    }
}
