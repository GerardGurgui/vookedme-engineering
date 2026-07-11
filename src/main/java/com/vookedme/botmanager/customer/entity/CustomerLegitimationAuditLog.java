package com.vookedme.botmanager.customer.entity;

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
 * Append-only record of every customer channel legitimacy state transition.
 * One row per transition of {@code Customer.channelLegitimacyStatus}.
 *
 * <p>Follows the same append-only pattern as
 * {@link com.vookedme.botmanager.appointment.entity.AppointmentAuditLog} (published SC-2):
 * rows are never updated or deleted — the repository is insert-only and a database trigger
 * blocks UPDATE and DELETE operations. No {@code @Version} annotation is required because
 * there is no concurrent update path on append-only rows.
 *
 * <p><b>Data minimisation:</b> no direct PII — references the customer by id only; no phone
 * number, name, or free-text customer content. {@code attestationText} is the business owner's
 * attestation statement (Art. 13 legal basis evidence), not a customer data field.
 * {@code occurredAt} uses {@link OffsetDateTime} (TIMESTAMPTZ, unambiguous UTC), consistent
 * with the appointment audit log and distinct from the appointment domain's {@code LocalDateTime}.
 *
 * <p><b>Column strategy:</b> taxonomy columns ({@code eventType}, {@code triggeredBy}) are
 * stored as enum with a database CHECK constraint; state snapshot columns ({@code reason},
 * {@code origin}, {@code fromState}, {@code toState}) are stored as {@code String} without a
 * CHECK constraint, following the {@code previousStatus}/{@code newStatus} pattern in the
 * appointment audit log.
 *
 * <p>This class is the JPA mapping only. The write path lives in
 * {@code CustomerLegitimacyService}, not here.
 */
@Entity
@Table(name = "customer_legitimation_audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerLegitimationAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Customer reference (no FK) — the log survives customer deletion and anonymisation. */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private CustomerLegitimationEventType eventType;

    /** State snapshot before the transition (stored as String; no CHECK constraint). */
    @Column(name = "from_state", length = 20)
    private String fromState;

    /** State snapshot after the transition (stored as String; no CHECK constraint). */
    @Column(name = "to_state", length = 20)
    private String toState;

    /** Denial reason snapshot (stored as String). Values correspond to the {@link ReasonOfDeny} domain. */
    @Column(name = "reason", length = 30)
    private String reason;

    /** Legitimation origin snapshot (stored as String). Values correspond to the {@link OriginOfLegitimation} domain. */
    @Column(name = "origin", length = 30)
    private String origin;

    @Enumerated(EnumType.STRING)
    @Column(name = "triggered_by", nullable = false, length = 20)
    private SourceActor triggeredBy;

    /**
     * {@code User.id} — NOT NULL only for OWNER/ADMIN/EMPLOYEE; NULL for
     * CUSTOMER/BOT/SYSTEM/SCHEDULER. No FK constraint.
     */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    /**
     * Business owner attestation statement (Art. 13). Present only when
     * {@code origin = ATTESTATION}. This is a legal basis evidence record,
     * not customer personal data.
     */
    @Column(name = "attestation_text", columnDefinition = "TEXT")
    private String attestationText;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    /** Correlation with the bot conversational turn ({@code X-Turn-Id}), when applicable. */
    @Column(name = "turn_id")
    private UUID turnId;
}
