package com.vookedme.botmanager.bot.domain;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable value object produced by {@code BotEventResolver}.
 *
 * <p><b>Pure domain object</b> — no JPA mapping, no Jackson annotations.
 * DTO mapping is the responsibility of the impure adapter layer. Carries only
 * what the resolver derives from {@code Appointment} columns.
 *
 * <p><b>Actor name separation</b> — there is no {@code actorName} field.
 * Name resolution requires a {@code UserRepository} lookup and is therefore
 * impure. The resolver returns {@code actorUserId: Long} only. The impure
 * adapter layer resolves actor user names from the repository and enriches
 * the response DTO. This preserves the purity guarantee of the resolver
 * (see {@link BotEventResolver}).
 *
 * @param id deterministic identifier: {@code "{appointmentId}-{type}-{occurredAtMs}"}.
 *     Used for stable deep-linking and pagination cursors. The same appointment
 *     state always produces the same id.
 * @param occurredAt when the event occurred, in Madrid wall-clock time (LocalDateTime).
 *     The source column varies per event type — see {@link BotEventType} field docs.
 * @param type one of the {@link BotEventType} values representing the event taxonomy.
 * @param actorType string discriminator: {@code "BOT"}, {@code "OWNER"},
 *     {@code "EMPLOYEE"}, {@code "SYSTEM"}, {@code "ADMIN"}. Null when the
 *     event has no attributed actor (e.g. SYSTEM-automated events carry
 *     {@code actorType="SYSTEM"} with a null {@code actorUserId}).
 * @param actorUserId raw foreign key to the {@code users} table. Null when actor
 *     is BOT or SYSTEM, or when the approval audit columns are absent on
 *     pre-cutoff data. The impure adapter layer resolves this to a display
 *     name via the user repository.
 * @param appointmentId foreign key to the {@code appointments} table. Always
 *     non-null — the resolver derives events from a single Appointment.
 * @param result the resulting {@code AppointmentStatus.name()} after the event,
 *     or null for query-only events. Used in the audit projection.
 * @param reason free-text reason populated for events with an explicit motive:
 *     {@code OWNER_REJECTED}, {@code OWNER_REJECTED_CANCEL},
 *     {@code OWNER_APPROVED_CANCEL}, {@code BOT_CANCELLED}. Null otherwise.
 * @param metadata event-specific extras (unmodifiable). Current keys:
 *     {@code "pre_v69"} (boolean) for BOT_PROPOSED on pre-cutoff data;
 *     {@code "approval_decision_source"} (string) for OWNER_APPROVED;
 *     {@code "previous_cancel_actor"} (string) for BOT_REVOKED.
 */
public record BotEvent(
        String id,
        LocalDateTime occurredAt,
        BotEventType type,
        String actorType,
        Long actorUserId,
        Long appointmentId,
        String result,
        String reason,
        Map<String, Object> metadata
) {
    /**
     * Compact constructor — defensive null-checking and immutability enforcement.
     * {@code id}, {@code occurredAt}, {@code type}, and {@code appointmentId}
     * are required; a {@code null} value throws {@code NullPointerException}.
     * {@code metadata} is defensively copied; {@code null} is normalised to an
     * empty map.
     */
    public BotEvent {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(appointmentId, "appointmentId is required");
        metadata = (metadata == null)
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(Map.copyOf(metadata));
    }

    /**
     * Deterministic id generator. Same appointment state → same id always.
     * Format: {@code "{appointmentId}-{type}-{epochSecond}"}.
     *
     * <p>Caller responsibility: pass the canonical {@code occurredAt} for the
     * event type (e.g. for OWNER_APPROVED use {@code approved_at}, not
     * {@code createdAt}).
     *
     * <p>Uses {@code ZoneId.of("Europe/Madrid")} explicitly — not
     * {@code ZoneId.systemDefault()} — as a defence against a future regression
     * of {@code JvmTimezoneInvariant}. If the JVM timezone were ever changed,
     * deep-link IDs would shift retroactively and break stored links. Pinning
     * the zone here keeps id generation invariant regardless of JVM configuration.
     */
    public static String generateId(Long appointmentId, BotEventType type, LocalDateTime occurredAt) {
        Objects.requireNonNull(appointmentId, "appointmentId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        // Epoch-second granularity. If two events on the same appointment share
        // the same second, the secondary tie-break by type ordinal in the
        // resolver's sort produces stable ordering.
        long epochSecond = occurredAt.atZone(MADRID_ZONE).toEpochSecond();
        return appointmentId + "-" + type.name() + "-" + epochSecond;
    }

    /**
     * Madrid wall-clock zone — pinned explicitly to decouple id generation from
     * the JVM system timezone. See {@link #generateId}.
     */
    private static final ZoneId MADRID_ZONE = ZoneId.of("Europe/Madrid");
}
