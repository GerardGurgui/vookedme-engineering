package com.vookedme.botmanager.auth;

import com.vookedme.botmanager.auth.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for the runtime enforcement of the consent gate architecture
 * (backend filter chain, 2026-05-27).
 *
 * <pre>
 *   EMPLOYEE acceptance ≠ DPA acceptance.
 *   EMPLOYEE accepts platform-use T&amp;C + own-account privacy policy.
 *   ONLY OWNER role accepts the DPA (Data Processing Agreement),
 *   business contractual terms, processor agreement, AI processing clauses.
 * </pre>
 *
 * <p>Petrifies the joint enforcement: {@link com.vookedme.botmanager.auth.security.LegalAcceptanceFilter}
 * actually blocks non-whitelisted requests when the user has pending TOS/Privacy
 * or pending DPA acceptance, AND the whitelist correctly allows the user to reach
 * the "fix the problem" endpoints.
 *
 * <p>Before the filter was introduced, both gates were enforced only client-side.
 * This IT closes the regression vector: if a future change disables the filter
 * or incorrectly widens its whitelist, this test fails.
 *
 * <p>{@code BaseIntegrationTest} provides shared integration test fixtures:
 * {@code mockMvc}, {@code testBusiness}, {@code ownerUser}, {@code employeeUser},
 * {@code adminUser}, {@code businessRepository}, {@code userRepository}, and
 * the {@code tokenFor(User)} helper. It is published in a subsequent source batch.
 */
class BotConsentEnforcementIT extends BaseIntegrationTest {

    /**
     * Clears the test business's DPA pre-stamp added by
     * {@code BaseIntegrationTest.setUpBase()} for scenarios that need to
     * exercise the un-accepted state.
     */
    private void clearTestBusinessDpa() {
        testBusiness.setDpaAcceptedAt(null);
        testBusiness.setDpaAcceptedByUserId(null);
        testBusiness.setDpaSignedVersion(null);
        testBusiness = businessRepository.save(testBusiness);
    }

    private void setUserMustAcceptLegal(User user) {
        user.setMustAcceptLegal(true);
        userRepository.save(user);
    }

    // ────────────────────────────────────────────────────────────────
    // DPA gate — fires only for OWNER role
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OWNER without DPA → 403 with requires=DPA_ACCEPTANCE on non-whitelisted endpoint")
    void ownerNoDpaBlockedOnApiEndpoint() throws Exception {
        clearTestBusinessDpa();

        mockMvc.perform(get("/api/businesses/" + testBusiness.getId())
                        .header("Authorization", "Bearer " + tokenFor(ownerUser)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.requires").value("DPA_ACCEPTANCE"))
                .andExpect(jsonPath("$.data.mustAcceptLegal").value(false))
                .andExpect(jsonPath("$.data.mustAcceptDpa").value(true));
    }

    @Test
    @DisplayName("OWNER without DPA → 200 on whitelisted /auth/me (reachable to fix state)")
    void ownerNoDpaCanReachAuthMe() throws Exception {
        clearTestBusinessDpa();

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + tokenFor(ownerUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustAcceptDpa").value(true))
                .andExpect(jsonPath("$.data.mustAcceptLegal").value(false));
    }

    @Test
    @DisplayName("OWNER without DPA → 200 on whitelisted /auth/accept-dpa (the fix endpoint)")
    void ownerNoDpaCanReachAcceptDpa() throws Exception {
        clearTestBusinessDpa();

        String body = "{\"dpaAccepted\":true}";
        mockMvc.perform(post("/auth/accept-dpa")
                        .header("Authorization", "Bearer " + tokenFor(ownerUser))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustAcceptDpa").value(false));
    }

    @Test
    @DisplayName("OWNER after accept-dpa → next non-whitelist request passes 200")
    void ownerAfterAcceptDpaUnblocked() throws Exception {
        clearTestBusinessDpa();

        mockMvc.perform(post("/auth/accept-dpa")
                        .header("Authorization", "Bearer " + tokenFor(ownerUser))
                        .contentType("application/json")
                        .content("{\"dpaAccepted\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/businesses/" + testBusiness.getId())
                        .header("Authorization", "Bearer " + tokenFor(ownerUser)))
                .andExpect(status().isOk());
    }

    // ────────────────────────────────────────────────────────────────
    // DPA gate does NOT fire for EMPLOYEE / ADMIN (architectural lock)
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EMPLOYEE without DPA on business → 200 on non-whitelist (DPA does not apply to EMPLOYEE)")
    void employeeNotBlockedByDpa() throws Exception {
        clearTestBusinessDpa();

        // EMPLOYEE acceptance ≠ DPA acceptance — gate must not block.
        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + tokenFor(employeeUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data.mustAcceptDpa").value(false));
    }

    @Test
    @DisplayName("EMPLOYEE attempts POST /auth/accept-dpa → 403 (PreAuthorize)")
    void employeeForbiddenFromAcceptDpa() throws Exception {
        mockMvc.perform(post("/auth/accept-dpa")
                        .header("Authorization", "Bearer " + tokenFor(employeeUser))
                        .contentType("application/json")
                        .content("{\"dpaAccepted\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN attempts POST /auth/accept-dpa → 403 (PreAuthorize, no business to sign for)")
    void adminForbiddenFromAcceptDpa() throws Exception {
        mockMvc.perform(post("/auth/accept-dpa")
                        .header("Authorization", "Bearer " + tokenFor(adminUser))
                        .contentType("application/json")
                        .content("{\"dpaAccepted\":true}"))
                .andExpect(status().isForbidden());
    }

    // ────────────────────────────────────────────────────────────────
    // Legal acceptance gate
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EMPLOYEE with mustAcceptLegal=true → 403 requires=LEGAL_ACCEPTANCE on non-whitelist")
    void employeeMustAcceptLegalBlocksApi() throws Exception {
        setUserMustAcceptLegal(employeeUser);

        mockMvc.perform(get("/api/businesses/" + testBusiness.getId())
                        .header("Authorization", "Bearer " + tokenFor(employeeUser)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.requires").value("LEGAL_ACCEPTANCE"))
                .andExpect(jsonPath("$.data.mustAcceptLegal").value(true));
    }

    @Test
    @DisplayName("EMPLOYEE with mustAcceptLegal=true → 200 on /auth/accept-legal (the fix endpoint)")
    void employeeMustAcceptLegalReachesAcceptLegal() throws Exception {
        setUserMustAcceptLegal(employeeUser);

        mockMvc.perform(post("/auth/accept-legal")
                        .header("Authorization", "Bearer " + tokenFor(employeeUser))
                        .contentType("application/json")
                        .content("{\"termsAccepted\":true,\"privacyAccepted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustAcceptLegal").value(false));
    }

    @Test
    @DisplayName("OWNER with both flags pending → LEGAL gate fires first (priority order)")
    void ownerBothPendingPrioritizesLegal() throws Exception {
        clearTestBusinessDpa();
        setUserMustAcceptLegal(ownerUser);

        mockMvc.perform(get("/api/businesses/" + testBusiness.getId())
                        .header("Authorization", "Bearer " + tokenFor(ownerUser)))
                .andExpect(status().isForbidden())
                // LEGAL fires first per filter ordering — even though DPA is also pending.
                .andExpect(jsonPath("$.data.requires").value("LEGAL_ACCEPTANCE"))
                .andExpect(jsonPath("$.data.mustAcceptLegal").value(true))
                .andExpect(jsonPath("$.data.mustAcceptDpa").value(true));
    }
}
