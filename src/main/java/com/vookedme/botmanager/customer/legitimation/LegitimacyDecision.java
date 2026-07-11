package com.vookedme.botmanager.customer.legitimation;

import com.vookedme.botmanager.customer.entity.ReasonOfDeny;

/**
 * Gate return value from {@link OutboundLegitimacyGate#evaluate(Long)}: either ALLOW
 * or DENY, with the denial reason when known.
 *
 * <p><b>Contract:</b>
 * <ul>
 *   <li>{@code eligible == true} ⇒ {@code reason == null} (channelLegitimacyStatus == true).</li>
 *   <li>{@code eligible == false} ⇒ {@code reason} may be null or non-null:
 *     <ul>
 *       <li>{@code reason == null} — unevaluated / default-deny state
 *           (channelLegitimacyStatus == null).</li>
 *       <li>{@code reason != null} — explicit denial (channelLegitimacyStatus == false;
 *           the reason mirrors the customer's stored {@code reasonOfDeny}).</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>The {@code reason} field mirrors the customer's {@code reasonOfDeny}, consistent with
 * the storage invariant: {@code false ⟺ reasonOfDeny != null};
 * {@code null/true ⇒ reasonOfDeny == null}.
 */
public record LegitimacyDecision(boolean eligible, ReasonOfDeny reason) {

    /** Enforces the contract: an eligible decision never carries a denial reason. */
    public LegitimacyDecision {
        if (eligible && reason != null) {
            throw new IllegalArgumentException(
                    "Invalid LegitimacyDecision: eligible=true requires reason=null (received " + reason + ")");
        }
    }

    // Factory methods named allow()/deny() rather than eligible()/ineligible() to avoid
    // collision with the record's auto-generated eligible() accessor.

    /** Customer is eligible (channelLegitimacyStatus == true). {@code reason} is always null. */
    public static LegitimacyDecision allow() {
        return new LegitimacyDecision(true, null);
    }

    /**
     * Customer is not eligible. {@code reason} is the customer's {@code reasonOfDeny}:
     * {@code null} for the unevaluated/default-deny state (channelLegitimacyStatus == null),
     * or the concrete value for an explicit denial (channelLegitimacyStatus == false).
     */
    public static LegitimacyDecision deny(ReasonOfDeny reason) {
        return new LegitimacyDecision(false, reason);
    }
}
