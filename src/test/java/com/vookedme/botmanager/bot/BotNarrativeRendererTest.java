package com.vookedme.botmanager.bot;

import com.vookedme.botmanager.bot.domain.BotEvent;
import com.vookedme.botmanager.bot.domain.BotEventType;
import com.vookedme.botmanager.bot.domain.BotNarrativeContext;
import com.vookedme.botmanager.bot.service.BotNarrativeRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BotNarrativeRenderer}, covering:
 *
 * <ol>
 *   <li><b>Coverage</b> — every {@link BotEventType} produces a non-empty
 *       narrative via {@code @EnumSource} sweep.</li>
 *   <li><b>Locale narrative templates</b> — exact Spanish string assertions for
 *       each template. These strings are locale output ({@code es_ES}) rendered
 *       in the bot activity feed; they are verified as-is and not translated.</li>
 *   <li><b>Defensive fallback paths</b> — null/blank customer, null offering,
 *       null datetime, missing reason, missing {@code previous_cancel_actor}.</li>
 *   <li><b>Purity and API contract</b> — structural reflection verifying that
 *       the class is final, has no Spring annotations, no instance fields, and
 *       that all public methods are static.</li>
 * </ol>
 *
 * <p>Note on Spanish strings: all string literals in the
 * "Locale narrative templates" section are intentional locale output
 * ({@code es_ES}) for Spanish-speaking business operators. They mirror
 * the exact constants in {@link BotNarrativeRenderer} and must be verified
 * verbatim — translating them would defeat the purpose of the test.
 */
@DisplayName("BotNarrativeRenderer — locale narrative rendering and purity guarantees")
class BotNarrativeRendererTest {

    // ─── Shared fixtures ─────────────────────────────────────────────────────

    private static final LocalDateTime DT = LocalDateTime.of(2026, 6, 15, 10, 30);

    /**
     * Sample context with generic customer and offering names used for the
     * majority of tests. Customer name "María" and offering "Corte de pelo"
     * are synthetic fixtures; they do not identify any real customer.
     */
    private static final BotNarrativeContext SAMPLE_CONTEXT =
            BotNarrativeContext.of("María", "Corte de pelo", DT);

    // ─── Minimal BotEvent builder ─────────────────────────────────────────────

    /**
     * Builds a minimal {@link BotEvent} for the given type. All optional
     * fields are null/empty unless overridden per-test.
     */
    private static BotEvent eventOf(BotEventType type) {
        return new BotEvent(
                "1-" + type.name() + "-0",  // id
                DT,                          // occurredAt
                type,                        // type
                "BOT",                       // actorType
                null,                        // actorUserId
                1L,                          // appointmentId
                null,                        // result
                null,                        // reason
                Map.of()                     // metadata
        );
    }

