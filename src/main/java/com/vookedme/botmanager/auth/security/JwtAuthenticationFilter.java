package com.vookedme.botmanager.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT extraction and validation filter. Runs once per request.
 *
 * <p>Extracts the JWT from the {@code Authorization: Bearer} header, validates
 * it via {@link JwtService}, confirms the token is an access token (carries
 * a {@code role} claim — refresh tokens do not), and populates the
 * {@link SecurityContextHolder} with the authenticated principal.
 *
 * <p>{@link JwtService} is published in SC-1. {@code UserDetailsServiceImpl}
 * is published in a subsequent source batch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        // No Authorization header or not a Bearer token — pass through.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(7);

        try {
            // Validate token signature and expiration.
            if (!jwtService.isTokenValid(jwt)) {
                log.debug("Invalid JWT token");
                filterChain.doFilter(request, response);
                return;
            }

            // Confirm this is an access token: access tokens carry a "role" claim;
            // refresh tokens do not. Reject refresh tokens presented as access tokens.
            String role = jwtService.extractRole(jwt);
            if (role == null) {
                log.debug("Rejected refresh token used as access token");
                filterChain.doFilter(request, response);
                return;
            }

            Long userId = jwtService.extractUserId(jwt);

            // Only authenticate if there is no existing authentication in this context.
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserById(userId);

                if (userDetails.isEnabled()) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );

                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    log.debug("Authenticated user: {} with role: {}",
                            userDetails.getUsername(),
                            userDetails.getAuthorities());
                }
            }
        } catch (Exception e) {
            log.debug("JWT authentication failed: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
