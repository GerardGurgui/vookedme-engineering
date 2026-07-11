package com.vookedme.botmanager.config.observability;

import com.vookedme.botmanager.common.exception.BadRequestException;
import com.vookedme.botmanager.common.exception.ConflictException;
import com.vookedme.botmanager.common.exception.ForbiddenException;
import com.vookedme.botmanager.common.exception.ResourceNotFoundException;
import com.vookedme.botmanager.common.exception.UnauthorizedException;
import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Request;
import io.sentry.protocol.User;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Sentry {@link SentryOptions.BeforeSendCallback} — PII scrubbing and noise
 * filtering.
 *
 * <p>Two responsibilities:
 *
 * <ol>
 *   <li><b>GDPR scrub</b> — removes residual PII from every event before it
 *       leaves the JVM. Cleans request body, {@code Authorization} and
 *       {@code Cookie} headers, query string, user email/username/IP, plus
 *       any tags or extras whose key contains {@code phone}, {@code email},
 *       {@code name}, {@code token}, {@code jwt}, {@code password},
 *       {@code secret}, {@code authorization}, or {@code cookie}. The same
 *       scan applies to breadcrumb data, where sensitive values are replaced
 *       with {@code [REDACTED]} rather than removed, so breadcrumbs retain
 *       their operational meaning (HTTP route, etc.).</li>
 *
 *   <li><b>Noise filter</b> — drops expected 4xx and domain-controlled
 *       exceptions ({@link BadRequestException}, {@link ResourceNotFoundException},
 *       {@link ConflictException}, {@link ForbiddenException},
 *       {@link UnauthorizedException}, {@link MethodArgumentNotValidException},
 *       {@link DataIntegrityViolationException},
 *       {@link ObjectOptimisticLockingFailureException}) so the Sentry quota
 *       only carries actionable 5xx and unexpected errors.</li>
 * </ol>
 *
 * <p>Never modifies the exception {@code message} or {@code stacktrace} —
 * both are needed for debugging and contain no PII by convention. Never
 * returns {@code null} to drop an event silently except for noise filtering;
 * for all other events, the cleaned event is always returned.
 *
 * <p>{@link BadRequestException}, {@link ConflictException},
 * {@link ForbiddenException}, and {@link ResourceNotFoundException} are
 * published in a subsequent source batch (SC-6). {@link UnauthorizedException}
 * is published in a subsequent source batch.
 */
@Component
public class SentryBeforeSendCallback implements SentryOptions.BeforeSendCallback {

    private static final Set<String> SENSITIVE_KEY_FRAGMENTS = Set.of(
            "phone", "email", "name", "token", "jwt", "password", "secret", "authorization", "cookie"
    );

    private static final Set<Class<? extends Throwable>> DROPPED_EXCEPTIONS = Set.of(
            BadRequestException.class,
            ResourceNotFoundException.class,
            ConflictException.class,
            ForbiddenException.class,
            UnauthorizedException.class,
            MethodArgumentNotValidException.class,
            DataIntegrityViolationException.class,
            ObjectOptimisticLockingFailureException.class
    );

    @Override
    public @Nullable SentryEvent execute(@NotNull SentryEvent event, @NotNull Hint hint) {
        // ── Noise filter — drop expected 4xx and domain-controlled exceptions ──
        if (shouldDropEvent(event)) {
            return null;
        }

        // ── PII scrub — never return null for cleaning, only for noise ──
        scrubRequest(event.getRequest());
        scrubUser(event.getUser());
        scrubMap(event.getTags());
        scrubMap(event.getExtras());
        scrubBreadcrumbs(event);

        return event;
    }

    /**
     * Drops the event if its primary throwable is a known 4xx or
     * domain-controlled exception, OR if its extras explicitly carry a
     * 4xx HTTP status.
     */
    private boolean shouldDropEvent(SentryEvent event) {
        Throwable t = event.getThrowable();
        if (t != null && isDroppableException(t)) {
            return true;
        }

        // Status hint via extras (e.g. "status": 404 set by an interceptor).
        Map<String, Object> extras = event.getExtras();
        if (extras != null) {
            Object status = extras.get("status");
            if (status instanceof Number n) {
                int s = n.intValue();
                if (s >= 400 && s < 500) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isDroppableException(Throwable t) {
        Throwable current = t;
        while (current != null) {
            for (Class<? extends Throwable> dropped : DROPPED_EXCEPTIONS) {
                if (dropped.isInstance(current)) {
                    return true;
                }
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    private void scrubRequest(@Nullable Request request) {
        if (request == null) return;

        // Body and query string may contain raw user payload.
        request.setData(null);
        request.setQueryString(null);
        request.setCookies(null);

        // Headers — strip Authorization, Cookie, and Set-Cookie (case-insensitive).
        Map<String, String> headers = request.getHeaders();
        if (headers != null) {
            // Copy keys before mutating to avoid ConcurrentModificationException.
            for (String key : headers.keySet().toArray(new String[0])) {
                String lower = key.toLowerCase();
                if (lower.equals("authorization") || lower.equals("cookie") || lower.equals("set-cookie")) {
                    headers.remove(key);
                }
            }
        }
    }

    private void scrubUser(@Nullable User user) {
        if (user == null) return;
        user.setEmail(null);
        user.setUsername(null);
        user.setIpAddress(null);
        // user.id and segment may stay — they are opaque identifiers, not PII per se.
    }

    private void scrubMap(@Nullable Map<String, ?> map) {
        if (map == null || map.isEmpty()) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> mutable = (Map<String, Object>) map;
        for (String key : mutable.keySet().toArray(new String[0])) {
            if (isSensitiveKey(key)) {
                mutable.remove(key);
            }
        }
    }

    private void scrubBreadcrumbs(SentryEvent event) {
        if (event.getBreadcrumbs() == null) return;
        for (Breadcrumb crumb : event.getBreadcrumbs()) {
            Map<String, Object> data = crumb.getData();
            if (data == null || data.isEmpty()) continue;
            // Replace sensitive values with [REDACTED] rather than removing the key,
            // so the breadcrumb retains its operational meaning (HTTP route, etc.).
            Map<String, Object> redacted = new HashMap<>(data);
            for (String key : data.keySet()) {
                if (isSensitiveKey(key)) {
                    redacted.put(key, "[REDACTED]");
                }
            }
            // Breadcrumb data is often immutable in the Sentry SDK; setData replaces it.
            crumb.setData("redacted", true);
            for (Map.Entry<String, Object> entry : redacted.entrySet()) {
                crumb.setData(entry.getKey(), entry.getValue());
            }
        }
    }

    private boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        for (String fragment : SENSITIVE_KEY_FRAGMENTS) {
            if (lower.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
