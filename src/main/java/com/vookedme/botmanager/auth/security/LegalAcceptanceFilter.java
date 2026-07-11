package com.vookedme.botmanager.auth.security;

import com.vookedme.botmanager.auth.service.ConsentService;
import com.vookedme.botmanager.auth.service.ConsentService.AcceptanceStatus;
import com.vookedme.botmanager.common.dto.ApiResponse;
// Spring Boot 4 + Jackson 3 — the auto-configured bean lives in the
// `tools.jackson.databind` package, NOT in the legacy `com.fasterxml.jackson.databind`.
// Using the legacy import here would cause NoSuchBeanDefinitionException on startup.
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Backend enforcement of legal acceptance state for authenticated users.
 *
 * <p><b>Architectural problem this closes:</b> before this filter was added,
 * legal acceptance enforcement was purely client-side (frontend redirect). If
 * the frontend missed the redirect, the user proceeded with a valid JWT and
 * full API access. This filter closes that gap at the backend.
 *
 * <p><b>Architectural lock:</b> EMPLOYEE acceptance ≠ DPA acceptance. EMPLOYEE
 * accepts platform-use terms and conditions plus their own account privacy policy.
 * Only the OWNER role accepts the DPA (Data Processing Agreement, Art. 28 GDPR),
 * business contractual terms, processor agreement, and AI processing clauses.
 *
 * <p><b>Behavior:</b>
 * <ol>
 *   <li>Only acts on JWT-authenticated requests where the principal is a
 *       {@code UserPrincipal}. Anonymous principals and webhook X-API-Key
 *       principals (different type) pass through.</li>
 *   <li>Whitelist of URIs that must remain reachable while the user is in
 *       pending-acceptance state: {@code /auth/accept-legal},
 *       {@code /auth/accept-dpa}, {@code /auth/me}, {@code /auth/logout},
 *       {@code /auth/refresh}, {@code /actuator/health},
 *       {@code /actuator/info}.</li>
 *   <li>For every other request: reads fresh {@link AcceptanceStatus} via
 *       {@link ConsentService#checkAcceptanceStatus(Long)}. If
 *       {@code mustAcceptLegal=true} → 403 with body
 *       {@code {requires: "LEGAL_ACCEPTANCE", ...}}. If
 *       {@code mustAcceptDpa=true} → 403 with body
 *       {@code {requires: "DPA_ACCEPTANCE", ...}}. Otherwise → next filter.</li>
 * </ol>
 *
 * <p><b>Defence in depth:</b> the DTO {@code @AssertTrue} constraint and
 * {@code @PreAuthorize("hasRole('OWNER')")} at the endpoint level cover the
 * happy path. This filter catches the broader case: any authenticated user who
 * skipped the frontend acceptance flow and attempts to invoke any non-whitelisted
 * endpoint.
 *
 * <p><b>ADMIN platform users:</b> pass through the DPA gate naturally (no
 * business → {@code computeMustAcceptDpa=false}). They still must accept
 * TOS + Privacy if {@code mustAcceptLegal=true}.
 *
 * <p><b>Why this filter runs after {@link JwtAuthenticationFilter}:</b> needs the
 * populated {@code SecurityContext} principal. Webhook auth (X-API-Key) populates
 * a different principal type and the filter naturally skips.
 *
 * <p>{@code UserPrincipal} is published in a subsequent source batch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LegalAcceptanceFilter extends OncePerRequestFilter {

    private final ConsentService consentService;
    private final ObjectMapper objectMapper;

    /**
     * Endpoints that must remain reachable while the user is in pending-acceptance
     * state. The first three are the "fix the problem" endpoints; the rest are
     * operational essentials that must not be blocked.
     *
     * <p>If a new endpoint is needed before the user has accepted (e.g., a
     * "load legal text" GET), add it here and document the reason.
     */
    private static final Set<String> WHITELIST_PREFIXES = Set.of(
            "/auth/accept-legal",
            "/auth/accept-dpa",
            "/auth/me",
            "/auth/logout",
            "/auth/refresh",
            "/actuator/health",
            "/actuator/info"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // Skip anonymous principals and non-JWT principals (webhook X-API-Key sets a different type).
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }

        // Skip whitelisted endpoints regardless of acceptance state.
        if (isWhitelisted(request)) {
            chain.doFilter(request, response);
            return;
        }

        AcceptanceStatus status = consentService.checkAcceptanceStatus(principal.getId());

        if (status.mustAcceptLegal()) {
            // LOCALE OUTPUT (Spanish): user-facing legal acceptance prompt
            writeForbidden(response, "LEGAL_ACCEPTANCE",
                    "Debes aceptar los términos y la política de privacidad para continuar.",
                    status);
            log.debug("LegalAcceptanceFilter blocked userId={} method={} path={} reason=LEGAL_ACCEPTANCE",
                    principal.getId(), request.getMethod(), request.getRequestURI());
            return;
        }

        if (status.mustAcceptDpa()) {
            // LOCALE OUTPUT (Spanish): user-facing DPA acceptance prompt
            writeForbidden(response, "DPA_ACCEPTANCE",
                    "Debes aceptar el DPA (Anexo de Encargado de Tratamiento) para continuar.",
                    status);
            log.debug("LegalAcceptanceFilter blocked userId={} method={} path={} reason=DPA_ACCEPTANCE",
                    principal.getId(), request.getMethod(), request.getRequestURI());
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isWhitelisted(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        for (String prefix : WHITELIST_PREFIXES) {
            if (uri.equals(prefix) || uri.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private void writeForbidden(HttpServletResponse response,
                                String requiresCode,
                                String localeMessage,
                                AcceptanceStatus status) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requires", requiresCode);
        data.put("mustAcceptLegal", status.mustAcceptLegal());
        data.put("mustAcceptDpa", status.mustAcceptDpa());

        ApiResponse<Map<String, Object>> body = ApiResponse.<Map<String, Object>>builder()
                .success(false)
                .message(localeMessage)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
