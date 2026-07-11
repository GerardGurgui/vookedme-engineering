package com.vookedme.botmanager.customer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architectural wiring guard for the auto-legitimation model — deterministic and
 * Docker-free. Fails if a regression introduces the legitimation call inline in the
 * booking transaction (which would cause an optimistic lock conflict to abort the
 * booking), removes the async handling required to give the writer its own transaction
 * boundary, or drops the background failure handler.
 *
 * <p>Reads real source files (not bytecode) to assert on the <em>shape</em> of the
 * wiring code, stripping comments so that documentation references to prohibited
 * patterns do not produce false positives.
 *
 * <p>{@code CustomerService} and {@code CustomerInboundLegitimationListener} — the two
 * wiring classes exercised by this guard — are published in a subsequent source batch.
 * Their paths and method signatures are recorded here as the wiring specification.
 */
class LegitimationWiringGuardTest {

    /**
     * Reads source and strips comments (block/Javadoc {@code /* *}{@code /} and line {@code //}) —
     * guards assert on the shape of the code, not the documentation (which legitimately
     * mentions the patterns being guarded against, to explain why they are absent).
     */
    private static String read(String relativePath) throws Exception {
        String src = Files.readString(Path.of(relativePath));
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("//[^\\n]*", "");
    }

    @Test
    void existingCustomerLegit_isDeferredToAfterCommitEvent() throws Exception {
        String svc = read(
                "src/main/java/com/vookedme/botmanager/customer/service/CustomerService.java");
        assertThat(svc)
                .as("the EXISTING-customer branch must publish the deferred event, not call legitimation inline")
                .contains("publishEvent(CustomerInboundLegitimationEvent");
    }

    @Test
    void newCustomerLegit_isInline() throws Exception {
        String svc = read(
                "src/main/java/com/vookedme/botmanager/customer/service/CustomerService.java");
        assertThat(svc)
                .as("the NEW-customer branch must call legitimateFromBot inline after save()")
                .contains("customerLegitimacyService.legitimateFromBot(saved.getId())");
    }

    @Test
    void customerService_callsLegitimateFromBot_exactlyOnce() throws Exception {
        String svc = read(
                "src/main/java/com/vookedme/botmanager/customer/service/CustomerService.java");
        int count = svc.split("legitimateFromBot\\(", -1).length - 1;
        assertThat(count)
                .as("only the NEW-customer branch calls legitimateFromBot inline; "
                        + "the EXISTING branch defers via AFTER_COMMIT event to avoid optimistic lock "
                        + "conflict aborting the booking. A second inline call would indicate a regression "
                        + "to the rejected model.")
                .isEqualTo(1);
    }

    @Test
    void listener_isAfterCommit_async_withBackgroundFailureHandling() throws Exception {
        String lst = read(
                "src/main/java/com/vookedme/botmanager/customer/legitimation/CustomerInboundLegitimationListener.java");
        assertThat(lst).contains("@TransactionalEventListener");
        assertThat(lst).contains("AFTER_COMMIT");
        assertThat(lst).contains("legitimateFromBot");
        // The synchronous AFTER_COMMIT path cannot commit its own transaction (verified by
        // integration test); a fresh thread via @Async is required so the writer's @Transactional
        // boundary applies per-attempt and the @Version retry functions correctly.
        assertThat(lst)
                .as("the listener must be @Async (fresh thread) so the writer's transaction and @Version retry work")
                .contains("@Async");
        assertThat(lst)
                .as("background failure handling is required: a lost legitimation must be surfaced, not silently dropped")
                .contains("reportBackgroundFailure");
    }
}
