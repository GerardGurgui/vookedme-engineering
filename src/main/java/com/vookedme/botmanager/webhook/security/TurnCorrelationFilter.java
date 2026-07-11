package com.vookedme.botmanager.webhook.security;

import com.vookedme.botmanager.config.observability.TurnContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Binds a conversational turn identifier to the request thread for webhook requests (ADR-012).
 *
 * <p>On {@code /api/webhook/**} paths, reads the {@code X-Turn-Id} header emitted by the
 * n8n orchestrator — one header per conversational turn — and binds it to the current
 * thread via {@link TurnContext} (MDC) for the full duration of the request:
 *
 * <ul>
 *   <li><b>Valid UUID header</b> ({@link TurnContext.Source#HEADER}) — propagated to log
 *       output via {@code %X{turnId}} pattern and persisted to {@code audit_log.turn_id}
 *       for any appointment mutations that occur during this request.</li>
 *   <li><b>Absent or non-UUID header</b> ({@link TurnContext.Source#SYNTHETIC}) — a
 *       synthetic UUID is generated for intra-request log correlation only;
 *       {@code audit_log.turn_id} remains NULL. This is the correct degraded behaviour
 *       for requests that do not carry the header — the filter provides log correlation
 *       value independently of header availability.</li>
 * </ul>
 *
 * <p><b>Position in the filter chain:</b> after {@link WebhookApiKeyFilter} (only requests
 * with a valid API key reach this filter) and before {@link WebhookSignatureFilter}. Uses
 * MDC rather than {@code @RequestScope} to avoid dependency on
 * {@code RequestContextHolder} binding during the filter phase — see {@link TurnContext}
 * for the detailed rationale. The {@code MDC.remove} call in {@code finally} prevents
 * MDC leaks from servlet thread-pool reuse.
 */
@Component
@Slf4j
public class TurnCorrelationFilter extends OncePerRequestFilter {

    private static final String TURN_ID_HEADER = "X-Turn-Id";
    private static final String WEBHOOK_PATH_PREFIX = "/api/webhook";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        if (path == null || !path.startsWith(WEBHOOK_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        UUID parsed = parseUuid(request.getHeader(TURN_ID_HEADER));
        UUID turnId;
        TurnContext.Source source;
        if (parsed != null) {
            turnId = parsed;
            source = TurnContext.Source.HEADER;
        } else {
            // No valid header: synthetic UUID for log correlation only; audit_log.turn_id will remain NULL.
            turnId = UUID.randomUUID();
            source = TurnContext.Source.SYNTHETIC;
        }

        try {
            TurnContext.bind(turnId, source);
            filterChain.doFilter(request, response);
        } finally {
            // Critical: clear MDC unconditionally to prevent contamination of the next
            // request that reuses this servlet thread-pool thread.
            TurnContext.clear();
        }
    }

    /**
     * Parses the header value as a UUID; returns {@code null} if absent, blank, or
     * malformed, causing the filter to fall back to a synthetic identifier.
     */
    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
