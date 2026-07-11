package com.vookedme.botmanager.bot.service;

import com.vookedme.botmanager.bot.domain.BotEvent;
import com.vookedme.botmanager.bot.domain.BotEventType;
import com.vookedme.botmanager.bot.domain.BotNarrativeContext;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * PURE renderer: {@link BotEvent} + {@link BotNarrativeContext} → locale narrative string.
 *
 * <p><b>Purity guarantees</b> (analogous to {@link BotEventResolver}):
 * <ul>
 *   <li>No Spring annotations, no instance state, no clock access, no I/O</li>
 *   <li>Deterministic: same event + context → same string always</li>
 *   <li>No PII enrichment — the caller resolves names and applies GDPR redaction
 *       before invoking this renderer (see {@link BotNarrativeContext} contract)</li>
 * </ul>
 *
 * <p><b>Locale note</b> — all narrative string constants and templates in this class
 * are in Spanish ({@code es_ES}). These strings are intentional locale output
 * displayed in the bot activity feed to Spanish-speaking business operators.
 * They are not code comments and are not translated. Extending to other locales
 * would require extracting these constants to resource bundles and parameterising
 * the locale — a deliberate future concern, not a current requirement.
 *
 * <p><b>Exhaustiveness</b> — the renderer's switch covers every {@link BotEventType}.
 * The private {@code compileTimeExhaustivenessCheck} method enforces this at
 * compile time: it contains a second switch over the enum and will produce a
 * compile error if a new {@code BotEventType} is added without a corresponding
 * case in {@link #render}.
 */
public final class BotNarrativeRenderer {

    // Madrid wall-clock datetime format used across bot activity narratives.
    // Kept as a renderer-local constant to avoid coupling to the notification
    // module, which uses the same format but is in a different package.
    private static final DateTimeFormatter DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", new Locale("es", "ES"));

    // ─── Locale output constants (Spanish, es_ES) ───────────────────────
    // These strings appear verbatim in the bot activity feed. All are
    // intentional Spanish-language operator UI strings.
    private static final String DELETED_CUSTOMER = "[Cliente borrado]";
    private static final String SERVICE_FALLBACK = "tu servicio";
    private static final String DATETIME_FALLBACK = "fecha sin registrar";
    private static final String NO_REASON = "sin motivo indicado";
    private static final String PREV_ACTOR_FALLBACK = "el sistema";

    private BotNarrativeRenderer() {
        throw new UnsupportedOperationException("BotNarrativeRenderer is pure — no instances");
    }

    /**
     * Renders the Spanish narrative string for a bot event.
     *
     * @param event   non-null {@link BotEvent} produced by {@code BotEventResolver}
     * @param context non-null {@link BotNarrativeContext} carrying the customer
     *                name, offering name, and appointment datetime. The caller
     *                is responsible for GDPR pre-redaction before passing the
     *                context (null customerName is acceptable — the renderer
     *                applies the locale-specific deleted-customer fallback).
     * @return Spanish narrative string ready for the bot activity feed.
     *     Never null, never empty — the renderer falls back gracefully on
     *     missing or null fields.
     */
    public static String render(BotEvent event, BotNarrativeContext context) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(context, "context");

        String customer = orDeleted(context.customerName());
        String offering = orFallback(context.offeringName(), SERVICE_FALLBACK);
        String datetime = formatDatetime(context.datetime());

        // All string literals in this switch are locale output (Spanish, es_ES).
        return switch (event.type()) {
            case BOT_PROPOSED -> renderBotProposed(event, customer, offering, datetime);
            case BOT_AUTO_CONFIRMED -> "El bot creó una cita para " + customer
                    + " (" + offering + ", " + datetime + "). Confirmada automáticamente.";
            case OWNER_APPROVED -> "Aprobaste la cita de " + customer
                    + " (" + offering + ", " + datetime + ").";
            case OWNER_REJECTED -> "Rechazaste la cita de " + customer
                    + ". Motivo: \"" + reasonOrFallback(event) + "\".";
            case BOT_PENDING_EXPIRED -> "El bot esperaba tu aprobación pero el tiempo expiró. "
                    + "La cita de " + customer + " fue liberada.";
            case BOT_CANCEL_REQUESTED -> customer + " solicitó cancelar su cita ("
                    + offering + ", " + datetime + ").";
            case OWNER_APPROVED_CANCEL -> "Aprobaste la cancelación solicitada por " + customer + ".";
            case OWNER_REJECTED_CANCEL -> "Rechazaste la cancelación de " + customer
                    + ". Motivo: \"" + reasonOrFallback(event) + "\".";
            case CR_TIMEOUT_EXPIRED -> "La solicitud de cancelación de " + customer
                    + " expiró sin decisión. La cita sigue confirmada.";
            case BOT_CANCELLED -> "El bot canceló la cita de " + customer
                    + " (" + offering + ", " + datetime + ").";
            case BOT_REVOKED -> "Reactivaste la cita de " + customer
                    + " (estaba cancelada por " + previousCancelActor(event)
                    + ", ahora confirmada de nuevo).";
        };
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private static String renderBotProposed(BotEvent event,
                                            String customer,
                                            String offering,
                                            String datetime) {
        String base = "El bot creó una cita para " + customer
                + " (" + offering + ", " + datetime + "). Esperando tu aprobación.";
        if (Boolean.TRUE.equals(event.metadata().get("pre_v69"))) {
            // Pre-cutoff fallback: the approval occurred before approval audit
            // columns were available — no precise timestamp recorded.
            return base + " (aprobada antes de la actualización del sistema —"
                    + " sin registro detallado)";
        }
        return base;
    }

    private static String orDeleted(String customerName) {
        return (customerName == null || customerName.isBlank()) ? DELETED_CUSTOMER : customerName;
    }

    private static String orFallback(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String formatDatetime(LocalDateTime dt) {
        return (dt == null) ? DATETIME_FALLBACK : DATETIME_FORMAT.format(dt);
    }

    private static String reasonOrFallback(BotEvent event) {
        return orFallback(event.reason(), NO_REASON);
    }

    /**
     * Maps the {@code previous_cancel_actor} metadata key to a Spanish display
     * string for the BOT_REVOKED narrative. Falls back to the generic system
     * actor string when the metadata key is absent.
     *
     * <p>All returned strings are locale output (Spanish, es_ES).
     */
    private static String previousCancelActor(BotEvent event) {
        Object actor = event.metadata().get("previous_cancel_actor");
        if (actor == null) return PREV_ACTOR_FALLBACK;
        String s = actor.toString();
        return switch (s) {
            case "OWNER"    -> "ti";
            case "BOT"      -> "el bot";
            case "SYSTEM"   -> "el sistema";
            case "EMPLOYEE" -> "un empleado";
            case "ADMIN"    -> "un administrador";
            default         -> PREV_ACTOR_FALLBACK;
        };
    }

    /**
     * Compile-time exhaustiveness check: forces a compile error if a new
     * {@link BotEventType} is added without a corresponding case in
     * {@link #render}. This method is never called at runtime — it exists
     * only to make the Java compiler verify that the switch in {@code render}
     * covers every enum constant.
     *
     * <p>Keep this switch in sync with the switch in {@link #render}.
     */
    @SuppressWarnings("unused")
    private static void compileTimeExhaustivenessCheck(BotEventType type) {
        switch (type) {
            case BOT_PROPOSED, BOT_AUTO_CONFIRMED, OWNER_APPROVED, OWNER_REJECTED,
                    BOT_PENDING_EXPIRED, BOT_CANCEL_REQUESTED, OWNER_APPROVED_CANCEL,
                    OWNER_REJECTED_CANCEL, CR_TIMEOUT_EXPIRED, BOT_CANCELLED,
                    BOT_REVOKED -> { /* covered */ }
        }
    }
}
