package com.vookedme.botmanager.appointment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Database-level constraint verification for the appointment audit log (ADR-002,
 * ADR-003). Tests the guarantees that only a real PostgreSQL instance can enforce
 * — specifically the constraints introduced in the audit log migration:
 *
 * <ul>
 *   <li>The migration has been applied and the table is queryable.</li>
 *   <li>{@code chk_audit_actor_user_id}: {@code actor_user_id} is NOT NULL if
 *       and only if the actor is a panel role (OWNER, ADMIN, or EMPLOYEE). Bot,
 *       scheduler, customer, and system actors have no {@code User} identity and
 *       must have NULL here.</li>
 *   <li>{@code chk_audit_event_type} and {@code chk_audit_triggered_by}: values
 *       are restricted to the declared enum domains; out-of-domain inserts are
 *       rejected.</li>
 *   <li>{@code trg_appointment_audit_immutable}: UPDATE and DELETE are blocked by
 *       a database trigger. The audit log is append-only; this property is
 *       enforced at the database layer, not only by the service layer.</li>
 * </ul>
 *
 * <p>Uses {@link JdbcTemplate} to write raw rows, isolating the database
 * constraints from the service stack. The actor attribution logic that produces
 * the correct values at runtime is tested separately in
 * {@code AppointmentAuditListenerTest} (unit test, no database).
 *
 * <p>These tests run against a real PostgreSQL instance (Testcontainers) via
 * {@code BaseIntegrationTest} *(published in a subsequent source batch)*.
 */
class AppointmentAuditLogConstraintsIT extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private static final String INSERT =
            "INSERT INTO appointment_audit_log " +
            "(appointment_id, business_id, event_type, triggered_by, actor_user_id, new_status, occurred_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, NOW())";

    private int insert(String eventType, String triggeredBy, Long actorUserId) {
        return jdbc.update(INSERT, 1L, testBusiness.getId(), eventType, triggeredBy, actorUserId, "CONFIRMED");
    }

    private long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointment_audit_log", Long.class);
    }

    // ── Migration applied ─────────────────────────────────────────────────────

    @Test
    void migrationApplied_tableIsQueryable() {
        assertThat(count()).isZero();
    }

    // ── chk_audit_actor_user_id ───────────────────────────────────────────────

    @Test
    void panelActor_withUserId_accepted() {
        insert("CREATED", "OWNER", ownerUser.getId());
        assertThat(count()).isEqualTo(1L);
    }

    @Test
    void nonPanelActor_nullUserId_accepted() {
        // Non-panel actors (BOT, CUSTOMER, SCHEDULER, SYSTEM) have no User identity.
        insert("CREATED", "CUSTOMER", null);
        insert("CANCELLED", "BOT", null);
        insert("CANCELLED", "SCHEDULER", null);
        insert("CONFIRMED", "SYSTEM", null);
        assertThat(count()).isEqualTo(4L);
    }

    @Test
    void panelActor_nullUserId_rejected() {
        // A panel actor (OWNER, ADMIN, EMPLOYEE) must always have a traceable user_id.
        assertThatThrownBy(() -> insert("CREATED", "OWNER", null))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("chk_audit_actor_user_id");
    }

    @Test
    void nonPanelActor_withUserId_rejected() {
        // A non-panel actor cannot have a user_id: bots and schedulers do not
        // operate under a user identity.
        assertThatThrownBy(() -> insert("CANCELLED", "BOT", 5L))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("chk_audit_actor_user_id");
    }

    // ── chk_audit_event_type / chk_audit_triggered_by ────────────────────────

    @Test
    void invalidEventType_rejected() {
        assertThatThrownBy(() -> insert("HACK_EVENT", "SYSTEM", null))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("chk_audit_event_type");
    }

    @Test
    void invalidTriggeredBy_rejected() {
        // 'INTRUDER' is outside the SourceActor enum domain. Both
        // chk_audit_triggered_by (IN-list for event types) and
        // chk_audit_actor_user_id (IN-list for actor-user_id pairing) reject it.
        // Postgres may attribute the violation to either constraint; both proofs
        // confirm that no out-of-enum actor can be persisted.
        assertThatThrownBy(() -> insert("CREATED", "INTRUDER", null))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("chk_audit_");
    }

    // ── trg_appointment_audit_immutable (append-only) ─────────────────────────

    @Test
    void update_blockedByImmutabilityTrigger() {
        insert("CREATED", "OWNER", ownerUser.getId());
        assertThatThrownBy(() ->
                jdbc.update("UPDATE appointment_audit_log SET new_status = 'TAMPERED' WHERE appointment_id = 1"))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("append-only");
    }

    @Test
    void delete_blockedByImmutabilityTrigger() {
        insert("CREATED", "OWNER", ownerUser.getId());
        assertThatThrownBy(() ->
                jdbc.update("DELETE FROM appointment_audit_log WHERE appointment_id = 1"))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("append-only");
    }
}
