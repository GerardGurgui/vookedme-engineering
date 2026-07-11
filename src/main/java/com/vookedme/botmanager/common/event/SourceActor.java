package com.vookedme.botmanager.common.event;

/**
 * Identifies the actor that originated a business action producing a domain
 * event or outbound notification. Locks down the contract of {@code triggeredBy}
 * across events and their derived notifications.
 *
 * <p>The actor is always resolved server-side from the authenticated JWT —
 * this enum is never accepted as external input (request body). Panel roles
 * (OWNER/ADMIN/EMPLOYEE) are resolved from the JWT by
 * {@link com.vookedme.botmanager.auth.service.AuthorizationService#getCurrentActor()},
 * which injects the correct value automatically into all events and
 * notifications. Non-panel paths (bot, scheduler, system reactions) pass
 * their actor explicitly at the call site.
 */
public enum SourceActor {
    /** Business owner (OWNER role in the JWT). */
    OWNER,
    /** Administrator with elevated permissions (ADMIN role in the JWT). */
    ADMIN,
    /** Employee operating on their own appointments or schedule blocks (EMPLOYEE role). */
    EMPLOYEE,
    /** End customer mediated by the bot — inbound interaction via WhatsApp. */
    CUSTOMER,
    /**
     * Autonomous bot action not attributable to a direct customer decision.
     * Reserved; all current bot actions arrive via {@link #CUSTOMER}.
     */
    BOT,
    /** Backend reaction — internal listener or maintenance task with no human actor. */
    SYSTEM,
    /** Origin of a {@code @Scheduled} job — periodic background tasks. */
    SCHEDULER
}
