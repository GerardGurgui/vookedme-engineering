package com.vookedme.botmanager.customer.legitimation;

import com.vookedme.botmanager.common.event.CustomerOptOutEvent; // published in a subsequent source batch
import com.vookedme.botmanager.common.event.SourceActor;
import com.vookedme.botmanager.common.exception.ConflictException;
import com.vookedme.botmanager.customer.entity.Customer;
import com.vookedme.botmanager.customer.entity.CustomerLegitimationAuditLog;
import com.vookedme.botmanager.customer.entity.CustomerLegitimationEventType;
import com.vookedme.botmanager.customer.entity.OriginOfLegitimation;
import com.vookedme.botmanager.customer.entity.ReasonOfDeny;
import com.vookedme.botmanager.customer.repository.CustomerLegitimationAuditLogRepository;
import com.vookedme.botmanager.customer.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Transactional write unit for channel legitimacy transitions. Package-private — internal
 * to {@link CustomerLegitimacyService}, which is the sole public façade for legitimation
 * writes. No class outside {@code customer.legitimation} can call this writer directly.
 *
 * <p>Each method applies a single idempotent transition within a single transaction:
 * re-reads the {@link Customer} fresh by id, calculates the transition as a function
 * of current state, mutates the four legitimation fields respecting the domain invariant
 * ({@code true ⟺ origin != null, reason = null}; {@code false ⟺ reason != null, origin = null}),
 * and appends an audit log row — both writes committed atomically (committed ⟺ audited).
 * The {@code @Version} field on {@code Customer} detects concurrent modification; the resulting
 * {@code ObjectOptimisticLockingFailureException} is handled by
 * {@link CustomerLegitimacyService} with a bounded, idempotent retry.
 *
 * <p><b>Precedence rule:</b> a generic bot inbound does NOT reactivate an explicit customer
 * opt-out. {@link #legitimateFromBotTx} over {@code CUSTOMER_EXPLICIT_STOP} is a silent
 * no-op; reactivation of an opt-out requires the deliberate {@link #reactivateByCustomerTx}
 * action. {@link #attestTx} over a customer-initiated denial throws {@link ConflictException}
 * (the business owner cannot override a customer opt-out, Art. 21).
 *
 * <p>{@code CustomerOptOutEvent} — published by {@link #optOutTx} via
 * {@code ApplicationEventPublisher} — is received by a {@code @TransactionalEventListener(AFTER_COMMIT)}
 * so that notification delivery is tied to the committing transaction. An optimistic lock
 * failure rolls back the transaction and discards the event; the idempotent retry lands in
 * the no-op guard and produces no duplicate. The event type is published in a subsequent
 * source batch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class LegitimacyTransactionalWriter {

    private final CustomerRepository customerRepository;
    private final CustomerLegitimationAuditLogRepository auditRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Auto-legitimation via customer inbound ({@code triggeredBy = CUSTOMER},
     * {@code origin = BOT_ORIGIN}).
     *
     * <p>State transitions: {@code null → LEGITIMATED}; {@code false + NEVER_LEGITIMATED → REACTIVATED}.
     * Already {@code true} → no-op (idempotent). {@code false + CUSTOMER_EXPLICIT_STOP} or
     * {@code BLOCKED_BY_CUSTOMER} → silent no-op (a generic inbound does not reactivate
     * an explicit opt-out; only the deliberate {@link #reactivateByCustomerTx} can do so).
     */
    @Transactional
    void legitimateFromBotTx(Long customerId) {
        Customer customer = customerRepository.findById(customerId).orElse(null);
        if (customer == null) {
            log.warn("[legitimation] legitimateFromBot: customer {} not found — no-op", customerId);
            return;
        }
        Boolean status = customer.getChannelLegitimacyStatus();
        if (Boolean.TRUE.equals(status)) {
            return; // already legitimate — idempotent, no audit row
        }
        if (Boolean.FALSE.equals(status) && isCustomerInitiatedDeny(customer.getReasonOfDeny())) {
            // A generic inbound does not reactivate a customer-initiated opt-out or platform block.
            return;
        }
        // null (UNEVALUATED) → LEGITIMATED ; false + NEVER_LEGITIMATED → REACTIVATED. Both with BOT_ORIGIN.
        LegitimationState from = LegitimationState.of(status);
        CustomerLegitimationEventType event = (from == LegitimationState.UNEVALUATED)
                ? CustomerLegitimationEventType.LEGITIMATED
                : CustomerLegitimationEventType.REACTIVATED;
        applyLegitimation(customer, OriginOfLegitimation.BOT_ORIGIN, event, from,
                SourceActor.CUSTOMER, null, null);
    }

    /**
     * Deliberate customer reactivation (opt-in / START signal) after a denial — the only
     * path that reactivates a {@code CUSTOMER_EXPLICIT_STOP}.
     *
     * <p>State transitions: {@code false → REACTIVATED}/{@code REACTIVATED_BY_CUSTOMER}.
     * {@code BLOCKED_BY_CUSTOMER} → no-op (platform block, not reactivatable by application signal).
     * {@code null} or {@code true} → no-op (no denial to reactivate).
     */
    @Transactional
    void reactivateByCustomerTx(Long customerId) {
        Customer customer = customerRepository.findById(customerId).orElse(null);
        if (customer == null) {
            log.warn("[legitimation] reactivateByCustomer: customer {} not found — no-op", customerId);
            return;
        }
        if (!Boolean.FALSE.equals(customer.getChannelLegitimacyStatus())) {
            return; // only reactivates from DENIED state
        }
        if (customer.getReasonOfDeny() == ReasonOfDeny.BLOCKED_BY_CUSTOMER) {
            return; // platform block — not reactivatable by application signal
        }
        applyLegitimation(customer, OriginOfLegitimation.REACTIVATED_BY_CUSTOMER,
                CustomerLegitimationEventType.REACTIVATED, LegitimationState.DENIED,
                SourceActor.CUSTOMER, null, null);
    }

    /**
     * Business owner attestation from the administration panel ({@code triggeredBy = OWNER},
     * {@code origin = ATTESTATION}).
     *
     * <p>State transitions: {@code null → LEGITIMATED}; {@code false + NEVER_LEGITIMATED → REACTIVATED}.
     * Already {@code true} → no-op. {@code false + CUSTOMER_EXPLICIT_STOP} or
     * {@code BLOCKED_BY_CUSTOMER} → {@link ConflictException} (Art. 21 precedence: the business
     * owner cannot override a customer-initiated denial).
     */
    @Transactional
    void attestTx(Long customerId, Long ownerUserId, String attestationText) {
        Customer customer = customerRepository.findById(customerId).orElse(null);
        if (customer == null) {
            log.warn("[legitimation] attest: customer {} not found — no-op", customerId);
            return;
        }
        Boolean status = customer.getChannelLegitimacyStatus();
        if (Boolean.TRUE.equals(status)) {
            return; // re-attestation on already-legitimate customer → no-op
        }
        if (Boolean.FALSE.equals(status) && isCustomerInitiatedDeny(customer.getReasonOfDeny())) {
            throw new ConflictException(
                    "Cannot attest: the customer has requested not to be contacted. "
                            + "Only the customer can reactivate the channel.");
        }
        // null (UNEVALUATED) → LEGITIMATED ; false + NEVER_LEGITIMATED → REACTIVATED.
        LegitimationState from = LegitimationState.of(status);
        CustomerLegitimationEventType event = (from == LegitimationState.UNEVALUATED)
                ? CustomerLegitimationEventType.LEGITIMATED
                : CustomerLegitimationEventType.REACTIVATED;
        applyLegitimation(customer, OriginOfLegitimation.ATTESTATION, event, from,
                SourceActor.OWNER, ownerUserId, attestationText);
    }

    /**
     * Explicit customer opt-out (STOP signal) → {@code channelLegitimacyStatus = false},
     * {@code CUSTOMER_EXPLICIT_STOP}, {@code OPT_OUT} audit row.
     * Idempotent: already in {@code CUSTOMER_EXPLICIT_STOP} → no-op.
     */
    @Transactional
    void optOutTx(Long customerId) {
        Customer customer = customerRepository.findById(customerId).orElse(null);
        if (customer == null) {
            log.warn("[legitimation] optOut: customer {} not found — no-op", customerId);
            return;
        }
        if (Boolean.FALSE.equals(customer.getChannelLegitimacyStatus())
                && customer.getReasonOfDeny() == ReasonOfDeny.CUSTOMER_EXPLICIT_STOP) {
            return; // already opted out — idempotent
        }
        LegitimationState from = LegitimationState.of(customer.getChannelLegitimacyStatus());
        customer.setChannelLegitimacyStatus(false);
        customer.setReasonOfDeny(ReasonOfDeny.CUSTOMER_EXPLICIT_STOP);
        customer.setOriginOfLegitimation(null);
        // legitimatedAt is preserved: it records the last transition to true (historical)
        // and is not updated on a transition to false.
        customerRepository.save(customer);
        emitAudit(customer, CustomerLegitimationEventType.OPT_OUT, from, LegitimationState.DENIED,
                ReasonOfDeny.CUSTOMER_EXPLICIT_STOP, null, SourceActor.CUSTOMER, null, null);
        eventPublisher.publishEvent(CustomerOptOutEvent.builder()
                .customerId(customer.getId())
                .businessId(customer.getBusiness().getId())
                .build());
    }

    /** Returns true for denials initiated by the customer (as opposed to system-initiated denials). */
    private boolean isCustomerInitiatedDeny(ReasonOfDeny reason) {
        return reason == ReasonOfDeny.CUSTOMER_EXPLICIT_STOP || reason == ReasonOfDeny.BLOCKED_BY_CUSTOMER;
    }

    /**
     * Applies a transition to {@code channelLegitimacyStatus = true} (invariant:
     * {@code origin != null, reason = null}) and emits the audit log row, atomically.
     */
    private void applyLegitimation(Customer customer, OriginOfLegitimation origin,
                                   CustomerLegitimationEventType event, LegitimationState from,
                                   SourceActor actor, Long actorUserId, String attestationText) {
        customer.setChannelLegitimacyStatus(true);
        customer.setOriginOfLegitimation(origin);
        customer.setReasonOfDeny(null);
        customer.setLegitimatedAt(OffsetDateTime.now());
        customerRepository.save(customer);
        emitAudit(customer, event, from, LegitimationState.LEGITIMATE, null, origin,
                actor, actorUserId, attestationText);
    }

    /** Appends an audit log row — same transaction as the mutation (committed ⟺ audited). Metadata-only. */
    private void emitAudit(Customer customer, CustomerLegitimationEventType event,
                           LegitimationState from, LegitimationState to,
                           ReasonOfDeny reason, OriginOfLegitimation origin,
                           SourceActor actor, Long actorUserId, String attestationText) {
        auditRepository.save(CustomerLegitimationAuditLog.builder()
                .customerId(customer.getId())
                .businessId(customer.getBusiness().getId())
                .eventType(event)
                .fromState(from.name())
                .toState(to.name())
                .reason(reason != null ? reason.name() : null)
                .origin(origin != null ? origin.name() : null)
                .triggeredBy(actor)
                .actorUserId(actorUserId)
                .attestationText(attestationText)
                .occurredAt(OffsetDateTime.now())
                .build());
    }
}
