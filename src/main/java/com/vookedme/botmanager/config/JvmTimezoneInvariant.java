package com.vookedme.botmanager.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JVM timezone invariant guard (2026-05-20).
 *
 * <p>The system uses {@link LocalDateTime} semantically as "Madrid wall-clock
 * time" in multiple places — appointment datetime, reminder scheduling,
 * rate-limit windows, expiration jobs, notification deduplication, and past-
 * unresolved scanners. If the JVM runs in a different timezone (Docker default
 * is UTC), all calculations of "now", "N hours ago", "in N hours" shift by the
 * Madrid-to-JVM offset, typically +2h in CEST or +1h in CET.
 *
 * <p><b>Production incident that motivated this guard (2026-05-20):</b> a
 * 24-hour-before reminder fired 2 hours late because the Docker container was
 * in UTC and the JVM timezone environment variable was not set. Without this
 * guard, the system starts silently with the wrong timezone — "now" looks
 * correct in logs but all scheduled work is offset. The guard converts a
 * silent misconfiguration into a startup failure.
 *
 * <p><b>Activation:</b> controlled by {@code app.timezone.invariant.enabled}
 * (default {@code true}). Set to {@code false} in {@code application-test.yml}
 * so that CI integration tests running in UTC are not affected. This is the
 * correct test-isolation mechanism: the guard exists for production safety,
 * not for test correctness.
 *
 * <p><b>Timezone alias tolerance:</b> Docker containers sometimes set
 * {@code TZ=CET} instead of {@code TZ=Europe/Madrid}. Although
 * {@code ZoneId.equals} returns {@code false} for different IDs,
 * the runtime offset may be identical (+01:00 CET / +02:00 CEST with the same
 * DST rules). Equivalent aliases are accepted with a WARN log recommending
 * the canonical IANA name. The guard hard-fails only when the runtime offset
 * does not match Madrid at the moment of startup.
 *
 * <p><b>Diagnostic surface:</b> implements {@link InfoContributor} — the timezone
 * state is visible at {@code GET /actuator/info} at any time, without log access:
 * <pre>{@code
 * {
 *   "timezone": {
 *     "jvmZone": "Europe/Madrid",
 *     "jvmNow": "2026-05-20T15:42:33",
 *     "utcNow": "2026-05-20T13:42:33Z",
 *     "offset": "+02:00",
 *     "match": "exact" | "alias" | (never "mismatch" — that throws)
 *   }
 * }
 * }</pre>
 *
 * <p>Fix for a misconfigured deployment: set the environment variable
 * {@code JAVA_TOOL_OPTIONS=-Duser.timezone=Europe/Madrid} before rebuild.
 *
 * @see ADR-008 — Timestamp Timezone Migration; this guard is the enforcement
 *      mechanism for the interim mitigation described there.
 */
@Component
@ConditionalOnProperty(
        value = "app.timezone.invariant.enabled",
        havingValue = "true",
        matchIfMissing = true
)
@Slf4j
public class JvmTimezoneInvariant implements InfoContributor {

    public static final ZoneId REQUIRED = ZoneId.of("Europe/Madrid");

    private String matchType; // "exact" | "alias" | (never "mismatch" — that throws)

    @PostConstruct
    public void verifyTimezone() {
        ZoneId actual = ZoneId.systemDefault();
        LocalDateTime jvmNow = LocalDateTime.now();
        Instant utcNow = Instant.now();
        OffsetDateTime offsetNow = OffsetDateTime.now(actual);

        log.info("[invariant] JVM timezone: {}", actual);
        log.info("[invariant] JVM now (local wall-clock): {}", jvmNow);
        log.info("[invariant] Instant now (UTC):          {}", utcNow);
        log.info("[invariant] Current offset:             {}", offsetNow.getOffset());

        if (REQUIRED.equals(actual)) {
            this.matchType = "exact";
            log.info("[invariant] JVM timezone OK (exact match Europe/Madrid)");
            return;
        }

        // Tolerance for equivalent aliases (CET, CEST, +01:00, etc.).
        // Compare the runtime offset of the default zone against Madrid right now.
        // If they match, the zones are functionally equivalent — accept but WARN.
        OffsetDateTime madridNow = OffsetDateTime.now(REQUIRED);
        if (offsetNow.getOffset().equals(madridNow.getOffset())) {
            this.matchType = "alias";
            log.warn("[invariant] JVM timezone is {} (current offset matches Europe/Madrid {}). " +
                            "Acceptable but RECOMMEND setting JAVA_TOOL_OPTIONS=-Duser.timezone=Europe/Madrid " +
                            "for the explicit IANA timezone name and correct DST handling year-round.",
                    actual, madridNow.getOffset());
            return;
        }

        // Hard fail — offset does not match.
        throw new IllegalStateException(String.format(
                "JVM timezone MUST be %s (or an equivalent offset) for correct LocalDateTime " +
                        "semantics in reminders, rate limits, expiration jobs and scheduling. " +
                        "Current default: %s (offset %s vs Madrid %s). " +
                        "Fix: set env var JAVA_TOOL_OPTIONS=-Duser.timezone=Europe/Madrid " +
                        "before rebuild.",
                REQUIRED, actual, offsetNow.getOffset(), madridNow.getOffset()
        ));
    }

    @Override
    public void contribute(Info.Builder builder) {
        ZoneId actual = ZoneId.systemDefault();
        LocalDateTime jvmNow = LocalDateTime.now();
        Instant utcNow = Instant.now();
        OffsetDateTime offsetNow = OffsetDateTime.now(actual);

        Map<String, Object> tz = new LinkedHashMap<>();
        tz.put("jvmZone", actual.toString());
        tz.put("jvmNow", jvmNow.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        tz.put("utcNow", utcNow.toString());
        tz.put("offset", offsetNow.getOffset().toString());
        tz.put("requiredZone", REQUIRED.toString());
        tz.put("match", matchType != null ? matchType : "unknown");

        builder.withDetail("timezone", tz);
    }
}
