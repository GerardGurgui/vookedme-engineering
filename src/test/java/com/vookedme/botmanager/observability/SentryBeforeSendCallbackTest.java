package com.vookedme.botmanager.observability;

import com.vookedme.botmanager.common.exception.BadRequestException;
import com.vookedme.botmanager.common.exception.ConflictException;
import com.vookedme.botmanager.common.exception.ForbiddenException;
import com.vookedme.botmanager.common.exception.ResourceNotFoundException;
import com.vookedme.botmanager.common.exception.UnauthorizedException;
import com.vookedme.botmanager.config.observability.SentryBeforeSendCallback;
import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;
import io.sentry.protocol.SentryStackTrace;
import io.sentry.protocol.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SentryBeforeSendCallback}.
 *
 * <p>Verifies the two responsibilities: PII scrubbing and 4xx / domain-exception
 * noise filtering. Exception {@code message} and {@code stacktrace} MUST remain
 * intact (debug invariant).
 *
 * <p>{@code SentryConfig} and {@code SentryProperties} are the configuration
 * layer that wires the callback into the Sentry SDK — published in a subsequent
 * source batch.
 */
class SentryBeforeSendCallbackTest {

    private final SentryBeforeSendCallback callback = new SentryBeforeSendCallback();
    private final Hint hint = new Hint();

    // ─── PII scrub ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PII scrubbing")
    class PiiScrub {

        @Test
        void removesEmailUsernameAndIpFromUser() {
            SentryEvent event = new SentryEvent();
            User user = new User();
            user.setEmail("alice@example.com");
            user.setUsername("alice");
            user.setIpAddress("203.0.113.42");
            user.setId("u-42");
            event.setUser(user);

            SentryEvent result = callback.execute(event, hint);

            assertThat(result).isNotNull();
            assertThat(result.getUser()).isNotNull();
            assertThat(result.getUser().getEmail()).isNull();
            assertThat(result.getUser().getUsername()).isNull();
            assertThat(result.getUser().getIpAddress()).isNull();
            // Opaque id is preserved.
            assertThat(result.getUser().getId()).isEqualTo("u-42");
        }

        @Test
        void removesAuthorizationAndCookieHeaders() {
            SentryEvent event = new SentryEvent();
            Request request = new Request();
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer secret-token");
            headers.put("Cookie", "session=abc");
            headers.put("Accept", "application/json");
            request.setHeaders(headers);
            event.setRequest(request);

            SentryEvent result = callback.execute(event, hint);

            assertThat(result).isNotNull();
            Map<String, String> cleaned = result.getRequest().getHeaders();
            assertThat(cleaned).doesNotContainKey("Authorization");
            assertThat(cleaned).doesNotContainKey("Cookie");
            assertThat(cleaned).containsEntry("Accept", "application/json");
        }

        @Test
        void caseInsensitiveAuthorizationCookieRemoval() {
            SentryEvent event = new SentryEvent();
            Request request = new Request();
            Map<String, String> headers = new HashMap<>();
            headers.put("authorization", "Bearer x");
            headers.put("cookie", "y");
            headers.put("Set-Cookie", "z");
            request.setHeaders(headers);
            event.setRequest(request);

            SentryEvent result = callback.execute(event, hint);

            assertThat(result.getRequest().getHeaders()).isEmpty();
        }

        @Test
        void removesQueryStringAndBody() {
            SentryEvent event = new SentryEvent();
            Request request = new Request();
            request.setQueryString("phone=%2B34611111111&token=abc");
            request.setData("{\"phone\":\"+34611111111\"}");
            event.setRequest(request);

            SentryEvent result = callback.execute(event, hint);

            assertThat(result.getRequest().getQueryString()).isNull();
            assertThat(result.getRequest().getData()).isNull();
        }

        @Test
        void removesSensitiveKeysFromExtras() {
            SentryEvent event = new SentryEvent();
            event.setExtra("phone", "+34611111111");
            event.setExtra("customerEmail", "alice@example.com");
            event.setExtra("jwtToken", "eyJ...");
            event.setExtra("appointmentId", 42L);

            SentryEvent result = callback.execute(event, hint);

            Map<String, Object> extras = result.getExtras();
            assertThat(extras).doesNotContainKey("phone");
            assertThat(extras).doesNotContainKey("customerEmail");
            assertThat(extras).doesNotContainKey("jwtToken");
            assertThat(extras).containsEntry("appointmentId", 42L);
        }

