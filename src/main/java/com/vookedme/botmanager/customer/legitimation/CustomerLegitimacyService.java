package com.vookedme.botmanager.customer.legitimation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * The sole write owner for customer channel legitimacy. Operations:
 * {@link #legitimateFromBot}, {@link #optOut}, {@link #reactivateByCustomer},
 * and {@link #attest}. No other class modifies the four legitimation fields on
 * {@code Customer}.
 *
 * <p>Centralises the precedence rule (explicit customer opt-out takes precedence over business
 * owner attestation, Art. 21) and the domain invariant ({@code true ⟺ origin != null,
 * reason = null}). Each transition emits an audit log row in the same transaction as the
 * mutation (committed ⟺ audited).
 *
 * <p><b>Precedence rule:</b> a generic bot inbound does NOT reactivate an explicit customer
 * opt-out — {@link #legitimateFromBot} over {@code CUSTOMER_EXPLICIT_STOP} is a silent no-op.
 * Reactivating an opt-out requires the deliberate {@link #reactivateByCustomer} action.
 * {@link #attest} over a customer-initiated denial throws {@code ConflictException} (409).
 *
 * <p><b>Concurrency:</b> each transition is delegated to {@link LegitimacyTransactionalWriter}
 * (isolated transaction per attempt) and retried idempotently on
 * {@link ObjectOptimisticLockingFailureException} — the retry re-reads fresh state; a retry
 * that finds the state already applied is a no-op. Only
 * {@link ObjectOptimisticLockingFailureException} triggers retry; all other exceptions
 * (e.g. {@code ConflictException} from the precedence rule) propagate immediately without retry.
 * A bounded retry via a separate bean is required so that each attempt has its own transactional
 * boundary — a self-invocation retry would run inside the outer transaction and defeat the
 * optimistic lock mechanism.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerLegitimacyService {

    /** Maximum retry attempts on concurrent modification ({@code @Version} conflict). */
    static final int MAX_ATTEMPTS = 3;

    private final LegitimacyTransactionalWriter writer;

    /**
     * Auto-legitimation via customer inbound. No-op if already legitimate or if there
     * is an active explicit opt-out or platform block.
     */
    public void legitimateFromBot(Long customerId) {
        retryOnOptimisticLock(() -> writer.legitimateFromBotTx(customerId), "legitimateFromBot", customerId);
    }

    /** Explicit customer opt-out (STOP signal) → {@code channelLegitimacyStatus = false}, {@code CUSTOMER_EXPLICIT_STOP}. */
    public void optOut(Long customerId) {
        retryOnOptimisticLock(() -> writer.optOutTx(customerId), "optOut", customerId);
    }

    /**
     * Deliberate customer reactivation (opt-in / START signal) after an opt-out
     * → {@code REACTIVATED_BY_CUSTOMER}. The only path that reactivates a
     * {@code CUSTOMER_EXPLICIT_STOP}.
     */
    public void reactivateByCustomer(Long customerId) {
        retryOnOptimisticLock(() -> writer.reactivateByCustomerTx(customerId), "reactivateByCustomer", customerId);
    }

    /**
     * Business owner attestation (administration panel, OWNER role). Throws
     * {@code ConflictException} (409) if there is an active explicit opt-out
     * (customer precedence rule). {@code triggeredBy = OWNER}, {@code origin = ATTESTATION}.
     *
     * @param ownerUserId     {@code User.id} of the attesting owner (non-null for the audit row).
     * @param attestationText the owner's Art. 13 attestation statement — not customer PII.
     */
    public void attest(Long customerId, Long ownerUserId, String attestationText) {
        retryOnOptimisticLock(() -> writer.attestTx(customerId, ownerUserId, attestationText), "attest", customerId);
    }

    /**
     * Bounded idempotent retry on {@code @Version} conflict. Only retries
     * {@link ObjectOptimisticLockingFailureException}; all other exceptions propagate immediately.
     */
    private void retryOnOptimisticLock(Runnable op, String operation, Long customerId) {
        for (int attempt = 1; ; attempt++) {
            try {
                op.run();
                return;
            } catch (ObjectOptimisticLockingFailureException race) {
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("[legitimation] {} customer={}: persistent @Version conflict after {} attempts",
                            operation, customerId, MAX_ATTEMPTS);
                    throw race;
                }
                log.debug("[legitimation] {} customer={}: retry {} after @Version conflict (idempotent)",
                        operation, customerId, attempt);
            }
        }
    }
}
