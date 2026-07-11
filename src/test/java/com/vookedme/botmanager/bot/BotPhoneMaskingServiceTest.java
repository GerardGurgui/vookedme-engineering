package com.vookedme.botmanager.bot;

import com.vookedme.botmanager.bot.service.BotPhoneMaskingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BotPhoneMaskingService}, verifying:
 *
 * <ol>
 *   <li>Spain (+34) — primary operating market.</li>
 *   <li>North America (+1), UK (+44), France (+33), Germany (+49) — known
 *       prefixes with explicit masking rules.</li>
 *   <li>Generic 2-digit fallback — for all other country codes.</li>
 *   <li>Rejection paths — null, blank, missing {@code "+"}, too-short
 *       digit strings.</li>
 *   <li>Purity and API contract — class shape, determinism, no Spring wiring.</li>
 * </ol>
 *
 * <p>All phone numbers used as test fixtures are synthetic test values.
 * They do not identify any real customer.
 *
 * <p>The masking algorithm is a one-way transformation: the same raw input
 * always produces the same masked output (deterministic and stateless).
 * Because the service has no I/O and no side effects, all paths can be tested
 * exhaustively without a database or application context.
 */
@DisplayName("BotPhoneMaskingService — GDPR masking for list-view display")
class BotPhoneMaskingServiceTest {

    // ════════════════════════════════════════════════════════════════════════
    // 1. Spain (+34) — primary operating market
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Spain (+34)")
    class Spain {

        @Test
        @DisplayName("+34 mobile (9 digits after CC) → +34 *** *** <last 3>")
        void spanishMobile() {
            assertThat(BotPhoneMaskingService.mask("+34600111222"))
                    .isEqualTo("+34 *** *** 222");
        }

        @Test
        @DisplayName("+34 with leading whitespace is normalised")
        void spanishMobileLeadingSpace() {
            assertThat(BotPhoneMaskingService.mask("  +34600111333"))
                    .isEqualTo("+34 *** *** 333");
        }

        @Test
        @DisplayName("+34 with internal dashes and spaces is normalised")
        void spanishMobileInternalSpaces() {
            assertThat(BotPhoneMaskingService.mask("+34 600 111 444"))
                    .isEqualTo("+34 *** *** 444");
        }

        @Test
        @DisplayName("Last 3 digits preserved verbatim")
        void lastThreeDigitsPreserved() {
            assertThat(BotPhoneMaskingService.mask("+34612345678"))
                    .isEqualTo("+34 *** *** 678");
        }

        @Test
        @DisplayName("Synthetic test number used in integration tests is masked correctly")
        void syntheticTestNumber() {
            // "+34600999888" is the synthetic constant used in BotRecentRelevantReadIT
            assertThat(BotPhoneMaskingService.mask("+34600999888"))
                    .isEqualTo("+34 *** *** 888");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. Known international prefixes
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Known international prefixes")
    class KnownPrefixes {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                "+12125557890,  +1 *** *** 890",   // US (11 digits with country code)
                "+12125557,     +1 *** *** 557",   // US (10 digits: 1 + 9)
                "+447700900123, +44 *** *** 123",  // UK
                "+33612345678,  +33 *** *** 678",  // France
                "+4915123456789,+49 *** *** 789",  // Germany
        })
        void knownPrefixMasked(String input, String expected) {
            assertThat(BotPhoneMaskingService.mask(input.strip()))
                    .isEqualTo(expected.strip());
        }

        @Test
        @DisplayName("+1 with exactly 10 subscriber digits (NANP standard) → masking applies")
        void nanpTenDigits() {
            // NANP: +1 + 10 digits = 11 total digit chars
            assertThat(BotPhoneMaskingService.mask("+12345678901"))
                    .isEqualTo("+1 *** *** 901");
        }