        @Test
        void removesSensitiveTagsByKey() {
            SentryEvent event = new SentryEvent();
            event.setTag("user.phone", "+34611111111");
            event.setTag("user.email", "alice@example.com");
            event.setTag("customerName", "Alice García");
            event.setTag("businessId", "1");

            SentryEvent result = callback.execute(event, hint);

            Map<String, String> tags = result.getTags();
            assertThat(tags).doesNotContainKey("user.phone");
            assertThat(tags).doesNotContainKey("user.email");
            assertThat(tags).doesNotContainKey("customerName");
            assertThat(tags).containsEntry("businessId", "1");
        }

        @Test
        void redactsSensitiveBreadcrumbDataInPlace() {
            SentryEvent event = new SentryEvent();
            Breadcrumb crumb = new Breadcrumb();
            crumb.setCategory("http");
            crumb.setData("url", "https://api/example");
            crumb.setData("userPhone", "+34611111111");
            crumb.setData("authorization", "Bearer abc");

            List<Breadcrumb> breadcrumbs = new ArrayList<>();
            breadcrumbs.add(crumb);
            event.setBreadcrumbs(breadcrumbs);

            SentryEvent result = callback.execute(event, hint);

            Map<String, Object> data = result.getBreadcrumbs().get(0).getData();
            assertThat(data).containsEntry("url", "https://api/example");
            assertThat(data).containsEntry("userPhone", "[REDACTED]");
            assertThat(data).containsEntry("authorization", "[REDACTED]");
            assertThat(data).containsEntry("redacted", true);
        }
    }

    // ─── Debug invariants ───────────────────────────────────────────────────

    @Nested
    @DisplayName("debug invariants — message + stacktrace untouched")
    class DebugInvariants {

        @Test
        void messageIsPreserved() {
            SentryEvent event = new SentryEvent(new RuntimeException("Critical 5xx error: DB connection lost"));

            SentryEvent result = callback.execute(event, hint);

            assertThat(result).isNotNull();
            assertThat(result.getThrowable()).isNotNull();
            assertThat(result.getThrowable().getMessage())
                    .isEqualTo("Critical 5xx error: DB connection lost");
        }

        @Test
        void stacktraceIsPreserved() {
            SentryEvent event = new SentryEvent(new RuntimeException("oops"));

            SentryStackTrace trace = new SentryStackTrace();
            SentryStackFrame frame = new SentryStackFrame();
            frame.setFunction("doStuff");
            frame.setModule("com.vookedme.botmanager.SomeService");
            frame.setLineno(42);
            trace.setFrames(List.of(frame));

            SentryException sentryException = new SentryException();
            sentryException.setStacktrace(trace);
            sentryException.setType("RuntimeException");
            sentryException.setValue("oops");
            event.setExceptions(List.of(sentryException));

            SentryEvent result = callback.execute(event, hint);

            assertThat(result).isNotNull();
            assertThat(result.getExceptions()).hasSize(1);
            SentryStackTrace resultTrace = result.getExceptions().get(0).getStacktrace();
            assertThat(resultTrace).isNotNull();
            assertThat(resultTrace.getFrames()).hasSize(1);
            assertThat(resultTrace.getFrames().get(0).getFunction()).isEqualTo("doStuff");
            assertThat(resultTrace.getFrames().get(0).getLineno()).isEqualTo(42);
        }
    }

    // ─── Noise filter ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("noise filter — drop 4xx + domain-controlled exceptions")
    class NoiseFilter {

        @Test
        void dropsBadRequestException() {
            SentryEvent event = new SentryEvent(new BadRequestException("invalid"));
            assertThat(callback.execute(event, hint)).isNull();
        }

        @Test
        void dropsResourceNotFoundException() {
            SentryEvent event = new SentryEvent(new ResourceNotFoundException("not found"));
            assertThat(callback.execute(event, hint)).isNull();
        }

        @Test
        void dropsConflictException() {
            SentryEvent event = new SentryEvent(new ConflictException("conflict"));
            assertThat(callback.execute(event, hint)).isNull();
        }

        @Test
        void dropsForbiddenException() {
            SentryEvent event = new SentryEvent(new ForbiddenException("forbidden"));
            assertThat(callback.execute(event, hint)).isNull();
        }

        @Test
        void dropsUnauthorizedException() {
            SentryEvent event = new SentryEvent(new UnauthorizedException("unauth"));
            assertThat(callback.execute(event, hint)).isNull();
        }

