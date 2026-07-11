package com.vookedme.botmanager.appointment.audit;

import com.vookedme.botmanager.appointment.entity.AppointmentAuditLog;
import com.vookedme.botmanager.appointment.repository.AppointmentAuditLogRepository;
import com.vookedme.botmanager.common.event.AppointmentEvent;
import com.vookedme.botmanager.common.event.SourceActor;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forensic audit listener for the appointment lifecycle (ADR-002).
 *
 * <p><b>Transactional model — same transaction.</b> This is a synchronous
 * {@link EventListener} — not a {@code @TransactionalEventListener}. It executes on the
 * same thread, inside the transaction of the {@code @Transactional} method that published
 * the {@link AppointmentEvent}. The {@code save()} participates in that transaction:
 * the audit row and the business mutation commit together or roll back together —
 * <b>committed ⟺ audited</b>. A forensic gap cannot exist without a corresponding gap in
 * the business data. If this write fails, the business mutation rolls back. Accepted
 * trade-off: for a forensic log, a gap is worse than a rollback.
 *
 * <p><b>Actor attribution.</b> Reads {@code triggeredBy} and {@code actorUserId} captured
 * in the event at the moment of mutation (explicit parameters, not thread-local). Defensive
 * fallback to {@code SYSTEM} if {@code triggeredBy} is null; {@code actorUserId} is forced
 * to null for non-panel roles — enforcing the {@code chk_audit_actor_user_id} invariant at
 * the application layer in addition to the database constraint.
 *
 * <p><b>Data minimisation (ADR-002).</b> Does not persist {@code customerName} or
 * {@code customerPhone} — the audit row references via {@code appointmentId}. The
 * cancellation reason is reduced to a structured {@code {reasonProvided, reasonOrigin}}
 * entry in the {@code detail} column (JSON-as-text), without the reason content itself.
 *
 * <p>{@code AppointmentAuditLogRepository} will be published in a subsequent source batch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AppointmentAuditListener {

    private final AppointmentAuditLogRepository auditRepository;
    private final ObjectMapper objectMapper;

    @EventListener
    public void onAppointmentEvent(AppointmentEvent event) {
        SourceActor triggeredBy = event.getTriggeredBy() != null
                ? event.getTriggeredBy()
                : SourceActor.SYSTEM;

        // Enforce chk_audit_actor_user_id at the application layer: userId only for panel roles.
        Long actorUserId = isPanelRole(triggeredBy) ? event.getActorUserId() : null;

        AppointmentAuditLog row = AppointmentAuditLog.builder()
                .appointmentId(event.getAppointmentId())
                .businessId(event.getBusinessId())
                .eventType(event.getType())
                .triggeredBy(triggeredBy)
                .actorUserId(actorUserId)
                .previousStatus(event.getPreviousStatus())
                .newStatus(event.getStatus())
                .detail(buildDetail(event, triggeredBy))
                .correlationId(event.getCorrelationId())
                .turnId(event.getTurnId())
                .occurredAt(OffsetDateTime.now())
                .build();

        auditRepository.save(row);
    }

    private boolean isPanelRole(SourceActor actor) {
        return actor == SourceActor.OWNER
                || actor == SourceActor.ADMIN
                || actor == SourceActor.EMPLOYEE;
    }

    /**
     * Builds the structured JSON-as-text {@code detail} value: metadata diffs and flags
     * only, never free-text content (ADR-002 data minimisation). Returns null when there
     * is no relevant supplementary detail. A serialisation exception must not abort the
     * enclosing business transaction — the core audit fields (who, what, when) are already
     * in the named columns; {@code detail} is supplementary and written on a best-effort
     * basis.
     */
    private String buildDetail(AppointmentEvent event, SourceActor triggeredBy) {
        Map<String, Object> detail = new LinkedHashMap<>();
        switch (event.getType()) {
            case CANCELLED, CANCELLATION_REQUESTED -> {
                boolean reasonProvided = event.getReason() != null && !event.getReason().isBlank();
                detail.put("reasonProvided", reasonProvided);
                if (reasonProvided) {
                    // Record the actor origin of the reason, never the reason content (GDPR Art. 9).
                    detail.put("reasonOrigin", triggeredBy.name());
                }
            }
            case EMPLOYEE_ASSIGNED -> detail.put("employeeAssigned", true);
            case EMPLOYEE_UNASSIGNED -> detail.put("employeeUnassigned", true);
            case PAID_TOGGLED -> detail.put("operation", "paid_toggle");
            default -> { /* no additional detail — event_type + status capture the essentials */ }
        }
        if (detail.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            log.warn("Audit detail serialisation failed for appointment {} event {} — storing null detail",
                    event.getAppointmentId(), event.getType(), e);
            return null;
        }
    }
}
