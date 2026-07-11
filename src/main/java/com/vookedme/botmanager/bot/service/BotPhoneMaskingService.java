package com.vookedme.botmanager.bot.service;

/**
 * PURE utility: masks E.164 phone numbers for list-view display in the
 * bot audit feed.
 *
 * <p>Output format: {@code "<country code> *** *** <last 3 digits>"}.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code "+34601111111"} → {@code "+34 *** *** 111"} (Spain)</li>
 *   <li>{@code "+12125557890"} → {@code "+1 *** *** 890"} (US/Canada)</li>
 *   <li>{@code "+44 7700 900 123"} → {@code "+44 *** *** 123"} (whitespace stripped)</li>
 *   <li>{@code null} or blank → {@code null} (caller renders an empty cell)</li>
 *   <li>No {@code "+"} prefix → {@code null} (rejects non-E.164 input)</li>
 * </ul>
 *
 * <p><b>Purity guarantee</b>: no I/O, no clock access, no instance state.
 * Same input always produces the same output. The algorithm is stable:
 * changes here re-mask all historical data on next read, which is acceptable
 * because the raw phone is never written to the audit log — only the masked
 * form is served to the panel.
 *
 * <p><b>Country code heuristic</b>: explicit prefix cases for Spain (+34),
 * North America (+1), UK (+44), France (+33), and Germany (+49). All other
 * inputs fall back to a generic 2-digit prefix. Adding more known prefixes
 * is a one-liner, but extending without a concrete demand adds maintenance
 * surface without a corresponding benefit — the fallback is explicitly
 * documented as a deliberate simplification, not an oversight.
 */
public final class BotPhoneMaskingService {

    private static final String MIDDLE_MASK = " *** *** ";

    private BotPhoneMaskingService() {
        throw new UnsupportedOperationException("BotPhoneMaskingService is pure — no instances");
    }

    /**
     * Masks a single E.164 phone number for list-view display.
     *
     * @param phoneE164 raw phone number like {@code "+34601111111"}.
     *                  Tolerant of internal whitespace and dashes (stripped).
     *                  Must begin with {@code "+"} after trimming, otherwise
     *                  returns {@code null}.
     * @return masked string ({@code "+34 *** *** 111"}) or {@code null} if
     *         the input is null, blank, or not recognisable as E.164.
     */
    public static String mask(String phoneE164) {
        if (phoneE164 == null) return null;
        String trimmed = phoneE164.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("+")) return null;

        String digits = trimmed.substring(1).replaceAll("\\D", "");
        // Minimum sanity: at least 1 country digit + at least 1 body digit + 3 trailing = 5.
        if (digits.length() < 5) return null;

        String last3 = digits.substring(digits.length() - 3);

        // Known country codes — explicit cases for the operating market and likely
        // international contacts.
        if (digits.startsWith("34") && digits.length() >= 7) {
            return "+34" + MIDDLE_MASK + last3;
        }
        if (digits.startsWith("1") && (digits.length() == 10 || digits.length() == 11)) {
            return "+1" + MIDDLE_MASK + last3;
        }
        if (digits.startsWith("44") && digits.length() >= 7) {
            return "+44" + MIDDLE_MASK + last3;
        }
        if (digits.startsWith("33") && digits.length() >= 7) {
            return "+33" + MIDDLE_MASK + last3;
        }
        if (digits.startsWith("49") && digits.length() >= 7) {
            return "+49" + MIDDLE_MASK + last3;
        }

        // Generic fallback: treat the first 2 digits as the country code.
        // Defensible for most E.164 numbers outside the known-prefix list.
        // Trade-off: a +1 (NANP) number without the expected 10- or 11-digit
        // length falls into this path — accepted for the current operating context.
        String cc = digits.substring(0, 2);
        return "+" + cc + MIDDLE_MASK + last3;
    }
}
