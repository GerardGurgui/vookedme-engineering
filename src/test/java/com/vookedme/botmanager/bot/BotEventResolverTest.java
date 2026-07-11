package com.vookedme.botmanager.bot;

import com.vookedme.botmanager.appointment.entity.Appointment;
import com.vookedme.botmanager.appointment.entity.AppointmentSource;
import com.vookedme.botmanager.appointment.entity.AppointmentStatus;
import com.vookedme.botmanager.appointment.entity.ApprovalDecisionSource;
import com.vookedme.botmanager.bot.domain.BotEvent;
import com.vookedme.botmanager.bot.domain.BotEventType;
import com.vookedme.botmanager.bot.service.BotEventResolver;
import com.vookedme.botmanager.config.observability.ObservabilityHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BotEventResolver}, covering two orthogonal concerns:
 *
 * <ol>
 *   <li><b>Exhaustive branch coverage</b> — every derivation branch and the
 *       3-actor cancellation model (BRANCH 1-11 in {@code resolve()}) is exercised
 *       with a minimal fixture that matches the branch discriminator.</li>
 *   <li><b>Purity guarantees</b> — structural reflection tests verify that the
 *       class is final, has no Spring annotations, no instance fields, no
 *       injection annotations, and that all public methods are static. A
 *       determinism test verifies that identical input produces identical output
 *       across multiple invocations without mutating the input.</li>
 * </ol>
 *
 * <p>Both concerns can be tested entirely without a database or application
 * context — the resolver's purity guarantee is what makes this possible.
 * The exhaustive coverage here is the executable specification of the
 * derivation algorithm; see {@link BotEventType} for the taxonomy definition.
 *
 * <p>Note: {@code ObservabilityHelper} is used by the DEFAULT-branch canary
 * in the resolver. Tests mock it statically to verify the canary fires
 * without making actual Sentry calls.
 */
@DisplayName("BotEventResolver — exhaustive branch coverage and purity guarantees")
class BotEventResolverTest {

    /** Reference point after the approval audit columns were introduced. */
    private static final LocalDateTime POST_AUDIT_CUTOFF =
            LocalDateTime.of(2026, 5, 24, 10, 0);

    /** Reference point before the approval audit columns were introduced. */
    private static final LocalDateTime PRE_AUDIT_CUTOFF =
            LocalDateTime.of(2026, 4, 1, 9, 0);

    // ─── Fixture helpers ────────────────────────────────────────────────

    /** Minimal BOT-source PENDING appointment with id=1 and post-cutoff timestamps. */
    private static Appointment botPending() {
        Appointment a = Appointment.builder()
                .source(AppointmentSource.BOT)
                .status(AppointmentStatus.PENDING)
                .datetime(POST_AUDIT_CUTOFF.plusDays(1))
                .durationMinutes(30)
                .build();
        a.setId(1L);
        a.setCreatedAt(POST_AUDIT_CUTOFF);
        return a;
    }

    /** Minimal PANEL-source row — not bot-relevant. */
    private static Appointment panelConfirmed() {
        Appointment a = Appointment.builder()
                .source(AppointmentSource.PANEL)
                .status(AppointmentStatus.CONFIRMED)
                .datetime(POST_AUDIT_CUTOFF.plusDays(1))
                .durationMinutes(30)
                .build();
        a.setId(2L);
        a.setCreatedAt(POST_AUDIT_CUTOFF);
        return a;
    }

    /** Asserts that resolved event types match the expected sequence exactly. */
    private static void assertTypes(List<BotEvent> events, BotEventType... expected) {
        assertThat(events.stream().map(BotEvent::type)).containsExactly(expected);
    }

    // ════════════════════════════════════════════════════════════════════
    // PART 1 — Exhaustive branch coverage
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Argument validation")
    class ArgumentValidation {

