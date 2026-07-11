package com.vookedme.botmanager.customer.legitimation;

import com.vookedme.botmanager.customer.entity.Customer;
import com.vookedme.botmanager.customer.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Evaluates channel legitimacy before any business-initiated outbound message to a customer.
 *
 * <p><b>Default-deny (Art. 25.2):</b> only {@code channelLegitimacyStatus == true} is eligible;
 * {@code null} (not yet evaluated) and {@code false} (denied) are both ineligible. This is not
 * a configuration option — it is the code path that executes when the status is absent.
 *
 * <p><b>Fresh-read:</b> the gate takes {@code customerId} and re-reads customer state at
 * evaluation time. It does not accept a {@code Customer} entity. The notification dispatch path
 * is asynchronous; an entity loaded before the dispatch may be stale if an opt-out was committed
 * between enqueuing and dispatching. The fresh-read makes stale-cache suppression failures
 * structurally impossible.
 *
 * <p>Sits above {@code WhatsAppOutboundPort} in the component hierarchy — survives a swap
 * of the underlying messaging provider.
 *
 * <p><b>Feature flag:</b> governed by {@code app.legitimation.gate-enabled} (default OFF at time
 * of publication). The gate is wired on all outbound paths; the flag holds it inert pending
 * full activation.
 */
@Component
@RequiredArgsConstructor
public class OutboundLegitimacyGate {

    private final CustomerRepository customerRepository;

    /**
     * Evaluates the channel legitimacy of the given customer by reading fresh state by id.
     * A non-existent customer returns default-deny ({@code reason = null}).
     *
     * @param customerId the customer id (the gate performs its own read — does not accept an entity).
     */
    @Transactional(readOnly = true)
    public LegitimacyDecision evaluate(Long customerId) {
        Optional<Customer> found = customerRepository.findById(customerId);
        if (found.isEmpty()) {
            return LegitimacyDecision.deny(null);
        }
        Customer customer = found.get();
        if (Boolean.TRUE.equals(customer.getChannelLegitimacyStatus())) {
            return LegitimacyDecision.allow();
        }
        // channelLegitimacyStatus == null (unevaluated) or false (denied) → ineligible.
        // reason = the stored reasonOfDeny (null if unevaluated; the value if false).
        return LegitimacyDecision.deny(customer.getReasonOfDeny());
    }
}
