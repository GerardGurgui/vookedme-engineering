package com.vookedme.botmanager.appointment.entity;

import com.vookedme.botmanager.common.event.AppointmentEvent;
import com.vookedme.botmanager.common.event.SourceActor;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Append-only forensic audit record for appointment lifecycle events (ADR-002).
 *
 * <p>One row per {@link AppointmentEvent}, written synchronously inside the same
 * transaction as the business mutation that published the event. The audit row and the
 * business mutation commit atomically — <b>committed ⟺ audited</b> — with no forensic
 * gap possible. If the audit write fails, the business mutation rolls back. This is an
 * accepted trade-off: for a forensic log, a gap is worse than a rollback.
 *
 * <p>Rows are never updated or deleted. The repository is insert-only; a database trigger
 * blocks UPDATE and DELETE at the storage layer.
 *
 * <p><b>Data minimisation (ADR-002):</b> no direct PII stored — records reference
 * {@code appointmentId} rather than carrying customer name or phone. The {@code detail}
 * column holds structured JSON-as-text (diffs and flags only); it never contains free-text
 * content. {@code occurredAt} uses {@link OffsetDateTime} (TIMESTAMPTZ, unambiguous UTC)
 * as required for forensic evidence — the same convention as the consent audit table.
 *
 * <p>No {@code @Version} — concurrent update is impossible on append-only rows.
 */
@Entity
@Table(name = "appointment_audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appointment_id", nullable = false)
    private Long appointmentId;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private AppointmentEvent.Type eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "triggered_by", nullable = false, length = 20)
    private SourceActor triggeredBy;

    /**
     * {@code User.id} — NOT NULL only for OWNER / ADMIN / EMPLOYEE panel roles;
     * NULL for CUSTOMER / BOT / SYSTEM / SCHEDULER. Enforced by the
     * {@code chk_audit_actor_user_id} database constraint. No foreign key — the audit log
     * must remain intact even if a user record is later deactivated or deleted.
     */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "previous_status", length = 30)
    private String previousStatus;

    @Column(name = "new_status", length = 30)
    private String newStatus;

    /**
     * Structured JSON-as-text: metadata diffs and flags only. Never free-text content
     * (ADR-002 data minimisation). Built by {@code AppointmentAuditListener.buildDetail()}.
     */
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "correlation_id")
    private UUID correlationId;

    /**
     * Conversational turn identifier from the n8n orchestrator ({@code X-Turn-Id} header).
     * NULL except for mutations that originated inside a real bot webhook request that
     * carried the header. Forensic invariant: non-null value ⟺ real n8n turn — the backend
     * never synthesises this value. Semantically distinct from {@link #correlationId},
     * which tracks bulk business operations rather than conversational turns.
     */
    @Column(name = "turn_id")
    private UUID turnId;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;
}