        @Test
        @DisplayName("+1 with 9 digits (non-standard) falls to generic 2-digit fallback, not +1 rule")
        void nanpNineDigits_fallsToGeneric() {
            // The +1 explicit branch requires 10 or 11 digit length.
            // A 9-digit body string after CC-digit yields total 10 — but NANP
            // branch checks digits.length() == 10 || 11, so "1" + 9 digits = 10 total → qualifies.
            // "1" + 8 digits = 9 total → generic fallback "1X".
            assertThat(BotPhoneMaskingService.mask("+112345678"))
                    .isEqualTo("+11 *** *** 678");  // generic 2-digit fallback: "11"
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. Generic 2-digit fallback
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Generic 2-digit fallback — unrecognised country code")
    class GenericFallback {

        @Test
        @DisplayName("Italian number (+39) uses generic fallback: +39 *** *** <last 3>")
        void italianNumber() {
            assertThat(BotPhoneMaskingService.mask("+39312345678"))
                    .isEqualTo("+39 *** *** 678");
        }

        @Test
        @DisplayName("Brazilian number (+55) uses generic fallback")
        void brazilianNumber() {
            assertThat(BotPhoneMaskingService.mask("+5511987654321"))
                    .isEqualTo("+55 *** *** 321");
        }

        @Test
        @DisplayName("Generic fallback always takes the first 2 raw digit chars as country code")
        void genericFallbackFirstTwoDigits() {
            assertThat(BotPhoneMaskingService.mask("+81901234567"))
                    .isEqualTo("+81 *** *** 567");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. Rejection paths — invalid input returns null
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Rejection paths — invalid input returns null")
    class RejectionPaths {

        @Test
        @DisplayName("null input → null")
        void nullInput() {
            assertThat(BotPhoneMaskingService.mask(null)).isNull();
        }

        @ParameterizedTest(name = "blank [{0}] → null")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        void blankInput_returnsNull(String input) {
            assertThat(BotPhoneMaskingService.mask(input)).isNull();
        }

        @Test
        @DisplayName("No '+' prefix (non-E.164) → null")
        void missingPlusPrefix() {
            assertThat(BotPhoneMaskingService.mask("34600111222")).isNull();
        }

        @Test
        @DisplayName("Too short after stripping non-digits (< 5 digits) → null")
        void tooShort() {
            assertThat(BotPhoneMaskingService.mask("+3412")).isNull();
        }

        @Test
        @DisplayName("Only '+' and non-digit characters → null")
        void onlyNonDigits() {
            assertThat(BotPhoneMaskingService.mask("+ABC")).isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5. Purity and API contract
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Purity and API contract — class shape")
    class PurityAndContract {

        @Test
        @DisplayName("Class is final — cannot be subclassed")
        void classIsFinal() {
            assertThat(Modifier.isFinal(BotPhoneMaskingService.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("No Spring stereotype annotations")
        void noSpringAnnotations() {
            for (Annotation a : BotPhoneMaskingService.class.getAnnotations()) {
                String name = a.annotationType().getName();
                assertThat(name)
                        .doesNotStartWith("org.springframework.stereotype")
                        .doesNotEndWith(".Service")
                        .doesNotEndWith(".Component");
            }
        }

        @Test
        @DisplayName("No instance fields — only static final constants")
        void noInstanceFields() {
            for (Field f : BotPhoneMaskingService.class.getDeclaredFields()) {
                if (f.isSynthetic()) continue;
                int mods = f.getModifiers();
                assertThat(Modifier.isStatic(mods))
                        .as("Field %s must be static", f.getName())
                        .isTrue();
                assertThat(Modifier.isFinal(mods))
                        .as("Static field %s must be final", f.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("All public methods are static — no instance required")
        void allPublicMethodsStatic() {
            List<Method> publicMethods = Arrays.stream(BotPhoneMaskingService.class.getDeclaredMethods())
                    .filter(m -> Modifier.isPublic(m.getModifiers()))
                    .toList();
            assertThat(publicMethods).isNotEmpty();
            for (Method m : publicMethods) {
                assertThat(Modifier.isStatic(m.getModifiers()))
                        .as("Public method %s must be static", m.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("Private constructor prevents instantiation")
        void privateConstructor_throws() throws Exception {
            var ctor = BotPhoneMaskingService.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            assertThatThrownBy(ctor::newInstance)
                    .hasCauseInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Deterministic: same input → same output across calls")
        void deterministic() {
            String first = BotPhoneMaskingService.mask("+34600111222");
            String second = BotPhoneMaskingService.mask("+34600111222");
            String third = BotPhoneMaskingService.mask("+34600111222");
            assertThat(first).isEqualTo(second).isEqualTo(third);
        }
    }
}
