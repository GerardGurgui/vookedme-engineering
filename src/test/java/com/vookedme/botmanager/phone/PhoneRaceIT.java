package com.vookedme.botmanager.phone;

import com.vookedme.botmanager.auth.repository.ConsentAuditRepository;
import com.vookedme.botmanager.auth.repository.UserRepository;
import com.vookedme.botmanager.auth.service.AuthService;
import com.vookedme.botmanager.business.repository.BusinessRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Race condition test: concurrent phone registration with a shared unique
 * constraint.
 *
 * <p>Verifies that when two threads call {@code registerBusiness} concurrently
 * with the same phone number, exactly one succeeds and the other receives a
 * conflict (from the {@code uk_users_phone_global} UNIQUE index on the
 * {@code users} table). {@code @Version}-based optimistic locking does not
 * protect against concurrent INSERTs — only a database-level UNIQUE constraint
 * can act as the authoritative arbiter for this race.
 *
 * <p>Requires Docker (Testcontainers PostgreSQL). Repeated 5 times to detect
 * flakiness: race outcomes vary by scheduling, so a single run may not reliably
 * expose a missing constraint.
 *
 * <p>Unpublished dependencies: {@code AuthService} (the registration path)
 * — published in a subsequent source batch.
 */
@SpringBootTest
@ActiveProfiles("test")
class PhoneRaceIT {

    @SuppressWarnings({"resource", "rawtypes"})
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private BusinessRepository businessRepository;
    @Autowired private ConsentAuditRepository consentAuditRepository;
    @Autowired private JdbcTemplate jdbc;

    private static final String SHARED_PHONE = "+34699888777";

    @AfterEach
    void cleanup() {
        // TRUNCATE CASCADE clears consent_audit, refresh_tokens, users, and businesses
        // between repetitions — but preserves the Flyway migration history and any
        // seed data that the migrations insert and the tests do not depend on.
        jdbc.execute("TRUNCATE TABLE consent_audit, refresh_tokens, users, businesses RESTART IDENTITY CASCADE");
    }

    @RepeatedTest(5)
    void concurrentRegisterBusinessWithSamePhoneAllowsExactlyOne() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successes = new AtomicInteger(0);
            AtomicInteger failures = new AtomicInteger(0);

            Callable<Void> register = () -> {
                String tName = Thread.currentThread().getName();
                String safe = tName.replaceAll("[^a-zA-Z0-9]", "");
                HttpServletRequest req = new MockHttpServletRequest();
                start.await();
                try {
                    authService.registerBusiness(
                            "owner-" + safe + "@test.com",
                            "ValidP4ss!",
                            "Owner",
                            null,
                            SHARED_PHONE,
                            "Business " + safe,
                            req);
                    successes.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
                return null;
            };

            Future<Void> f1 = pool.submit(register);
            Future<Void> f2 = pool.submit(register);
            start.countDown(); // Starting gun: both threads enter registerBusiness simultaneously.
            f1.get(15, TimeUnit.SECONDS);
            f2.get(15, TimeUnit.SECONDS);

            // Invariant: exactly one registration succeeds; the other encounters
            // a DataIntegrityViolationException from the UNIQUE constraint.
            assertThat(successes.get())
                    .as("Exactly 1 success — the other must see a conflict or data integrity failure")
                    .isEqualTo(1);
            assertThat(failures.get())
                    .as("Exactly 1 failure")
                    .isEqualTo(1);

            long userCount = userRepository.findAll().stream()
                    .filter(u -> SHARED_PHONE.equals(u.getPhone())).count();
            assertThat(userCount)
                    .as("Exactly 1 user with the shared phone number")
                    .isEqualTo(1);

            assertThat(businessRepository.count())
                    .as("Only 1 business — the loser's transaction was rolled back")
                    .isEqualTo(1);

            assertThat(consentAuditRepository.count())
                    .as("2 consent audit rows (terms + privacy) from the winner; 0 from the loser")
                    .isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }
}
