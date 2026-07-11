package com.vookedme.botmanager.bot.domain;

import java.time.LocalDateTime;

/**
 * Render-time context for {@code BotNarrativeRenderer}. Encapsulates the
 * appointment-level fields the resolver does not carry on the {@link BotEvent}
 * record itself — specifically, the customer name, offering name, and scheduled
 * datetime.
 *
 * <p>The impure adapter layer is responsible for resolving these values from
 * the {@code Appointment}, {@code Customer}, and {@code Offering} entities,
 * and for applying GDPR redaction rules before passing the context to the
 * renderer. The renderer does not access the database.
 *
 * <p><b>Pure data</b> — no JPA or serialisation annotations. Used only as
 * renderer input.
 *
 * @param customerName customer display name; pre-redacted by the caller to
 *     the locale-specific deleted-customer fallback string when the customer
 *     was GDPR-erased, or {@code null} when the customer association is
 *     broken. The renderer applies a final fallback when this is null or blank.
 * @param offeringName service or treatment name; may be pre-suppressed to a
 *     locale-specific generic fallback for business types where service names
 *     are considered sensitive. The renderer falls back gracefully when null.
 * @param datetime appointment scheduled datetime (Madrid wall-clock).
 *     Formatted to {@code "dd/MM/yyyy HH:mm"} by the renderer. The renderer
 *     produces a locale-specific fallback string when null.
 * @param employeeName optional employee display name. Not used in current
 *     renderer templates but preserved for future templates such as reschedule
 *     narratives.
 */
public record BotNarrativeContext(
        String customerName,
        String offeringName,
        LocalDateTime datetime,
        String employeeName
) {
    /**
     * Convenience factory for the three required rendering fields, without
     * an employee name.
     */
    public static BotNarrativeContext of(String customerName,
                                         String offeringName,
                                         LocalDateTime datetime) {
        return new BotNarrativeContext(customerName, offeringName, datetime, null);
    }
}
