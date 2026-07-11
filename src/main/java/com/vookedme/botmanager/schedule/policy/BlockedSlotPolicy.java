package com.vookedme.botmanager.schedule.policy;

import com.vookedme.botmanager.auth.entity.User;
import com.vookedme.botmanager.business.entity.Business;
import org.springframework.stereotype.Component;

/**
 * Policy class centralising "who can do what with a BlockedSlot" decisions for
 * a given Business. Single source of truth: the service layer consults this
 * policy rather than branching on roles and configuration flags inline.
 *
 * <h3>Why a policy class instead of inline checks</h3>
 *
 * <p>The BlockedSlot state machine introduces a permission model that depends on
 * actor role plus business configuration. Approval workflows, cancellation rules,
 * and per-business configuration toggles (auto-approval thresholds, blackout
 * windows) add more decision points over time. Duplicating the resulting decision
 * tree across the service layer and the controller causes drift — the same rule
 * expressed twice in subtly different ways.
 *
 * <p>Centralising the rules here gives:
 * <ul>
 *   <li>one place to read when answering "can EMPLOYEE X do Y?";</li>
 *   <li>one place to test (unit tests on this class are role-and-flag
 *       combinations, not full service paths);</li>
 *   <li>one place to extend when adding the next configuration knob;</li>
 *   <li>defence in depth at the service layer that mirrors the frontend
 *       gating exactly — the UX hides what the backend rejects, but they
 *       must agree on the same rule and must not maintain two parallel
 *       implementations.</li>
 * </ul>
 *
 * <h3>Stateless component</h3>
 *
 * <p>This class holds no fields. Methods take {@link User} and {@link Business}
 * arguments and return a boolean (or throw). Spring registers it as a singleton
 * via {@link Component} so {@code BlockedSlotService} can inject it without
 * picking up unexpected state across requests.
 */
@Component
public class BlockedSlotPolicy {

    /**
     * Returns whether an EMPLOYEE actor in the given business is allowed to
     * submit block requests (which enter the {@code REQUESTED} state for
     * OWNER/ADMIN approval). Reads
     * {@link Business#getAllowEmployeeBlockRequests}.
     *
     * <p>This method is the only direct reader of the
     * {@code allow_employee_block_requests} column. Other code paths call
     * {@link #checkEmployeeMayRequestBlocks(Business, User)} for the throwing
     * variant.
     *
     * <p>Defensive null handling: if the flag is {@code null} (a legacy row
     * that predates the column, or a hand-built test entity that bypassed the
     * entity default), returns {@code false} — the safer default. The database
     * migration set a NOT NULL default of {@code FALSE} for all existing rows,
     * so this path only matters for tests with manually constructed entities.
     */
    public boolean canEmployeeRequestBlocks(Business business) {
        if (business == null) return false;
        Boolean flag = business.getAllowEmployeeBlockRequests();
        return Boolean.TRUE.equals(flag);
    }

    /**
     * Throwing variant for service-layer use. Throws a
     * {@link BlockedSlotPolicyDeniedException} when the EMPLOYEE actor is
     * denied. The service layer translates this into the appropriate HTTP 403
     * response.
     *
     * <p>OWNER/ADMIN actors are never denied here — they bypass this check
     * entirely. This method is only called when the actor's role is confirmed
     * to be EMPLOYEE.
     *
     * @throws BlockedSlotPolicyDeniedException when the business has
     *         {@code allow_employee_block_requests = FALSE}
     */
    public void checkEmployeeMayRequestBlocks(Business business, User actor) {
        if (canEmployeeRequestBlocks(business)) return;
        throw new BlockedSlotPolicyDeniedException(
                // LOCALE OUTPUT (Spanish): user-facing denial message returned as HTTP 403 body
                "Tu negocio no permite que los empleados soliciten bloqueos. " +
                "Habla con el responsable para que lo bloquee él directamente.",
                business != null ? business.getId() : null,
                actor != null ? actor.getId() : null
        );
    }

    /**
     * Marker exception thrown by the policy when an EMPLOYEE request is denied
     * by a per-business toggle. Translated to HTTP 403 by the service layer.
     *
     * <p>Defined as an inner class so the policy file is self-contained. If
     * additional policy violations emerge they can be promoted to sibling
     * exception classes in the same package.
     */
    public static class BlockedSlotPolicyDeniedException extends RuntimeException {
        private final Long businessId;
        private final Long actorUserId;

        public BlockedSlotPolicyDeniedException(String message, Long businessId, Long actorUserId) {
            super(message);
            this.businessId = businessId;
            this.actorUserId = actorUserId;
        }

        public Long getBusinessId() { return businessId; }
        public Long getActorUserId() { return actorUserId; }
    }
}