        @Test
        @DisplayName("null appointment → NullPointerException")
        void nullAppointment_throws() {
            assertThatThrownBy(() -> BotEventResolver.resolve(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Private constructor throws — pure utility class marker enforced via reflection")
        void privateConstructor_throws() throws Exception {
            var ctor = BotEventResolver.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            assertThatThrownBy(ctor::newInstance)
                    .hasCauseInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Pre-check: bot-relevance")
    class BotRelevance {

        @Test
        @DisplayName("Non-bot row with no bot-initiated CR → empty list (DEFAULT canary does not fire)")
        void nonBotRow_returnsEmpty() {
            List<BotEvent> events = BotEventResolver.resolve(panelConfirmed());
            assertThat(events).isEmpty();
        }

        @Test
        @DisplayName("PANEL row with cancellationRequestedActor=BOT → still bot-relevant")
        void panelWithBotInitiatedCr_isBotRelevant() {
            Appointment a = panelConfirmed();
            a.setCancellationRequestedActor("BOT");
            a.setCancellationRequestedAt(POST_AUDIT_CUTOFF.plusDays(2));
            List<BotEvent> events = BotEventResolver.resolve(a);
            assertTypes(events, BotEventType.BOT_CANCEL_REQUESTED);
        }
    }

    @Nested
    @DisplayName("BRANCH 1 — BOT_PROPOSED (approval-required mode)")
    class BotProposed {

        @Test
        @DisplayName("BOT + PENDING → BOT_PROPOSED only")
        void pendingBotProposal() {
            List<BotEvent> events = BotEventResolver.resolve(botPending());
            assertTypes(events, BotEventType.BOT_PROPOSED);
            BotEvent e = events.get(0);
            assertThat(e.actorType()).isEqualTo("BOT");
            assertThat(e.appointmentId()).isEqualTo(1L);
            assertThat(e.occurredAt()).isEqualTo(POST_AUDIT_CUTOFF);
            assertThat(e.result()).isEqualTo("PENDING");
            assertThat(e.metadata()).doesNotContainKey("pre_v69");
        }

        @Test
        @DisplayName("ID is deterministic: same appointment state → same id across calls")
        void idDeterministic() {
            Appointment a = botPending();
            String id1 = BotEventResolver.resolve(a).get(0).id();
            String id2 = BotEventResolver.resolve(a).get(0).id();
            assertThat(id1).isEqualTo(id2);
            assertThat(id1).startsWith("1-BOT_PROPOSED-");
        }
    }

    @Nested
    @DisplayName("BRANCH 2 — BOT_AUTO_CONFIRMED (auto-confirm mode)")
    class BotAutoConfirmed {

        @Test
        @DisplayName("BOT + CONFIRMED + no approved_at + post-cutoff + no cancel lineage → BOT_AUTO_CONFIRMED")
        void autoConfirmCreation() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CONFIRMED);
            List<BotEvent> events = BotEventResolver.resolve(a);
            assertTypes(events, BotEventType.BOT_AUTO_CONFIRMED);
            assertThat(events.get(0).result()).isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("PENDING is mutually exclusive with BOT_AUTO_CONFIRMED")
        void pendingExcludesAutoConfirmed() {
            List<BotEvent> events = BotEventResolver.resolve(botPending());
            assertThat(events.stream().map(BotEvent::type))
                    .doesNotContain(BotEventType.BOT_AUTO_CONFIRMED);
        }
    }

    @Nested
    @DisplayName("Pre-audit-columns fallback heuristic")
    class PreAuditColumnsFallback {

        @Test
        @DisplayName("BOT + CONFIRMED + before cutoff + no audit columns → BOT_PROPOSED with pre_v69=true")
        void preAuditColumnsApprovedRow() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CONFIRMED);
            a.setCreatedAt(PRE_AUDIT_CUTOFF);
            List<BotEvent> events = BotEventResolver.resolve(a);
            assertTypes(events, BotEventType.BOT_PROPOSED);
            assertThat(events.get(0).metadata()).containsEntry("pre_v69", true);
        }

        @Test
        @DisplayName("Post-cutoff row without approved_at is BOT_AUTO_CONFIRMED, not fallback")
        void postCutoffNotFallback() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CONFIRMED);
            a.setCreatedAt(POST_AUDIT_CUTOFF);
            List<BotEvent> events = BotEventResolver.resolve(a);
            assertTypes(events, BotEventType.BOT_AUTO_CONFIRMED);
        }