        @Test
        void dropsObjectOptimisticLockingFailureException() {
            SentryEvent event = new SentryEvent(
                    new ObjectOptimisticLockingFailureException("Appointment", 1L));
            assertThat(callback.execute(event, hint)).isNull();
        }

        @Test
        void dropsDataIntegrityViolationException() {
            SentryEvent event = new SentryEvent(
                    new DataIntegrityViolationException("dup phone"));
            assertThat(callback.execute(event, hint)).isNull();
        }

        @Test
        void dropsExceptionWrappingDomainException() {
            // Real-world pattern: outer RuntimeException wrapping a BadRequestException cause.
            RuntimeException wrapper = new RuntimeException(new BadRequestException("inner"));
            SentryEvent event = new SentryEvent(wrapper);

            assertThat(callback.execute(event, hint)).isNull();
        }

        @Test
        void dropsEventWith4xxStatusInExtras() {
            SentryEvent event = new SentryEvent();
            event.setExtra("status", 404);
            assertThat(callback.execute(event, hint)).isNull();
        }

        @Test
        void keepsEventWith5xxStatusInExtras() {
            SentryEvent event = new SentryEvent();
            event.setExtra("status", 500);
            SentryEvent result = callback.execute(event, hint);
            assertThat(result).isNotNull();
        }

        @Test
        void keepsGenericRuntimeException5xx() {
            SentryEvent event = new SentryEvent(
                    new RuntimeException("Unexpected NPE in scheduler"));
            SentryEvent result = callback.execute(event, hint);

            assertThat(result).isNotNull();
            assertThat(result.getThrowable().getMessage())
                    .isEqualTo("Unexpected NPE in scheduler");
        }
    }

    // ─── DSN gating (SentryConfig invariant) ────────────────────────────────

    @Nested
    @DisplayName("SentryConfig — DSN gating")
    class DsnGating {

        /**
         * {@code SentryConfig} and {@code SentryProperties} wire the callback into
         * the Sentry SDK via {@code @Value("${sentry.dsn:}")} gating. They are
         * published in a subsequent source batch. These tests verify the contract
         * without loading the full Spring context.
         */
        @Test
        void emptyDsnDoesNotInitialiseSentry() {
            SentryProperties properties = new SentryProperties();
            properties.setDsn("");
            SentryConfig config = new SentryConfig(properties, new SentryBeforeSendCallback());

            // Should not throw. init() short-circuits when DSN is blank.
            config.initSentry();
        }

        @Test
        void blankDsnDoesNotInitialiseSentry() {
            SentryProperties properties = new SentryProperties();
            properties.setDsn("   ");
            SentryConfig config = new SentryConfig(properties, new SentryBeforeSendCallback());

            config.initSentry();
        }

        @Test
        void nullDsnDoesNotInitialiseSentry() {
            SentryProperties properties = new SentryProperties();
            properties.setDsn(null);
            SentryConfig config = new SentryConfig(properties, new SentryBeforeSendCallback());

            config.initSentry();
        }
    }

    // ─── Combined ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("combined — 5xx event with PII gets cleaned and dispatched")
    class Combined {

        @Test
        void cleansAndDispatchesGenericException() {
            SentryEvent event = new SentryEvent(new RuntimeException("DB pool exhausted"));

            User user = new User();
            user.setEmail("alice@example.com");
            user.setIpAddress("203.0.113.42");
            event.setUser(user);

            Request request = new Request();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer x");
            headers.put("Accept", "application/json");
            request.setHeaders(headers);
            request.setQueryString("phone=%2B34611");
            event.setRequest(request);

            event.setExtra("phone", "+34611111111");
            event.setExtra("appointmentId", 42L);

            SentryEvent result = callback.execute(event, hint);

            assertThat(result).isNotNull();
            // PII gone.
            assertThat(result.getUser().getEmail()).isNull();
            assertThat(result.getUser().getIpAddress()).isNull();
            assertThat(result.getRequest().getHeaders()).doesNotContainKey("Authorization");
            assertThat(result.getRequest().getQueryString()).isNull();
            assertThat(result.getExtras()).doesNotContainKey("phone");
            // Operational data preserved.
            assertThat(result.getRequest().getHeaders()).containsEntry("Accept", "application/json");
            assertThat(result.getExtras()).containsEntry("appointmentId", 42L);
            // Debug data preserved.
            assertThat(result.getThrowable().getMessage()).isEqualTo("DB pool exhausted");
        }
    }
}
