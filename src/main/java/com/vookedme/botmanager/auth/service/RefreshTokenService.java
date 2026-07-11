package com.vookedme.botmanager.auth.service;

import com.vookedme.botmanager.auth.entity.RefreshToken;
import com.vookedme.botmanager.auth.entity.User;
import com.vookedme.botmanager.auth.repository.RefreshTokenRepository;
import com.vookedme.botmanager.auth.security.JwtService;
import com.vookedme.botmanager.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;

    @Transactional
    public RefreshToken createRefreshToken(User user, String deviceInfo, String ipAddress) {
        String tokenValue = jwtService.generateRefreshToken(user);

        RefreshToken refreshToken = RefreshToken.builder()
                .token(tokenValue)
                .user(user)
                .expiresAt(LocalDateTime.now().plusSeconds(jwtService.getRefreshTokenExpiration() / 1000))
                .deviceInfo(deviceInfo)
                .ipAddress(ipAddress)
                .build();

        RefreshToken saved = refreshTokenRepository.save(refreshToken);
        log.info("Refresh token created for user: {}", user.getEmail());

        return saved;
    }

    @Transactional(readOnly = true)
    public RefreshToken findByToken(String token) {
        return refreshTokenRepository.findByToken(token)
                .orElseThrow(() -> new BadRequestException("Invalid refresh token"));
    }

    @Transactional
    public RefreshToken verifyAndRotate(String token, String deviceInfo, String ipAddress) {
        RefreshToken refreshToken = findByToken(token);

        // Check revocation first — a revoked token being re-presented signals
        // potential token theft. Revoke all tokens for the user to force
        // re-authentication across all devices.
        if (refreshToken.getRevoked()) {
            log.warn("Refresh token reuse detected for userId: {}. Revoking all tokens.",
                    refreshToken.getUser().getId());
            revokeAllUserTokens(refreshToken.getUser().getId());
            throw new BadRequestException("Refresh token has been revoked");
        }

        // Then check expiration.
        if (refreshToken.isExpired()) {
            log.debug("Expired refresh token for userId: {}", refreshToken.getUser().getId());
            throw new BadRequestException("Refresh token has expired");
        }

        // Revoke the current token (rotation — each token is single-use).
        refreshToken.revoke();
        refreshTokenRepository.save(refreshToken);

        // Issue a new token.
        return createRefreshToken(refreshToken.getUser(), deviceInfo, ipAddress);
    }

    @Transactional
    public void revokeAllUserTokens(Long userId) {
        refreshTokenRepository.revokeAllByUserId(userId, LocalDateTime.now());
        log.info("All refresh tokens revoked for userId: {}", userId);
    }

    @Transactional
    public void revokeToken(String token) {
        RefreshToken refreshToken = findByToken(token);
        refreshToken.revoke();
        refreshTokenRepository.save(refreshToken);
        log.info("Refresh token revoked for user: {}", refreshToken.getUser().getEmail());
    }

    @Transactional
    public int cleanupExpiredTokens() {
        int deleted = refreshTokenRepository.deleteExpiredTokens(LocalDateTime.now());
        log.info("Cleaned up {} expired refresh tokens", deleted);
        return deleted;
    }
}
