package com.vookedme.botmanager.auth.service;

import com.vookedme.botmanager.auth.entity.ConsentAudit;
import com.vookedme.botmanager.auth.entity.ConsentType;
import com.vookedme.botmanager.auth.entity.User;
import com.vookedme.botmanager.auth.repository.ConsentAuditRepository;
import com.vookedme.botmanager.auth.repository.UserRepository;
import com.vookedme.botmanager.business.entity.Business;
import com.vookedme.botmanager.business.repository.BusinessRepository;
import com.vookedme.botmanager.common.exception.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * GDPR consent recording service.
 *
 * <p>Called from two entry points:
 * <ul>
 *   <li>{@code AuthService.registerBusiness()} — self-service OWNER registration.</li>
 *   <li>{@code POST /auth/accept-legal} — first login of an EMPLOYEE created by
 *       an OWNER or ADMIN.</li>
 * </ul>
 *
 * <p>Each call inserts two rows into {@code consent_audit} (one per type:
 * TERMS and PRIVACY) and updates the acceptance timestamps on {@code users}.
 * No transaction is opened here — the caller decides the transactional scope.
 * If the second row insertion fails, the first is rolled back with the rest.
 *
 * <p><b>Timestamp:</b> {@link OffsetDateTime#now(ZoneOffset)} with
 * {@link ZoneOffset#UTC} — consent evidence requires an explicit UTC timestamp,
 * not a wall-clock value that depends on the JVM's timezone setting (see
 * {@link com.vookedme.botmanager.config.JvmTimezoneInvariant} for the
 * architectural context of that distinction).
 *
 * <p>{@code ConsentAuditRepository}, {@code UserRepository}, and
 * {@code BusinessRepository} are published in a subsequent source batch.
 * {@code User} and {@code Business} entities are published in a subsequent
 * source batch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsentService {

    private final ConsentAuditRepository consentAuditRepository;
    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;

    @Value("${app.legal.terms-version}")
    private String termsVersion;

    @Value("${app.legal.privacy-version}")
    private String privacyVersion;

    @Value("${app.legal.terms-checksum:}")
    private String termsChecksum;

    @Value("${app.legal.privacy-checksum:}")
    private String privacyChecksum;

    /** DPA version string (date-based, e.g. "2026-05-27"). */
    @Value("${app.legal.dpa-version}")
    private String dpaVersion;

    /** DPA SHA-256 checksum (used for GDPR evidence; configured via env var in production). */
    @Value("${app.legal.dpa-checksum:}")
    private String dpaChecksum;

    /**
     * Records the user's acceptance of the Terms of Service and Privacy Policy.
     * Inserts two rows into {@code consent_audit} and updates the cached timestamps
     * on {@code users}. Sets {@code mustAcceptLegal} to false.
     *
     * <p><b>Idempotency:</b> if called twice in the same session, four rows are
     * inserted with distinct {@code accepted_at} values. The second call is not
     * an error — it records a valid re-acceptance (e.g., after a policy version
     * change).
     *
     * @param user    the user accepting. Will be modified and saved.
     * @param request HTTP context — IP, User-Agent, Accept-Language.
     */
    public void recordConsent(User user, HttpServletRequest request) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String ip = extractIp(request);
        String userAgent = request.getHeader("User-Agent");
        String language = extractLanguage(request);

        ConsentAudit termsRow = ConsentAudit.builder()
                .userId(user.getId())
                .consentType(ConsentType.TERMS)
                .policyVersion(termsVersion)
                .acceptedAt(now)
                .ipAddress(ip)
                .userAgent(userAgent)
                .language(language)
                .checksumLegal(nullIfBlank(termsChecksum))
                .build();

        ConsentAudit privacyRow = ConsentAudit.builder()
                .userId(user.getId())
                .consentType(ConsentType.PRIVACY)
                .policyVersion(privacyVersion)
                .acceptedAt(now)
                .ipAddress(ip)
                .userAgent(userAgent)
                .language(language)
                .checksumLegal(nullIfBlank(privacyChecksum))
                .build();

        consentAuditRepository.save(termsRow);
        consentAuditRepository.save(privacyRow);

        user.setTermsAcceptedAt(now);
        user.setPrivacyAcceptedAt(now);
        user.setMustAcceptLegal(false);
        userRepository.save(user);

        // Do not log IP or User-Agent: PII. The audit row already stores those
        // fields for regulatory audit purposes.
        log.info("Consent recorded for userId={} (terms v{} + privacy v{})",
                user.getId(), termsVersion, privacyVersion);
    }

    /**
     * Extracts the client IP. Prefers the first hop of {@code X-Forwarded-For}
     * (standard behind a proxy or load balancer) and falls back to
     * {@code remoteAddr}. Capped at 45 characters to cover IPv6.
     */
    private String extractIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (first.length() <= 45) {
                return first;
            }
            // Truncate defensively; a malformed XFF header must not cause a
            // DataIntegrityViolationException during registration.
            return first.substring(0, 45);
        }
        String remote = request.getRemoteAddr();
        if (remote != null && remote.length() > 45) {
            return remote.substring(0, 45);
        }
        return remote;
    }

    /**
     * Extracts the language tag from {@code Accept-Language}. Takes only the
     * first tag and the first two characters. Defaults to {@code "es"} if the
     * header is absent or unparseable.
     */
    private String extractLanguage(HttpServletRequest request) {
        String header = request.getHeader("Accept-Language");
        if (header == null || header.isBlank()) {
            return "es";
        }
        String first = header.split(",")[0].trim();
        if (first.length() < 2) {
            return "es";
        }
        return first.substring(0, 2).toLowerCase();
    }

    private String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    // ════════════════════════════════════════════════════════════════════════
    // DPA corporate acceptance
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Records the corporate acceptance of the DPA (Data Processing Agreement,
     * Art. 28 GDPR) for the business.
     *
     * <p><b>Architectural lock:</b> EMPLOYEE acceptance ≠ DPA acceptance.
     * Only the business OWNER may invoke this flow. An EMPLOYEE attempting
     * it receives a {@link ForbiddenException} — defence in depth over the
     * {@code @PreAuthorize("hasRole('OWNER')")} guard at the endpoint level,
     * which catches HTTP callers but not future intra-backend callers.
     *
     * <p><b>Effect:</b> inserts one {@code consent_audit} row with
     * {@link ConsentType#DPA_CORPORATE} and updates the DPA acceptance columns
     * on {@code businesses} ({@code dpa_accepted_at}, {@code dpa_accepted_by_user_id},
     * {@code dpa_signed_version}) in the same transaction.
     *
     * <p><b>Idempotency per version:</b> if the business has already signed the
     * current DPA version, the call is a no-op (no duplicate row, no update).
     * Useful for frontend retries and for re-prompting after a version bump.
     *
     * <p><b>Multi-OWNER race:</b> without {@code @Version} on {@code Business},
     * two concurrent OWNER acceptances may both insert a {@code consent_audit}
     * row and last-write-wins on the {@code businesses} DPA columns — two audit
     * rows (both legitimate, different human signers) and a consistent final
     * state. Documented accepted behaviour.
     *
     * <p><b>Re-acceptance after version bump:</b> when {@code app.legal.dpa-version}
     * changes (material change), {@link #computeMustAcceptDpa} returns {@code true}
     * on the next login and the OWNER passes through this path again. A new
     * {@code consent_audit} row is inserted for the new version.
     *
     * @param business  the business whose DPA is being accepted. Will be modified and saved.
     * @param ownerUser the OWNER user signing. Must belong to the business.
     * @param request   HTTP context — IP, User-Agent, Accept-Language.
     * @throws ForbiddenException if {@code ownerUser.role != OWNER} or if
     *                            {@code ownerUser.business.id != business.id}.
     */
    public void recordDpaAcceptance(Business business, User ownerUser, HttpServletRequest request) {
        // Defence-in-depth role check. The endpoint @PreAuthorize covers HTTP;
        // this check covers any future intra-backend caller.
        if (ownerUser.getRole() == null
                || !"OWNER".equals(ownerUser.getRole().getName())) {
            log.warn("SECURITY: non-OWNER attempt to record DPA acceptance userId={} role={}",
                    ownerUser.getId(),
                    ownerUser.getRole() != null ? ownerUser.getRole().getName() : "null");
            // LOCALE OUTPUT (Spanish): forbidden error message for DPA role violation
            throw new ForbiddenException(
                    "Solo el OWNER del negocio puede aceptar el DPA.");
        }

        // Defence-in-depth tenant check. The controller derives the business from the
        // JWT principal, but this service-level check guards any future caller that
        // constructs the (business, user) pair manually.
        if (ownerUser.getBusiness() == null
                || !ownerUser.getBusiness().getId().equals(business.getId())) {
            log.warn("SECURITY: cross-tenant DPA acceptance attempt userId={} userBusinessId={} targetBusinessId={}",
                    ownerUser.getId(),
                    ownerUser.getBusiness() != null ? ownerUser.getBusiness().getId() : null,
                    business.getId());
            // LOCALE OUTPUT (Spanish): forbidden error message for cross-tenant DPA attempt
            throw new ForbiddenException(
                    "Operación no autorizada para este negocio.");
        }

        // Idempotency: same version already signed → no-op success.
        if (business.getDpaAcceptedAt() != null
                && dpaVersion.equals(business.getDpaSignedVersion())) {
            log.info("DPA already accepted at version {} for businessId={} (idempotent no-op)",
                    dpaVersion, business.getId());
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String ip = extractIp(request);
        String userAgent = request.getHeader("User-Agent");
        String language = extractLanguage(request);

        ConsentAudit dpaRow = ConsentAudit.builder()
                .userId(ownerUser.getId())
                .consentType(ConsentType.DPA_CORPORATE)
                .policyVersion(dpaVersion)
                .acceptedAt(now)
                .ipAddress(ip)
                .userAgent(userAgent)
                .language(language)
                .checksumLegal(nullIfBlank(dpaChecksum))
                .build();
        consentAuditRepository.save(dpaRow);

        // Update DPA acceptance columns on businesses.
        // Audit traceability via dpa_accepted_by_user_id — NEVER used for live
        // authorisation; the gate consults dpa_accepted_at + dpa_signed_version
        // at the business level, not the signer identity.
        business.setDpaAcceptedAt(now);
        business.setDpaAcceptedByUserId(ownerUser.getId());
        business.setDpaSignedVersion(dpaVersion);
        businessRepository.save(business);

        // Do not log IP or User-Agent: PII. The audit row already stores them.
        log.info("DPA accepted for businessId={} signedByUserId={} version={}",
                business.getId(), ownerUser.getId(), dpaVersion);
    }

    /**
     * Computes the {@code mustAcceptDpa} flag surfaced in the auth response.
     * The frontend consumes this to decide whether to redirect to the DPA
     * acceptance page.
     *
     * <p><b>Rules:</b>
     * <ul>
     *   <li>Only applies to OWNER. EMPLOYEE, ADMIN, and users without a
     *       business → {@code false}.</li>
     *   <li>OWNER with active business + {@code dpa_accepted_at IS NULL}
     *       → {@code true} (never signed).</li>
     *   <li>OWNER with {@code dpa_signed_version != currentDpaVersion}
     *       → {@code true} (material version bump).</li>
     *   <li>Inactive business → {@code false}.</li>
     * </ul>
     *
     * <p><b>Business-level validation:</b> this method never checks whether
     * the current user is the original signer — only the state of the business.
     * If the original OWNER was deactivated, the corporate acceptance persists
     * and this method returns {@code false} for the current OWNER.
     */
    public boolean computeMustAcceptDpa(User user) {
        if (user == null
                || user.getRole() == null
                || !"OWNER".equals(user.getRole().getName())) {
            return false;
        }
        Business business = user.getBusiness();
        if (business == null || !Boolean.TRUE.equals(business.getActive())) {
            return false;
        }
        if (business.getDpaAcceptedAt() == null) {
            return true;
        }
        return !dpaVersion.equals(business.getDpaSignedVersion());
    }

    /**
     * Computes the immediate (per-request) acceptance status for
     * {@link com.vookedme.botmanager.auth.security.LegalAcceptanceFilter}.
     *
     * <p>Single database round-trip plus computation of both flags.
     * Uses {@code @Transactional(readOnly=true)} so that the lazy access to
     * {@code user.getBusiness()} from {@link #computeMustAcceptDpa} is within
     * a transaction scope without depending on open-in-view.
     *
     * <p>Returns {@link AcceptanceStatus#UNKNOWN} if the user does not exist —
     * the filter decides how to handle that case.
     */
    @Transactional(readOnly = true)
    public AcceptanceStatus checkAcceptanceStatus(Long userId) {
        if (userId == null) {
            return AcceptanceStatus.UNKNOWN;
        }
        return userRepository.findById(userId)
                .map(user -> new AcceptanceStatus(
                        Boolean.TRUE.equals(user.getMustAcceptLegal()),
                        computeMustAcceptDpa(user)))
                .orElse(AcceptanceStatus.UNKNOWN);
    }

    /**
     * Snapshot of the legal and corporate acceptance state for a user,
     * surfaced by {@link #checkAcceptanceStatus} to the
     * {@link com.vookedme.botmanager.auth.security.LegalAcceptanceFilter}.
     *
     * <p>Immutable, transportable, detached from any entity.
     */
    public record AcceptanceStatus(boolean mustAcceptLegal, boolean mustAcceptDpa) {
        /** Sentinel: user not found or null id → do not block, pass through. */
        public static final AcceptanceStatus UNKNOWN = new AcceptanceStatus(false, false);
    }
}
