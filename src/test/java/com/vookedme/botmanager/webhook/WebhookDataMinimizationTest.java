package com.vookedme.botmanager.webhook;

import com.vookedme.botmanager.webhook.controller.WebhookController;
import com.vookedme.botmanager.webhook.dto.WebhookAppointmentResponse;
import com.vookedme.botmanager.webhook.dto.WebhookCustomerResponse;
import com.vookedme.botmanager.webhook.dto.WebhookOfferingResponse;
import com.vookedme.botmanager.webhook.dto.WebhookScheduleResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Petrification of ADR-014 / ADR-015 data minimisation on the bot webhook
 * surface (Art. 5.1.c GDPR).
 *
 * <p>Fails if a sensitive field reappears on the slim bot DTOs, or if any
 * {@code /api/webhook/*} endpoint returns the fat panel DTOs
 * ({@code appointment.dto.AppointmentResponse} /
 * {@code customer.dto.CustomerResponse}) — which would re-expose
 * sensitive fields to the LLM flow.
 *
 * <p>The invariant being enforced: the bot's webhook surface exposes only the
 * operational fields required to manage the conversation. Fields like
 * {@code customerServiceNotes}, {@code email}, {@code paid}, and audit
 * timestamps must not reach the language model.
 *
 * <p>{@link WebhookController}, {@link WebhookAppointmentResponse},
 * {@link WebhookCustomerResponse}, {@link WebhookOfferingResponse}, and
 * {@link WebhookScheduleResponse} are published in a subsequent source batch.
 *
 * @see <a href="../../docs/adr/ADR-014-bot-data-minimisation-and-audit-log.md">ADR-014</a>
 * @see <a href="../../docs/adr/ADR-015-art9-gdpr-minimisation-conversational-flow.md">ADR-015</a>
 */
class WebhookDataMinimizationTest {

    /** Fields that must NEVER be declared on a bot-facing DTO. */
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "customerServiceNotes", "serviceNotes", "notes",
            "email", "paid", "paymentMethod", "paidChangedByUserId",
            "cancellationReason", "cancellationRequestReason",
            "cancellationRequestedByUserId", "cancellationRequestDecisionReason",
            "createdBy", "businessId", "customerId",
            // Internal audit timestamps must not leak to the bot/LLM surface.
            "createdAt", "updatedAt"
    );

    private static final Set<String> FORBIDDEN_JSON_KEYS = Set.of(
            "customerServiceNotes", "serviceNotes", "notes", "email"
    );

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void webhookAppointmentResponse_doesNotDeclareForbiddenFields() {
        assertNoForbiddenFields(WebhookAppointmentResponse.class);
    }

    @Test
    void webhookCustomerResponse_doesNotDeclareForbiddenFields() {
        assertNoForbiddenFields(WebhookCustomerResponse.class);
    }

    @Test
    void webhookOfferingResponse_doesNotDeclareForbiddenFields() {
        // businessId is RETAINED on offering/schedule bot DTOs (non-sensitive
        // internal id; required by the orchestration layer contract).
        // The audit timestamp createdAt stays forbidden.
        assertNoForbiddenFields(WebhookOfferingResponse.class, Set.of("businessId"));
    }

    @Test
    void webhookScheduleResponse_doesNotDeclareForbiddenFields() {
        // businessId RETAINED; updatedAt stays forbidden.
        assertNoForbiddenFields(WebhookScheduleResponse.class, Set.of("businessId"));
    }

    private void assertNoForbiddenFields(Class<?> dto) {
        assertNoForbiddenFields(dto, Set.of());
    }

    private void assertNoForbiddenFields(Class<?> dto, Set<String> allowed) {
        Set<String> forbidden = FORBIDDEN_FIELDS.stream()
                .filter(f -> !allowed.contains(f))
                .collect(Collectors.toSet());
        List<String> declared = Arrays.stream(dto.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toList());
        assertThat(declared)
                .as("%s must not declare any sensitive field", dto.getSimpleName())
                .doesNotContainAnyElementsOf(forbidden);
    }

    @Test
    void webhookAppointmentResponse_serialisesWithoutSensitiveKeys() throws Exception {
        WebhookAppointmentResponse dto = WebhookAppointmentResponse.builder()
                .id(1L)
                .offeringName("Corte de pelo")
                .serviceNameSnapshot("Corte de pelo")
                .price(new BigDecimal("20.00"))
                .durationMinutes(30)
                .employeeName("Ana")
                .build();
        assertJsonHasNoSensitiveKeys(dto);
    }

    @Test
    void webhookCustomerResponse_serialisesWithoutSensitiveKeys() throws Exception {
        WebhookCustomerResponse dto = WebhookCustomerResponse.builder()
                .id(1L)
                .phone("+34600000000")
                .name("Ana")
                .lastName("García")
                .active(true)
                .build();
        assertJsonHasNoSensitiveKeys(dto);
    }

    private void assertJsonHasNoSensitiveKeys(Object dto) throws Exception {
        String json = mapper.writeValueAsString(dto);
        for (String key : FORBIDDEN_JSON_KEYS) {
            assertThat(json)
                    .as("serialised bot DTO must not contain key '%s'", key)
                    .doesNotContain(key);
        }
    }

    /**
     * No public {@link WebhookController} endpoint may return the fat panel DTOs.
     * Checks the generic return type (covers {@code List<...>} wrappers).
     *
     * <p>This is a structural guard: if any webhook endpoint returns the full
     * panel DTO, the ADR-014 minimisation boundary is broken at the type level.
     */
    @Test
    void noWebhookEndpointReturnsFatPanelDtos() {
        for (Method m : WebhookController.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            String returnType = m.getGenericReturnType().getTypeName();
            assertThat(returnType)
                    .as("WebhookController.%s must not return the fat AppointmentResponse", m.getName())
                    .doesNotContain("appointment.dto.AppointmentResponse");
            assertThat(returnType)
                    .as("WebhookController.%s must not return the fat CustomerResponse", m.getName())
                    .doesNotContain("customer.dto.CustomerResponse");
            assertThat(returnType)
                    .as("WebhookController.%s must not return the fat OfferingResponse", m.getName())
                    .doesNotContain("offering.dto.OfferingResponse");
            assertThat(returnType)
                    .as("WebhookController.%s must not return the fat ScheduleResponse", m.getName())
                    .doesNotContain("schedule.dto.ScheduleResponse");
        }
    }
}