    private static BotEvent eventWith(BotEventType type, String reason, Map<String, Object> metadata) {
        return new BotEvent(
                "1-" + type.name() + "-0",
                DT,
                type,
                "OWNER",
                42L,
                1L,
                null,
                reason,
                metadata
        );
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. Coverage — every BotEventType must produce a non-empty string
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Full coverage — every BotEventType yields a non-empty narrative")
    class FullCoverage {

        @ParameterizedTest(name = "{0} → non-empty narrative")
        @EnumSource(BotEventType.class)
        void everyTypeProducesNarrative(BotEventType type) {
            String narrative = BotNarrativeRenderer.render(eventOf(type), SAMPLE_CONTEXT);
            assertThat(narrative)
                    .as("Narrative for %s must not be null or empty", type)
                    .isNotNull()
                    .isNotBlank();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. Locale narrative templates — exact strings verified (es_ES)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Locale narrative templates — exact strings verified")
    class LocaleNarrativeTemplates {

        // Note: all assertThat().isEqualTo() calls in this section compare against
        // locale output strings (Spanish, es_ES). These are the verbatim strings
        // displayed in the bot activity feed for Spanish-speaking operators.

        @Test
        @DisplayName("BOT_PROPOSED — standard (post-cutoff) narrative")
        void botProposed_standard() {
            // Locale output (Spanish, es_ES): verifying exact narrative strings displayed to operators
            assertThat(BotNarrativeRenderer.render(eventOf(BotEventType.BOT_PROPOSED), SAMPLE_CONTEXT))
                    .isEqualTo("El bot creó una cita para María (Corte de pelo, 15/06/2026 10:30). Esperando tu aprobación.");
        }

        @Test
        @DisplayName("BOT_PROPOSED — pre-cutoff fallback appends audit note")
        void botProposed_preAuditFallback() {
            // Locale output (Spanish, es_ES): verifying exact narrative strings displayed to operators
            BotEvent event = eventWith(BotEventType.BOT_PROPOSED, null, Map.of("pre_v69", true));
            assertThat(BotNarrativeRenderer.render(event, SAMPLE_CONTEXT))
                    .isEqualTo("El bot creó una cita para María (Corte de pelo, 15/06/2026 10:30). Esperando tu aprobación."
                            + " (aprobada antes de la actualización del sistema — sin registro detallado)");
        }

        @Test
        @DisplayName("BOT_AUTO_CONFIRMED — auto-confirm creation narrative")
        void botAutoConfirmed() {
            // Locale output (Spanish, es_ES): verifying exact narrative strings displayed to operators
            assertThat(BotNarrativeRenderer.render(eventOf(BotEventType.BOT_AUTO_CONFIRMED), SAMPLE_CONTEXT))
                    .isEqualTo("El bot creó una cita para María (Corte de pelo, 15/06/2026 10:30). Confirmada automáticamente.");
        }

        @Test
        @DisplayName("OWNER_APPROVED — approval narrative")
        void ownerApproved() {
            // Locale output (Spanish, es_ES): verifying exact narrative strings displayed to operators
            assertThat(BotNarrativeRenderer.render(eventOf(BotEventType.OWNER_APPROVED), SAMPLE_CONTEXT))
                    .isEqualTo("Aprobaste la cita de María (Corte de pelo, 15/06/2026 10:30).");
        }

        @Test
        @DisplayName("OWNER_REJECTED — rejection narrative with explicit reason")
        void ownerRejected_withReason() {
            // Locale output (Spanish, es_ES): verifying exact narrative strings displayed to operators
            BotEvent event = eventWith(BotEventType.OWNER_REJECTED, "Fuera de horario", Map.of());
            assertThat(BotNarrativeRenderer.render(event, SAMPLE_CONTEXT))
                    .isEqualTo("Rechazaste la cita de María. Motivo: \"Fuera de horario\".");
        }

        @Test
        @DisplayName("OWNER_REJECTED — rejection narrative with no-reason fallback")
        void ownerRejected_noReason() {
            // Locale output (Spanish, es_ES): verifying exact narrative strings displayed to operators
            assertThat(BotNarrativeRenderer.render(eventOf(BotEventType.OWNER_REJECTED), SAMPLE_CONTEXT))
                    .isEqualTo("Rechazaste la cita de María. Motivo: \"sin motivo indicado\".");
        }

        @Test
        @DisplayName("BOT_PENDING_EXPIRED — timeout narrative")
        void botPendingExpired() {
            // Locale output (Spanish, es_ES): verifying exact narrative strings displayed to operators
            assertThat(BotNarrativeRenderer.render(eventOf(BotEventType.BOT_PENDING_EXPIRED), SAMPLE_CONTEXT))
                    .isEqualTo("El bot esperaba tu aprobación pero el tiempo expiró. La cita de María fue liberada.");
        }

        @Test
        @DisplayName("BOT_CANCEL_REQUESTED — cancellation request narrative")
        void botCancelRequested() {
            // Locale output (Spanish, es_ES): verifying exact narrative strings displayed to operators
            assertThat(BotNarrativeRenderer.render(eventOf(BotEventType.BOT_CANCEL_REQUESTED), SAMPLE_CONTEXT))
                    .isEqualTo("María solicitó cancelar su cita (Corte de pelo, 15/06/2026 10:30).");
        }

        @Test
        @DisplayName("OWNER_APPROVED_CANCEL — approved cancellation request narrative")
        void ownerApprovedCancel() {
            // Locale output (Spanish, es_ES): verifying exact narrative strings displayed to operators
            assertThat(BotNarrativeRenderer.render(eventOf(BotEventType.OWNER_APPROVED_CANCEL), SAMPLE_CONTEXT))
                    .isEqualTo("Aprobaste la cancelación solicitada por María.");
        }

        @Test
        @DisplayName("OWNER_REJECTED_CANCEL — rejected cancellation request narrative with reason")
        void ownerRejectedCancel_withReason() {
            // Locale output (Spanish, es_ES): verifying exact narrative strings displayed to operators
            BotEvent event = eventWith(BotEventType.OWNER_REJECTED_CANCEL, "Cita ya cobrada", Map.of());
            assertThat(BotNarrativeRenderer.render(event, SAMPLE_CONTEXT))
                    .isEqualTo("Rechazaste la cancelación de María. Motivo: \"Cita ya cobrada\".");
        }

        @Test
        @DisplayName("CR_TIMEOUT_EXPIRED — cancellation request timeout narrative")
        void crTimeoutExpired() {
            // Locale output (Spanish, es_ES): verifying exact narrative strings displayed to operators
            assertThat(BotNarrativeRenderer.render(eventOf(BotEventType.CR_TIMEOUT_EXPIRED), SAMPLE_CONTEXT))
                    .isEqualTo("La solicitud de cancelación de María expiró sin decisión. La cita sigue confirmada.");
        }

        @Test
        @DisplayName("BOT_CANCELLED — bot direct-cancel narrative")
        void botCancelled() {
            // Locale output (Spanish, es_ES): verifying exact narrative strings displayed to operators
            assertThat(BotNarrativeRenderer.render(eventOf(BotEventType.BOT_CANCELLED), SAMPLE_CONTEXT))
                    .isEqualTo("El bot canceló la cita de María (Corte de pelo, 15/06/2026 10:30).");
        }

        @Test
        @DisplayName("BOT_REVOKED — revoke narrative with OWNER previous actor")
        void botRevoked_ownerPreviousActor() {
            // Locale output (Spanish, es_ES): verifying exact narrative strings displayed to operators
            BotEvent event = eventWith(BotEventType.BOT_REVOKED, null, Map.of("previous_cancel_actor", "OWNER"));
            assertThat(BotNarrativeRenderer.render(event, SAMPLE_CONTEXT))
                    .isEqualTo("Reactivaste la cita de María (estaba cancelada por ti, ahora confirmada de nuevo).");
        }

        @Test
        @DisplayName("BOT_REVOKED — revoke narrative with BOT previous actor")
        void botRevoked_botPreviousActor() {
            // Locale output (Spanish, es_ES): verifying exact narrative strings displayed to operators
            BotEvent event = eventWith(BotEventType.BOT_REVOKED, null, Map.of("previous_cancel_actor", "BOT"));
            assertThat(BotNarrativeRenderer.render(event, SAMPLE_CONTEXT))
                    .isEqualTo("Reactivaste la cita de María (estaba cancelada por el bot, ahora confirmada de nuevo).");
        }

        @Test
        @DisplayName("BOT_REVOKED — revoke narrative with SYSTEM previous actor")
        void botRevoked_systemPreviousActor() {
            // Locale output (Spanish, es_ES): verifying exact narrative strings displayed to operators
            BotEvent event = eventWith(BotEventType.BOT_REVOKED, null, Map.of("previous_cancel_actor", "SYSTEM"));
            assertThat(BotNarrativeRenderer.render(event, SAMPLE_CONTEXT))
                    .isEqualTo("Reactivaste la cita de María (estaba cancelada por el sistema, ahora confirmada de nuevo).");
        }

        @Test
        @DisplayName("BOT_REVOKED — missing previous_cancel_actor key falls back to system string")
        void botRevoked_missingPreviousActor() {
            // Locale output (Spanish, es_ES): verifying exact narrative strings displayed to operators
            BotEvent event = eventOf(BotEventType.BOT_REVOKED);
            assertThat(BotNarrativeRenderer.render(event, SAMPLE_CONTEXT))
                    .isEqualTo("Reactivaste la cita de María (estaba cancelada por el sistema, ahora confirmada de nuevo).");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. Defensive fallback paths
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Defensive fallbacks — missing or null fields")
    class DefensiveFallbacks {

        @Test
        @DisplayName("Null customerName → locale-specific deleted-customer placeholder")
        void nullCustomerName_usesFallback() {
            BotNarrativeContext ctx = BotNarrativeContext.of(null, "Corte de pelo", DT);
            String narrative = BotNarrativeRenderer.render(eventOf(BotEventType.BOT_AUTO_CONFIRMED), ctx);
            // Locale output (Spanish, es_ES): [Cliente borrado] is the fallback for erased customers
            assertThat(narrative).contains("[Cliente borrado]");
        }

        @Test
        @DisplayName("Blank customerName → locale-specific deleted-customer placeholder")
        void blankCustomerName_usesFallback() {
            BotNarrativeContext ctx = BotNarrativeContext.of("   ", "Corte de pelo", DT);
            String narrative = BotNarrativeRenderer.render(eventOf(BotEventType.BOT_PROPOSED), ctx);
            // Locale output (Spanish, es_ES): [Cliente borrado] is the fallback for erased customers
            assertThat(narrative).contains("[Cliente borrado]");
        }

        @Test
        @DisplayName("Null offeringName → locale-specific generic offering fallback")
        void nullOffering_usesFallback() {
            BotNarrativeContext ctx = BotNarrativeContext.of("María", null, DT);
            String narrative = BotNarrativeRenderer.render(eventOf(BotEventType.BOT_AUTO_CONFIRMED), ctx);
            // Locale output (Spanish, es_ES): "tu servicio" is the generic service fallback
            assertThat(narrative).contains("tu servicio");
        }

        @Test
        @DisplayName("Null datetime → locale-specific unregistered date fallback")
        void nullDatetime_usesFallback() {
            BotNarrativeContext ctx = BotNarrativeContext.of("María", "Corte de pelo", null);
            String narrative = BotNarrativeRenderer.render(eventOf(BotEventType.BOT_AUTO_CONFIRMED), ctx);
            // Locale output (Spanish, es_ES): "fecha sin registrar" is the missing-datetime fallback
            assertThat(narrative).contains("fecha sin registrar");
        }

        @Test
        @DisplayName("Null reason on OWNER_REJECTED → locale-specific no-reason fallback")
        void nullReason_usesFallback() {
            String narrative = BotNarrativeRenderer.render(
                    eventOf(BotEventType.OWNER_REJECTED), SAMPLE_CONTEXT);
            // Locale output (Spanish, es_ES): "sin motivo indicado" is the no-reason fallback
            assertThat(narrative).contains("sin motivo indicado");
        }

        @Test
        @DisplayName("Null event → NullPointerException")
        void nullEvent_throws() {
            assertThatThrownBy(() -> BotNarrativeRenderer.render(null, SAMPLE_CONTEXT))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Null context → NullPointerException")
        void nullContext_throws() {
            assertThatThrownBy(() ->
                    BotNarrativeRenderer.render(eventOf(BotEventType.BOT_PROPOSED), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. Purity and API contract
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Purity and API contract — class shape")
    class PurityAndContract {

        @Test
        @DisplayName("Class is final — cannot be subclassed to introduce side effects")
        void classIsFinal() {
            assertThat(Modifier.isFinal(BotNarrativeRenderer.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("No Spring stereotype annotations")
        void noSpringAnnotations() {
            for (Annotation a : BotNarrativeRenderer.class.getAnnotations()) {
                String name = a.annotationType().getName();
                assertThat(name)
                        .doesNotStartWith("org.springframework.stereotype")
                        .doesNotEndWith(".Service")
                        .doesNotEndWith(".Component");
            }
        }

        @Test
        @DisplayName("No instance fields — only static final constants allowed")
        void noInstanceFields() {
            for (Field f : BotNarrativeRenderer.class.getDeclaredFields()) {
                if (f.isSynthetic()) continue;
                int mods = f.getModifiers();
                assertThat(Modifier.isStatic(mods))
                        .as("Field %s must be static", f.getName())
                        .isTrue();
                assertThat(Modifier.isFinal(mods))
                        .as("Static field %s must be final", f.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("All public methods are static — render() must not require an instance")
        void allPublicMethodsStatic() {
            List<Method> publicMethods = Arrays.stream(BotNarrativeRenderer.class.getDeclaredMethods())
                    .filter(m -> Modifier.isPublic(m.getModifiers()))
                    .toList();
            assertThat(publicMethods).isNotEmpty();
            for (Method m : publicMethods) {
                assertThat(Modifier.isStatic(m.getModifiers()))
                        .as("Public method %s must be static", m.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("Private constructor prevents instantiation (utility class lock)")
        void privateConstructor_throws() throws Exception {
            var ctor = BotNarrativeRenderer.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            assertThatThrownBy(ctor::newInstance)
                    .hasCauseInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Deterministic: same event + context → same output across invocations")
        void deterministic() {
            BotEvent event = eventOf(BotEventType.OWNER_APPROVED);
            String first = BotNarrativeRenderer.render(event, SAMPLE_CONTEXT);
            String second = BotNarrativeRenderer.render(event, SAMPLE_CONTEXT);
            assertThat(first).isEqualTo(second);
        }
    }
}
