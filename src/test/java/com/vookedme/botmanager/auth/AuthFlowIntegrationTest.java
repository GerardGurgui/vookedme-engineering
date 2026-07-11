package com.vookedme.botmanager.auth;

import com.vookedme.botmanager.auth.dto.LoginRequest;
import com.vookedme.botmanager.auth.dto.RefreshTokenRequest;
import com.vookedme.botmanager.auth.entity.RefreshToken;
import com.vookedme.botmanager.auth.entity.User;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the full authentication flow against a real
 * Spring context backed by PostgreSQL (Testcontainers).
 *
 * <p>Covers seven scenarios:
 * <ol>
 *   <li>Login with valid credentials → tokens issued → access token works on {@code /auth/me}</li>
 *   <li>Refresh token rotation → new token pair issued</li>
 *   <li>Logout → refresh token is revoked in the database</li>
 *   <li>Invalid password → 400</li>
 *   <li>Non-existent email → 400</li>
 *   <li>Inactive user → 400</li>
 *   <li>Login returns correct business context for a business-bound user</li>
 * </ol>
 *
 * <p><em>Note: this class extends {@code BaseIntegrationTest}, which
 * provides Testcontainers PostgreSQL, MockMvc, shared test fixtures
 * ({@code ownerUser}, {@code adminUser}, {@code employeeUser},
 * {@code testBusiness}, {@code ownerRole}, {@code userRepository},
 * {@code refreshTokenRepository}, {@code passwordEncoder},
 * {@code TEST_PASSWORD}), and the full Spring Security context.
 * The base class will be published in a subsequent batch.</em>
 */
class AuthFlowIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Full login flow: POST /auth/login → get tokens → verify tokens work on /auth/me")
    void login_withValidCredentials_returnsTokensAndAccessWorks() throws Exception {
        LoginRequest loginRequest = LoginRequest.builder()
                .email(ownerUser.getEmail())
                .password(TEST_PASSWORD)
                .build();

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.user.email").value("owner@test.com"))
                .andExpect(jsonPath("$.data.user.role").value("OWNER"))
                .andReturn();

        String accessToken = JsonPath.read(loginResult.getResponse().getContentAsString(), "$.data.accessToken");

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("owner@test.com"))
                .andExpect(jsonPath("$.data.role").value("OWNER"));
    }

    @Test
    @DisplayName("Refresh token flow: use refresh token → get new access token")
    void refreshToken_returnsNewTokens() throws Exception {
        LoginRequest loginRequest = LoginRequest.builder()
                .email(adminUser.getEmail())
                .password(TEST_PASSWORD)
                .build();

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String refreshToken = JsonPath.read(loginResult.getResponse().getContentAsString(), "$.data.refreshToken");

        RefreshTokenRequest refreshRequest = RefreshTokenRequest.builder()
                .refreshToken(refreshToken)
                .build();

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.email").value("admin@test.com"));
    }

    @Test
    @DisplayName("Logout flow: logout → verify refresh token is revoked")
    void logout_revokesRefreshToken() throws Exception {
        LoginRequest loginRequest = LoginRequest.builder()
                .email(employeeUser.getEmail())
                .password(TEST_PASSWORD)
                .build();

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String refreshToken = JsonPath.read(loginResult.getResponse().getContentAsString(), "$.data.refreshToken");

        RefreshTokenRequest logoutRequest = RefreshTokenRequest.builder()
                .refreshToken(refreshToken)
                .build();

        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"));

        RefreshToken revokedToken = refreshTokenRepository.findByToken(refreshToken).orElseThrow();
        assertThat(revokedToken.getRevoked()).isTrue();
        assertThat(revokedToken.getRevokedAt()).isNotNull();
    }

    @Test
    @DisplayName("Invalid credentials → 400")
    void login_withInvalidPassword_returnsBadRequest() throws Exception {
        LoginRequest loginRequest = LoginRequest.builder()
                .email(ownerUser.getEmail())
                .password("wrong-password")
                .build();

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("Non-existent email → 400")
    void login_withNonExistentEmail_returnsBadRequest() throws Exception {
        LoginRequest loginRequest = LoginRequest.builder()
                .email("nonexistent@test.com")
                .password(TEST_PASSWORD)
                .build();

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("Inactive user → 400")
    void login_withInactiveUser_returnsBadRequest() throws Exception {
        User inactiveUser = userRepository.save(User.builder()
                .email("inactive@test.com")
                .passwordHash(passwordEncoder.encode(TEST_PASSWORD))
                .name("Inactive User")
                .role(ownerRole)
                .business(testBusiness)
                .isActive(false)
                .build());

        LoginRequest loginRequest = LoginRequest.builder()
                .email(inactiveUser.getEmail())
                .password(TEST_PASSWORD)
                .build();

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("Login returns correct business info for business-bound user")
    void login_withBusinessUser_returnsBusinessInfo() throws Exception {
        LoginRequest loginRequest = LoginRequest.builder()
                .email(ownerUser.getEmail())
                .password(TEST_PASSWORD)
                .build();

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.businessId").value(testBusiness.getId()))
                .andExpect(jsonPath("$.data.user.businessName").value("Test Business"));
    }
}
