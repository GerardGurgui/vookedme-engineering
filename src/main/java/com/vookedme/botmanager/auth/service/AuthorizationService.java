package com.vookedme.botmanager.auth.service;

import com.vookedme.botmanager.auth.security.SecurityUtils;
import com.vookedme.botmanager.auth.security.UserPrincipal;
import com.vookedme.botmanager.common.event.EventActor;
import com.vookedme.botmanager.common.event.SourceActor;
import com.vookedme.botmanager.common.exception.ForbiddenException;
import com.vookedme.botmanager.common.exception.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Central tenant-isolation and authorisation gate — every state-mutating
 * service method calls one of these guards before touching data.
 *
 * <p>The design follows ADR-016: multi-tenancy is enforced at the
 * application layer, not by row-level security. A single guard call
 * per service method makes the isolation contract visible and auditable
 * without relying on database-level controls.
 *
 * <p>Seven guard methods cover the full permission surface:
 * <ol>
 *   <li>{@link #checkBusinessAccess} — authenticated user belongs to this business</li>
 *   <li>{@link #checkBusinessAccessAndGet} — same, returning the principal for downstream use</li>
 *   <li>{@link #checkOwnerOrAdmin} — OWNER of this business or global ADMIN</li>
 *   <li>{@link #checkSelfOrOwnerOrAdmin} — EMPLOYEE accessing own data, or OWNER/ADMIN</li>
 *   <li>{@link #checkSelfClaimOrOwnerOrAdmin} — EMPLOYEE self-assigning, or OWNER/ADMIN assigning any employee</li>
 *   <li>{@link #checkCanManageBlockForEmployee} — schedule block creation rules</li>
 *   <li>{@link #checkCanDeleteBlock} — schedule block deletion rules (stricter than creation)</li>
 * </ol>
 */
@Service
@Slf4j
public class AuthorizationService {

    /**
     * Returns the authenticated principal or throws if no user is present
     * in the security context.
     */
    public UserPrincipal getCurrentUserOrThrow() {
        return SecurityUtils.getCurrentUser()
                .orElseThrow(() -> new UnauthorizedException("Authentication required"));
    }

    /**
     * ADR-002 — maps an authenticated {@link UserPrincipal} to its
     * {@link SourceActor} for audit attribution. Panel roles
     * (OWNER/ADMIN/EMPLOYEE) map directly; any other case falls through
     * to {@code SYSTEM} as a defensive fallback — an unknown actor must
     * never be null in the audit log.
     */
    public SourceActor actorOf(UserPrincipal user) {
        if (user == null) {
            return SourceActor.SYSTEM;
        }
        if (user.isAdmin()) {
            return SourceActor.ADMIN;
        }
        if (user.isOwner()) {
            return SourceActor.OWNER;
        }
        if (user.isEmployee()) {
            return SourceActor.EMPLOYEE;
        }
        return SourceActor.SYSTEM;
    }

    /**
     * ADR-002 — resolves the actor for the current request from the
     * SecurityContext. Returns {@code SYSTEM} when no user is authenticated
     * (should not occur on panel paths; bot and scheduler paths pass their
     * actor explicitly).
     */
    public SourceActor getCurrentActor() {
        return SecurityUtils.getCurrentUser()
                .map(this::actorOf)
                .orElse(SourceActor.SYSTEM);
    }

    /**
     * ADR-002 — resolves the {@link EventActor} for the current panel
     * request, for explicit passing to {@code publishAppointmentEvent}.
     * {@code userId} is populated only for panel roles (OWNER/ADMIN/EMPLOYEE),
     * guaranteeing the {@code chk_audit_actor_user_id} DB invariant.
     * Returns {@code EventActor.system()} when no user is authenticated
     * (bot and scheduler paths pass their actor explicitly).
     */
    public EventActor currentEventActor() {
        return SecurityUtils.getCurrentUser()
                .map(u -> {
                    SourceActor a = actorOf(u);
                    boolean panelRole = a == SourceActor.OWNER
                            || a == SourceActor.ADMIN
                            || a == SourceActor.EMPLOYEE;
                    return new EventActor(a, panelRole ? u.getId() : null);
                })
                .orElse(EventActor.system());
    }

    /**
     * Verifies the authenticated user has access to the given business.
     * ADMIN: unrestricted access to all businesses.
     * OWNER / EMPLOYEE: access only to their own business.
     */
    public void checkBusinessAccess(Long businessId) {
        checkBusinessAccessAndGet(businessId);
    }

    /**
     * Verifies business access and returns the authenticated principal.
     * Useful when the controller also needs the user's role or id for
     * downstream logic.
     */
    public UserPrincipal checkBusinessAccessAndGet(Long businessId) {
        UserPrincipal user = getCurrentUserOrThrow();

        if (!hasBusinessAccess(user, businessId)) {
            log.warn("Access denied: user {} (role={}, businessId={}) tried to access businessId={}",
                    user.getEmail(), user.getRoleName(), user.getBusinessId(), businessId);
            throw new ForbiddenException("You don't have access to this business");
        }

        return user;
    }

    /**
     * Verifies the caller is ADMIN or OWNER of the given business.
     * Used for modification operations: creating offerings, schedules, etc.
     */
    public void checkOwnerOrAdmin(Long businessId) {
        UserPrincipal user = getCurrentUserOrThrow();

        if (!isOwnerOrAdmin(user, businessId)) {
            log.warn("Owner access denied: user {} (role={}) tried to modify businessId={}",
                    user.getEmail(), user.getRoleName(), businessId);
            throw new ForbiddenException("Only business owner or admin can perform this action");
        }
    }

    /**
     * Verifies the caller can access resources belonging to a specific employee.
     * ADMIN / OWNER: unrestricted.
     * EMPLOYEE: only their own data (targetUserId must equal the caller's id).
     */
    public void checkSelfOrOwnerOrAdmin(Long targetUserId) {
        UserPrincipal user = getCurrentUserOrThrow();

        if (user.isAdmin() || user.isOwner()) return;

        if (!user.getId().equals(targetUserId)) {
            log.warn("Self-access denied: user {} (id={}) tried to access data of userId={}",
                    user.getEmail(), user.getId(), targetUserId);
            throw new ForbiddenException("Employees can only access their own appointments");
        }
    }

    /**
     * Verifies permission to assign an employee to an appointment.
     *
     * <p>Rules:
     * <ul>
     *   <li>ADMIN: unrestricted.</li>
     *   <li>OWNER of the same business: may assign any employee.</li>
     *   <li>EMPLOYEE of the same business: may only self-assign
     *       ({@code targetEmployeeId} must equal their own id).</li>
     *   <li>All other cases: {@link ForbiddenException}.</li>
     * </ul>
     */
    public void checkSelfClaimOrOwnerOrAdmin(Long businessId, Long targetEmployeeId) {
        UserPrincipal user = getCurrentUserOrThrow();

        if (user.isAdmin()) return;

        boolean belongsToBusiness = user.getBusinessId() != null
                && user.getBusinessId().equals(businessId);

        if (user.isOwner() && belongsToBusiness) return;

        if (user.isEmployee() && belongsToBusiness
                && user.getId() != null && user.getId().equals(targetEmployeeId)) {
            return;
        }

        log.warn("Assign denied: user {} (role={}, businessId={}) tried to assign employeeId={} on businessId={}",
                user.getEmail(), user.getRoleName(), user.getBusinessId(), targetEmployeeId, businessId);
        throw new ForbiddenException(
                "Only the business owner, an administrator, or the employee themselves may perform this assignment");
    }

    /**
     * Verifies permission to create or modify a schedule block for a given employee.
     *
     * <p>Rules:
     * <ul>
     *   <li>ADMIN: unrestricted.</li>
     *   <li>OWNER of the same business: may manage blocks for any employee.</li>
     *   <li>EMPLOYEE of the same business: may only manage blocks for themselves
     *       ({@code targetEmployeeId} must equal their own id).</li>
     *   <li>{@code targetEmployeeId == null} (business-wide block): OWNER/ADMIN only.</li>
     *   <li>All other cases: {@link ForbiddenException}.</li>
     * </ul>
     */
    public void checkCanManageBlockForEmployee(Long businessId, Long targetEmployeeId) {
        UserPrincipal user = getCurrentUserOrThrow();

        if (user.isAdmin()) return;

        boolean belongsToBusiness = user.getBusinessId() != null
                && user.getBusinessId().equals(businessId);

        // Business-wide blocks (targetEmployeeId == null) — OWNER/ADMIN only.
        if (targetEmployeeId == null) {
            if (user.isOwner() && belongsToBusiness) return;
            log.warn("Block-create denied (business-wide): user {} (role={}) on businessId={}",
                    user.getEmail(), user.getRoleName(), businessId);
            throw new ForbiddenException(
                    "Only the business owner or an administrator may create business-wide schedule blocks");
        }

        // Employee-specific blocks.
        if (user.isOwner() && belongsToBusiness) return;
        if (user.isEmployee() && belongsToBusiness
                && user.getId() != null && user.getId().equals(targetEmployeeId)) {
            return;
        }

        log.warn("Block-create denied: user {} (role={}, businessId={}) tried to block employeeId={} on businessId={}",
                user.getEmail(), user.getRoleName(), user.getBusinessId(), targetEmployeeId, businessId);
        throw new ForbiddenException("Employees may not create schedule blocks for other employees");
    }

    /**
     * Verifies permission to delete a schedule block. Intentionally stricter
     * than the creation rule: what matters here is who created the block, not
     * for whom it applies.
     *
     * <p>Rules:
     * <ul>
     *   <li>ADMIN: unrestricted.</li>
     *   <li>OWNER of the same business: may delete any block.</li>
     *   <li>EMPLOYEE of the same business: only if they created the block
     *       ({@code createdByUserId} must equal their own id).</li>
     *   <li>Legacy blocks ({@code createdByUserId == null}, predating creator
     *       tracking): OWNER/ADMIN only — an employee cannot infer authorship.</li>
     * </ul>
     *
     * <p>This asymmetry is intentional: if an owner blocks an employee's calendar
     * for a training day, the employee cannot remove that block themselves.
     */
    public void checkCanDeleteBlock(Long businessId, Long createdByUserId) {
        UserPrincipal user = getCurrentUserOrThrow();

        if (user.isAdmin()) return;

        boolean belongsToBusiness = user.getBusinessId() != null
                && user.getBusinessId().equals(businessId);

        if (user.isOwner() && belongsToBusiness) return;

        if (user.isEmployee() && belongsToBusiness
                && createdByUserId != null
                && user.getId() != null && user.getId().equals(createdByUserId)) {
            return;
        }

        log.warn("Block-delete denied: user {} (role={}, businessId={}) tried to delete block created by userId={} on businessId={}",
                user.getEmail(), user.getRoleName(), user.getBusinessId(), createdByUserId, businessId);
        throw new ForbiddenException("You do not have permission to delete this schedule block");
    }

    /**
     * Verifies the caller is a global ADMIN.
     * Used for cross-business operations (creating businesses, viewing all, etc.).
     */
    public void checkAdmin() {
        UserPrincipal user = getCurrentUserOrThrow();

        if (!user.isAdmin()) {
            log.warn("Admin access denied: user {} (role={}) tried admin action",
                    user.getEmail(), user.getRoleName());
            throw new ForbiddenException("Only admin can perform this action");
        }
    }

    // ==================== Boolean helpers ====================

    public boolean hasBusinessAccess(Long businessId) {
        return SecurityUtils.getCurrentUser()
                .map(user -> hasBusinessAccess(user, businessId))
                .orElse(false);
    }

    public boolean isOwnerOrAdmin(Long businessId) {
        return SecurityUtils.getCurrentUser()
                .map(user -> isOwnerOrAdmin(user, businessId))
                .orElse(false);
    }

    public boolean isAdmin() {
        return SecurityUtils.getCurrentUser()
                .map(UserPrincipal::isAdmin)
                .orElse(false);
    }

    // ==================== Private ====================

    private boolean hasBusinessAccess(UserPrincipal user, Long businessId) {
        if (user.isAdmin()) {
            return true;
        }
        return user.getBusinessId() != null && user.getBusinessId().equals(businessId);
    }

    private boolean isOwnerOrAdmin(UserPrincipal user, Long businessId) {
        if (user.isAdmin()) {
            return true;
        }
        return user.isOwner() && user.getBusinessId() != null && user.getBusinessId().equals(businessId);
    }
}