        @Test
        @DisplayName("Package-private heuristic method is accessible for direct testing")
        void heuristicExposed() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CONFIRMED);
            a.setCreatedAt(PRE_AUDIT_CUTOFF);
            assertThat(BotEventResolver.isPreV69ApprovalFallback(a)).isTrue();
        }

        @Test
        @DisplayName("Fallback is NOT applied when cancellation lineage is present")
        void cancelLineageDisablesFallback() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CONFIRMED);
            a.setCreatedAt(PRE_AUDIT_CUTOFF);
            a.setCancellationActor("OWNER");
            assertThat(BotEventResolver.isPreV69ApprovalFallback(a)).isFalse();
        }
    }

    @Nested
    @DisplayName("BRANCH 3 — OWNER_APPROVED")
    class OwnerApproved {

        @Test
        @DisplayName("BOT + approved_at != null → BOT_PROPOSED + OWNER_APPROVED (lineage preserved)")
        void approvedRow() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CONFIRMED);
            a.setApprovedAt(POST_AUDIT_CUTOFF.plusMinutes(5));
            a.setApprovedByUserId(42L);
            a.setApprovalDecisionSource(ApprovalDecisionSource.OWNER_PANEL);

            List<BotEvent> events = BotEventResolver.resolve(a);
            assertTypes(events, BotEventType.BOT_PROPOSED, BotEventType.OWNER_APPROVED);

            BotEvent approved = events.get(1);
            assertThat(approved.actorType()).isEqualTo("OWNER");
            assertThat(approved.actorUserId()).isEqualTo(42L);
            assertThat(approved.occurredAt()).isEqualTo(POST_AUDIT_CUTOFF.plusMinutes(5));
            assertThat(approved.result()).isEqualTo("CONFIRMED");
            assertThat(approved.metadata()).containsEntry("approval_decision_source", "OWNER_PANEL");
        }
    }

    @Nested
    @DisplayName("BRANCH 4 — OWNER_REJECTED (rejected PENDING proposal)")
    class OwnerRejected {

        @Test
        @DisplayName("BOT + CANCELLED + OWNER + PANEL + no approved_at + no CR + no revoke → OWNER_REJECTED only")
        void rejectedProposal() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CANCELLED);
            a.setCancellationActor("OWNER");
            a.setCancellationSource("PANEL");
            a.setCancelledAt(POST_AUDIT_CUTOFF.plusHours(2));
            a.setCancelledByUserId(77L);
            a.setCancellationReason("Schedule not available at requested times");
            // cancellationRequestedActor IS NULL — no CR involved

            List<BotEvent> events = BotEventResolver.resolve(a);
            assertTypes(events, BotEventType.OWNER_REJECTED);
            BotEvent e = events.get(0);
            assertThat(e.actorType()).isEqualTo("OWNER");
            assertThat(e.actorUserId()).isEqualTo(77L);
            assertThat(e.reason()).isEqualTo("Schedule not available at requested times");
            assertThat(e.result()).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("CR scenario does not produce OWNER_REJECTED — cancellationRequestedActor present")
        void crScenarioDoesNotTriggerOwnerRejected() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CANCELLED);
            a.setCancellationActor("OWNER");
            a.setCancellationSource("PANEL");
            a.setCancelledAt(POST_AUDIT_CUTOFF.plusHours(2));
            // CR present: the discriminator must prefer OWNER_APPROVED_CANCEL
            a.setCancellationRequestedActor("BOT");
            a.setCancellationRequestedAt(POST_AUDIT_CUTOFF.plusHours(1));
            a.setCancellationRequestDecidedAt(POST_AUDIT_CUTOFF.plusHours(2));

            List<BotEvent> events = BotEventResolver.resolve(a);
            assertThat(events.stream().map(BotEvent::type))
                    .doesNotContain(BotEventType.OWNER_REJECTED)
                    .contains(BotEventType.OWNER_APPROVED_CANCEL);
        }
    }

    @Nested
    @DisplayName("BRANCH 5 — BOT_PENDING_EXPIRED (SYSTEM approval timeout)")
    class BotPendingExpired {

        @Test
        @DisplayName("BOT + CANCELLED + SYSTEM + TIMEOUT_BOT_APPROVAL reason → BOT_PENDING_EXPIRED only")
        void timeoutExpiredProposal() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CANCELLED);
            a.setCancellationActor("SYSTEM");
            a.setCancellationSource("JOB");
            a.setCancellationReason("TIMEOUT_BOT_APPROVAL");
            a.setCancelledAt(POST_AUDIT_CUTOFF.plusHours(6));

            List<BotEvent> events = BotEventResolver.resolve(a);
            assertTypes(events, BotEventType.BOT_PENDING_EXPIRED);
            BotEvent e = events.get(0);
            assertThat(e.actorType()).isEqualTo("SYSTEM");
            assertThat(e.actorUserId()).isNull();
        }

        @Test
        @DisplayName("Discriminator matches on prefix, not exact string — extended reason also qualifies")
        void timeoutReasonPrefixMatch() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CANCELLED);
            a.setCancellationActor("SYSTEM");
            a.setCancellationReason("TIMEOUT_BOT_APPROVAL_36H");
            a.setCancelledAt(POST_AUDIT_CUTOFF.plusHours(36));

            List<BotEvent> events = BotEventResolver.resolve(a);
            assertTypes(events, BotEventType.BOT_PENDING_EXPIRED);
        }

        @Test
        @DisplayName("SYSTEM cancel with a different reason → BOT_AUTO_CONFIRMED (not BOT_PENDING_EXPIRED)")
        void systemCancelWithDifferentReason() {
            // A future SYSTEM job could use cancellation_actor=SYSTEM with a
            // different reason. The TIMEOUT-prefix exclusion in BRANCH 2 does NOT
            // apply → BOT_AUTO_CONFIRMED emits as the creation event.
            // When a new SYSTEM job is introduced, extend the taxonomy with a new
            // BotEventType rather than expanding the existing prefix check.
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CANCELLED);
            a.setCancellationActor("SYSTEM");
            a.setCancellationReason("OTHER_REASON");
            a.setCancelledAt(POST_AUDIT_CUTOFF.plusHours(6));

            List<BotEvent> events = BotEventResolver.resolve(a);
            assertTypes(events, BotEventType.BOT_AUTO_CONFIRMED);
        }
    }

    @Nested
    @DisplayName("BRANCH 6 — BOT_CANCEL_REQUESTED + CR resolutions (6a/6b/6c)")
    class CancellationRequestFlow {

        @Test
        @DisplayName("BRANCH 6 alone: bot-initiated CR pending → BOT_CANCEL_REQUESTED only (in sequence)")
        void crPending() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CANCELLATION_REQUESTED);
            a.setApprovedAt(POST_AUDIT_CUTOFF.plusMinutes(5));
            a.setApprovedByUserId(42L);
            a.setCancellationRequestedActor("BOT");
            a.setCancellationRequestedAt(POST_AUDIT_CUTOFF.plusHours(1));
            a.setCancellationRequestReason("Customer requested cancellation via messaging channel");

            List<BotEvent> events = BotEventResolver.resolve(a);
            assertTypes(events,
                    BotEventType.BOT_PROPOSED,
                    BotEventType.OWNER_APPROVED,
                    BotEventType.BOT_CANCEL_REQUESTED);
            assertThat(events.get(2).actorType()).isEqualTo("BOT");
            assertThat(events.get(2).reason())
                    .isEqualTo("Customer requested cancellation via messaging channel");
        }

        @Test
        @DisplayName("6a OWNER_APPROVED_CANCEL: CR + decided_at + status=CANCELLED")
        void ownerApprovedCancel() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CANCELLED);
            a.setApprovedAt(POST_AUDIT_CUTOFF.plusMinutes(5));
            a.setApprovedByUserId(42L);
            a.setCancellationRequestedActor("BOT");
            a.setCancellationRequestedAt(POST_AUDIT_CUTOFF.plusHours(1));
            a.setCancellationRequestDecidedAt(POST_AUDIT_CUTOFF.plusHours(2));
            a.setCancellationRequestDecidedByUserId(77L);
            // 3-actor model: owner executed the cancellation via panel
            a.setCancellationActor("OWNER");
            a.setCancellationSource("PANEL");
            a.setCancelledAt(POST_AUDIT_CUTOFF.plusHours(2));

            List<BotEvent> events = BotEventResolver.resolve(a);
            assertTypes(events,
                    BotEventType.BOT_PROPOSED,
                    BotEventType.OWNER_APPROVED,
                    BotEventType.BOT_CANCEL_REQUESTED,
                    BotEventType.OWNER_APPROVED_CANCEL);
            BotEvent approvedCancel = events.get(3);
            assertThat(approvedCancel.actorType()).isEqualTo("OWNER");
            assertThat(approvedCancel.actorUserId()).isEqualTo(77L);
        }

        @Test
        @DisplayName("6b OWNER_REJECTED_CANCEL: CR + decided_at + status=CONFIRMED (appointment reverted)")
        void ownerRejectedCancel() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CONFIRMED);
            a.setApprovedAt(POST_AUDIT_CUTOFF.plusMinutes(5));
            a.setApprovedByUserId(42L);
            a.setCancellationRequestedActor("BOT");
            a.setCancellationRequestedAt(POST_AUDIT_CUTOFF.plusHours(1));
            a.setCancellationRequestDecidedAt(POST_AUDIT_CUTOFF.plusHours(2));
            a.setCancellationRequestDecidedByUserId(77L);
            a.setCancellationRequestDecisionReason("Booking already charged, customer must attend");

            List<BotEvent> events = BotEventResolver.resolve(a);
            assertTypes(events,
                    BotEventType.BOT_PROPOSED,
                    BotEventType.OWNER_APPROVED,
                    BotEventType.BOT_CANCEL_REQUESTED,
                    BotEventType.OWNER_REJECTED_CANCEL);
            BotEvent rejected = events.get(3);
            assertThat(rejected.result()).isEqualTo("CONFIRMED");
            assertThat(rejected.reason()).isEqualTo("Booking already charged, customer must attend");
            // 3-actor model: cancellationActor stays NULL (no cancellation was executed)
            assertThat(a.getCancellationActor()).isNull();
        }

        @Test
        @DisplayName("6c CR_TIMEOUT_EXPIRED: CR + expired_at + no decided_at")
        void crTimeoutExpired() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CONFIRMED);
            a.setApprovedAt(POST_AUDIT_CUTOFF.plusMinutes(5));
            a.setApprovedByUserId(42L);
            a.setCancellationRequestedActor("BOT");
            a.setCancellationRequestedAt(POST_AUDIT_CUTOFF.plusHours(1));
            a.setCancellationRequestExpiredAt(POST_AUDIT_CUTOFF.plusHours(13));

            List<BotEvent> events = BotEventResolver.resolve(a);
            assertTypes(events,
                    BotEventType.BOT_PROPOSED,
                    BotEventType.OWNER_APPROVED,
                    BotEventType.BOT_CANCEL_REQUESTED,
                    BotEventType.CR_TIMEOUT_EXPIRED);
            assertThat(events.get(3).actorType()).isEqualTo("SYSTEM");
        }
    }

    @Nested
    @DisplayName("BRANCH 10 — BOT_CANCELLED (auto-confirm direct cancel)")
    class BotCancelled {

        @Test
        @DisplayName("CANCELLED + BOT actor + BOT source → BOT_AUTO_CONFIRMED + BOT_CANCELLED")
        void autoConfirmDirectBotCancel() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CANCELLED);
            a.setCancellationActor("BOT");
            a.setCancellationSource("BOT");
            a.setCancellationReason("Customer cancelled via bot direct");
            a.setCancelledAt(POST_AUDIT_CUTOFF.plusDays(2));

            List<BotEvent> events = BotEventResolver.resolve(a);
            assertTypes(events,
                    BotEventType.BOT_AUTO_CONFIRMED,
                    BotEventType.BOT_CANCELLED);
            assertThat(events.get(1).actorType()).isEqualTo("BOT");
            assertThat(events.get(1).reason()).isEqualTo("Customer cancelled via bot direct");
        }
    }

    @Nested
    @DisplayName("BRANCH 11 — BOT_REVOKED + revoke filter")
    class BotRevoked {

        @Test
        @DisplayName("Revoke after OWNER_REJECTED — lineage: BOT_PROPOSED + BOT_REVOKED")
        void revokeAfterOwnerReject() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CONFIRMED); // post-revoke
            // Preserved cancel fields (revoke audit preservation invariant)
            a.setCancellationActor("OWNER");
            a.setCancellationSource("PANEL");
            a.setCancelledAt(POST_AUDIT_CUTOFF.plusHours(2));
            a.setCancelledByUserId(77L);
            a.setRevokedAt(POST_AUDIT_CUTOFF.plusHours(3));
            a.setRevokedByUserId(77L);

            List<BotEvent> events = BotEventResolver.resolve(a);
            // BOT_PROPOSED preserved (lineage). Revoke filter removes events
            // contradicting current CONFIRMED status.
            assertTypes(events, BotEventType.BOT_PROPOSED, BotEventType.BOT_REVOKED);
            BotEvent revoked = events.get(1);
            assertThat(revoked.actorType()).isEqualTo("OWNER");
            assertThat(revoked.actorUserId()).isEqualTo(77L);
            assertThat(revoked.metadata()).containsEntry("previous_cancel_actor", "OWNER");
        }

        @Test
        @DisplayName("Revoke after BOT_PENDING_EXPIRED — lineage: BOT_PROPOSED + BOT_REVOKED")
        void revokeAfterPendingExpired() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CONFIRMED); // post-revoke
            a.setCancellationActor("SYSTEM");
            a.setCancellationSource("JOB");
            a.setCancellationReason("TIMEOUT_BOT_APPROVAL");
            a.setCancelledAt(POST_AUDIT_CUTOFF.plusHours(6));
            a.setRevokedAt(POST_AUDIT_CUTOFF.plusHours(7));
            a.setRevokedByUserId(77L);

            List<BotEvent> events = BotEventResolver.resolve(a);
            assertTypes(events, BotEventType.BOT_PROPOSED, BotEventType.BOT_REVOKED);
            assertThat(events.get(1).metadata())
                    .containsEntry("previous_cancel_actor", "SYSTEM");
        }

        @Test
        @DisplayName("Revoke after BOT_CANCELLED (auto-confirm) — revoke filter removes BOT_CANCELLED")
        void revokeAfterBotCancelled() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CONFIRMED); // post-revoke
            a.setCancellationActor("BOT");
            a.setCancellationSource("BOT");
            a.setCancelledAt(POST_AUDIT_CUTOFF.plusDays(1));
            a.setRevokedAt(POST_AUDIT_CUTOFF.plusDays(2));
            a.setRevokedByUserId(77L);

            List<BotEvent> events = BotEventResolver.resolve(a);
            // BOT_CANCELLED would not fire (status=CONFIRMED breaks BRANCH 10).
            // BOT_AUTO_CONFIRMED fires. BOT_REVOKED fires.
            assertTypes(events, BotEventType.BOT_AUTO_CONFIRMED, BotEventType.BOT_REVOKED);
            assertThat(events.get(1).metadata()).containsEntry("previous_cancel_actor", "BOT");
        }

        @Test
        @DisplayName("Revoke after OWNER_APPROVED_CANCEL — filter removes OWNER_APPROVED_CANCEL, CR lineage preserved")
        void revokeAfterCrApproved() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CONFIRMED); // post-revoke
            a.setApprovedAt(POST_AUDIT_CUTOFF.plusMinutes(5));
            a.setApprovedByUserId(42L);
            a.setCancellationRequestedActor("BOT");
            a.setCancellationRequestedAt(POST_AUDIT_CUTOFF.plusHours(1));
            a.setCancellationRequestDecidedAt(POST_AUDIT_CUTOFF.plusHours(2));
            a.setCancellationRequestDecidedByUserId(77L);
            a.setCancellationActor("OWNER");
            a.setCancellationSource("PANEL");
            a.setCancelledAt(POST_AUDIT_CUTOFF.plusHours(2));
            a.setRevokedAt(POST_AUDIT_CUTOFF.plusHours(3));
            a.setRevokedByUserId(77L);

            List<BotEvent> events = BotEventResolver.resolve(a);
            // OWNER_APPROVED_CANCEL does not fire post-revoke (status=CONFIRMED breaks BRANCH 6a).
            // OWNER_REJECTED_CANCEL fires instead (status=CONFIRMED + decided_at present).
            // This is the documented limitation of the derive-at-query-time approach:
            // operational audit vs forensic audit (see ADR-007).
            assertTypes(events,
                    BotEventType.BOT_PROPOSED,
                    BotEventType.OWNER_APPROVED,
                    BotEventType.BOT_CANCEL_REQUESTED,
                    BotEventType.OWNER_REJECTED_CANCEL,
                    BotEventType.BOT_REVOKED);
        }
    }

    @Nested
    @DisplayName("DEFAULT — exhaustive guard (fail-loud canary)")
    class DefaultBranch {

        @Test
        @DisplayName("Bot-relevant row with no matching branch → empty list + ObservabilityHelper invoked")
        void degenerateState_triggersDefault() {
            // PANEL row + cancellationRequestedActor=BOT passes the bot-relevance pre-check,
            // but BRANCH 6 fails (no cancellationRequestedAt), and source != BOT excludes
            // BRANCHES 1-5 and 10.
            Appointment a = panelConfirmed();
            a.setCancellationRequestedActor("BOT");
            // Intentionally no cancellationRequestedAt set

            try (MockedStatic<ObservabilityHelper> mocked =
                         Mockito.mockStatic(ObservabilityHelper.class)) {
                List<BotEvent> events = BotEventResolver.resolve(a);
                assertThat(events).isEmpty();
                mocked.verify(() -> ObservabilityHelper.reportBackgroundFailure(
                        "bot-event-resolver-unhandled-state", null));
            }
        }
    }

    @Nested
    @DisplayName("Sorting and ordering")
    class Ordering {

        @Test
        @DisplayName("Multi-event row sorts by occurredAt ASC")
        void sortsByOccurredAt() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CONFIRMED);
            a.setApprovedAt(POST_AUDIT_CUTOFF.plusMinutes(30));
            a.setApprovedByUserId(42L);

            List<BotEvent> events = BotEventResolver.resolve(a);
            assertThat(events).hasSize(2);
            assertThat(events.get(0).occurredAt()).isBefore(events.get(1).occurredAt());
        }

        @Test
        @DisplayName("Same occurredAt: tie-broken by BotEventType.ordinal() ASC")
        void tieBrokenByOrdinal() {
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CONFIRMED);
            // Force same timestamp for BOT_PROPOSED creation and OWNER_APPROVED
            a.setApprovedAt(POST_AUDIT_CUTOFF);
            a.setApprovedByUserId(42L);

            List<BotEvent> events = BotEventResolver.resolve(a);
            assertThat(events).hasSize(2);
            assertThat(events.get(0).type().ordinal())
                    .isLessThan(events.get(1).type().ordinal());
            assertThat(events.get(0).type()).isEqualTo(BotEventType.BOT_PROPOSED);
            assertThat(events.get(1).type()).isEqualTo(BotEventType.OWNER_APPROVED);
        }
    }

    @Nested
    @DisplayName("Defensive null-handling")
    class NullHandling {

        @Test
        @DisplayName("Null occurredAt on a CANCELLED row skips event without NPE on sort")
        void nullOccurredAtSkipped() {
            // OWNER_REJECTED discriminator but with cancelledAt = null (degenerate).
            // buildEvent() returns null, removeIf filters it out, then DEFAULT canary fires.
            Appointment a = botPending();
            a.setStatus(AppointmentStatus.CANCELLED);
            a.setCancellationActor("OWNER");
            a.setCancellationSource("PANEL");
            // cancelledAt remains null

            try (MockedStatic<ObservabilityHelper> mocked =
                         Mockito.mockStatic(ObservabilityHelper.class)) {
                List<BotEvent> events = BotEventResolver.resolve(a);
                assertThat(events).isEmpty();
                mocked.verify(() -> ObservabilityHelper.reportBackgroundFailure(
                        "bot-event-resolver-unhandled-state", null));
            }
        }

        @Test
        @DisplayName("Resolver returns an immutable list")
        void immutableList() {
            List<BotEvent> events = BotEventResolver.resolve(botPending());
            assertThatThrownBy(() -> events.add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // PART 2 — Purity guarantees
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Class shape — no Spring wiring, no instance state")
    class ClassShape {

        @Test
        @DisplayName("Class is final — cannot be subclassed to introduce side effects")
        void classIsFinal() {
            assertThat(Modifier.isFinal(BotEventResolver.class.getModifiers()))
                    .as("BotEventResolver must be final")
                    .isTrue();
        }

        @Test
        @DisplayName("No Spring stereotype annotations (@Service / @Component / @Repository)")
        void noSpringStereotypes() {
            for (Annotation a : BotEventResolver.class.getAnnotations()) {
                String name = a.annotationType().getName();
                assertThat(name)
                        .as("Spring stereotype annotation forbidden: " + name)
                        .doesNotStartWith("org.springframework.stereotype")
                        .doesNotEndWith(".Service")
                        .doesNotEndWith(".Component")
                        .doesNotEndWith(".Repository")
                        .doesNotEndWith(".Configuration");
            }
        }

        @Test
        @DisplayName("No instance fields — only static final constants allowed")
        void noInstanceFields() {
            for (Field f : BotEventResolver.class.getDeclaredFields()) {
                int mods = f.getModifiers();
                if (f.isSynthetic()) continue; // skip compiler-injected fields
                assertThat(Modifier.isStatic(mods))
                        .as("Field %s must be static", f.getName())
                        .isTrue();
                assertThat(Modifier.isFinal(mods))
                        .as("Static field %s must be final", f.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("No @Autowired / @Inject / @Resource annotations on any field")
        void noInjectionAnnotations() {
            for (Field f : BotEventResolver.class.getDeclaredFields()) {
                if (f.isSynthetic()) continue;
                for (Annotation a : f.getAnnotations()) {
                    String name = a.annotationType().getName();
                    assertThat(name)
                            .as("Injection annotation forbidden on field %s: %s", f.getName(), name)
                            .doesNotEndWith(".Autowired")
                            .doesNotEndWith(".Inject")
                            .doesNotEndWith(".Resource");
                }
            }
        }

        @Test
        @DisplayName("Single private constructor — utility class lock")
        void privateConstructorOnly() {
            Constructor<?>[] ctors = BotEventResolver.class.getDeclaredConstructors();
            assertThat(ctors).hasSize(1);
            assertThat(Modifier.isPrivate(ctors[0].getModifiers())).isTrue();
        }

        @Test
        @DisplayName("All public methods are static — no instance methods exposed")
        void allPublicMethodsStatic() {
            Method[] declared = BotEventResolver.class.getDeclaredMethods();
            List<Method> publicMethods = Arrays.stream(declared)
                    .filter(m -> Modifier.isPublic(m.getModifiers()))
                    .toList();
            assertThat(publicMethods).isNotEmpty();
            for (Method m : publicMethods) {
                assertThat(Modifier.isStatic(m.getModifiers()))
                        .as("Public method %s must be static", m.getName())
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Determinism — same input → same output")
    class Determinism {

        @Test
        @DisplayName("Identical appointment state → identical event list across invocations")
        void identicalInputProducesIdenticalOutput() {
            Appointment a = sampleApproved();
            List<BotEvent> first = BotEventResolver.resolve(a);
            List<BotEvent> second = BotEventResolver.resolve(a);
            assertThat(first).isEqualTo(second);
            assertThat(first.get(0).id()).isEqualTo(second.get(0).id());
            assertThat(first.get(1).id()).isEqualTo(second.get(1).id());
        }

        @Test
        @DisplayName("Multiple resolves do not mutate the input Appointment")
        void doesNotMutateInput() {
            Appointment a = sampleApproved();
            LocalDateTime originalCreatedAt = a.getCreatedAt();
            LocalDateTime originalApprovedAt = a.getApprovedAt();
            Long originalApprovedBy = a.getApprovedByUserId();
            AppointmentStatus originalStatus = a.getStatus();

            BotEventResolver.resolve(a);
            BotEventResolver.resolve(a);
            BotEventResolver.resolve(a);

            assertThat(a.getCreatedAt()).isEqualTo(originalCreatedAt);
            assertThat(a.getApprovedAt()).isEqualTo(originalApprovedAt);
            assertThat(a.getApprovedByUserId()).isEqualTo(originalApprovedBy);
            assertThat(a.getStatus()).isEqualTo(originalStatus);
        }

        @Test
        @DisplayName("Returned list is unmodifiable — caller cannot mutate resolver output")
        void returnedListUnmodifiable() {
            List<BotEvent> events = BotEventResolver.resolve(sampleApproved());
            assertThat(events.getClass().getName())
                    .as("Resolver must return an immutable list")
                    .contains("Immutable");
        }
    }

    @Nested
    @DisplayName("Constants — V69_DEPLOY_TIMESTAMP is public static final")
    class Constants {

        @Test
        @DisplayName("V69_DEPLOY_TIMESTAMP is public static final LocalDateTime")
        void deployTimestampExposed() throws Exception {
            Field f = BotEventResolver.class.getDeclaredField("V69_DEPLOY_TIMESTAMP");
            int mods = f.getModifiers();
            assertThat(Modifier.isPublic(mods)).isTrue();
            assertThat(Modifier.isStatic(mods)).isTrue();
            assertThat(Modifier.isFinal(mods)).isTrue();
            assertThat(f.getType()).isEqualTo(LocalDateTime.class);
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private static Appointment sampleApproved() {
        Appointment a = Appointment.builder()
                .source(AppointmentSource.BOT)
                .status(AppointmentStatus.CONFIRMED)
                .datetime(POST_AUDIT_CUTOFF.plusDays(1))
                .durationMinutes(30)
                .build();
        a.setId(99L);
        a.setCreatedAt(POST_AUDIT_CUTOFF);
        a.setApprovedAt(POST_AUDIT_CUTOFF.plusMinutes(5));
        a.setApprovedByUserId(42L);
        return a;
    }
}
