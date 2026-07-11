# ADR-018 — JWT Refresh Token Rotation

**Status:** Accepted  
**Date:** 2026-05-11  
**Domain:** security  
**Editorial:** CORE

> **Engineering Question Answered:** When a client application persists a refresh token to maintain long-lived sessions, what rotation and revocation strategy ensures that a stolen token cannot be used silently — and that any compromise is detected and contained deterministically, regardless of whether the legitimate owner notices?

---

## Problem

A session system built on short-lived access tokens requires a mechanism to issue new access tokens without asking the user to re-authenticate frequently. The standard solution is a refresh token: a longer-lived credential stored by the client and presented to the server to obtain a new access token when the current one expires.

The refresh token creates a security surface that does not exist in pure short-lived access token schemes. If a refresh token is stolen — through a compromised device, a network intercept, or a storage vulnerability — the attacker can silently maintain access indefinitely by rotating the token themselves, issuing new access tokens, and never triggering a visible session event. The legitimate owner may never know. The system has no mechanism to detect or terminate the attacker's session.

The design question is not whether to use refresh tokens — the alternative, extremely long-lived access tokens, is worse — but how to design the refresh token lifecycle so that theft is structurally detectable and the damage window is bounded.

## Context

The authentication model produces two token types at login:

**Access token** — a short-lived, HMAC-SHA256 signed JWT containing the user's identity claims (subject: userId, plus role and business context). The access token is verified by the authentication filter on every request. It is not stored on the server; verification is purely cryptographic. Its short expiration limits the damage window if the access token itself is leaked.

**Refresh token** — a longer-lived HMAC-SHA256 signed JWT containing only the user's identity (subject: userId) and a unique identifier (jti). Unlike the access token, the refresh token is persisted in the database. Every issued refresh token has a database record; validity is checked against that record, not by cryptographic verification alone. The server can revoke a refresh token at any time by marking its database record as revoked, regardless of whether the token itself has expired.

The refresh token serves one purpose: presenting it to `/auth/refresh` issues a new access token. It has no other authority.

The system must satisfy two properties simultaneously:

1. **Theft detection:** if a valid refresh token has been stolen and used by an attacker, the server must be able to detect this at the earliest opportunity and terminate the attacker's access.
2. **Legitimate use continuity:** a legitimate user presenting their refresh token must receive a new access token without interference from the theft-detection mechanism.

These two properties are in tension. The resolution is token rotation with reuse detection.

## Decision

Every successful refresh operation replaces the presented token with a new one. The presented token is revoked immediately; a new token is issued and returned. This is called **refresh token rotation**.

The consequence of rotation is that every refresh token is single-use. A token that has been successfully used to obtain a new access token cannot be reused — it is already revoked in the database. The only valid refresh token at any point in time is the one most recently issued.

### Reuse detection

When a token is presented to `/auth/refresh`, the first check is whether it has already been revoked — before checking whether it has expired.

If the presented token is already revoked, two scenarios are possible:

1. A client bug or race condition has caused a token to be presented twice. This is rare in a well-implemented client but possible.
2. The token was legitimately used by the owner, rotated, and the original token was subsequently stolen and presented by an attacker.

The system cannot distinguish between these two scenarios from the presented token alone. The response to both is identical: **all refresh tokens for that user are revoked immediately**.

This is the reuse detection invariant: presenting a revoked refresh token is treated as evidence of token theft, and the response is total session termination across all devices and sessions for that user.

### Why total revocation rather than token-specific revocation

Revoking only the presented (already-revoked) token achieves nothing — it was already revoked. The threat model is:

- The attacker presents the original (stolen) token
- The server revokes it (but it was already revoked)
- The attacker still holds the token that was issued to them by the *first* successful reuse — the rotated token that the owner no longer has

The attacker's position is not changed by revoking the token they just presented. The only action that terminates the attacker's access is revoking all tokens in the user's session family — including any token that may have been issued to the attacker by a prior fraudulent rotation.

The user is forced to re-authenticate. This is the correct outcome: re-authentication is the only action that confirms the user's continued control of their credential.

### Token uniqueness

Each refresh token contains a `jti` (JWT ID) claim — a UUID generated at issuance. This guarantees that every issued token is globally unique regardless of how closely together tokens are issued. The token value stored in the database is the full signed JWT string; the `jti` ensures there are no key collisions across concurrent issuances.

### Refresh token content

The refresh token carries minimal information: the user's identifier (subject) and the `jti`. It does not carry role, business context, or any other claim. The business context claims are attached at access token issuance time, where they are fetched from the current state of the database. This ensures that role changes and business changes are reflected in the next access token, not deferred until the next authentication.

### Session metadata

Each refresh token record stores the device information and IP address observed at the time of issuance. This metadata is not used for access control decisions — it does not restrict which device or IP may present the token — but it provides forensic context for security review: when a reuse event fires, the metadata on the revoked token describes the environment of the original issuance, and the metadata on any tokens issued to an attacker describes the attacker's environment.

### Logout and password reset

Logout revokes the specific refresh token being abandoned. Password reset revokes all refresh tokens for the user across all sessions — the same total revocation used by reuse detection. This ensures that credential changes propagate immediately to session termination: a user who changes their password immediately invalidates any access held by an attacker who knew the old password.

### Lifecycle management

Refresh token records accumulate in the database over time. An administrative job runs periodically to delete records that are either expired or revoked. Expired and revoked tokens have no further function; their records serve only as forensic evidence of session history, and the retention window for that evidence is bounded.

## Alternatives Considered

| Option | Description | Why Rejected |
|---|---|---|
| Opaque random tokens (no JWT) | Store a random secret string as the refresh token; verify by database lookup only | Requires hashing the stored value to protect against database exposure. The HMAC-signed JWT approach provides the same cryptographic guarantee (a leaked database record is unusable without the signing key) with the additional benefit that the token's authenticity can be verified before the database lookup — reducing the attack surface from unauthenticated token forgery |
| Long-lived access tokens (no refresh tokens) | Extend access token lifetime to hours or days, eliminating the need for refresh | A stolen access token is valid for its full lifetime with no revocation mechanism. Database-persisted refresh tokens can be revoked server-side at any moment. Long access token lifetimes trade away the ability to respond to compromise in exchange for client simplicity |
| Sliding expiration without rotation | Extend the refresh token's expiration on each successful use without issuing a new token | A stolen token remains valid indefinitely as long as the attacker uses it within the sliding window. Rotation ensures that any use of the token invalidates the version the attacker holds — they must re-steal the rotated token before the owner uses it again. Sliding expiration without rotation provides no theft detection or containment |
| Token families with sibling revocation | Track each rotation chain as a family; on reuse, revoke only the family branch | Adds complexity without meaningful security improvement. In a single-user, single-session model, the family is the user's entire active session set. Revoking a sub-family branch while leaving other branches active provides no additional safety: the attacker may have exited the branch being revoked |
| Family-scoped revocation with family_id | Add a family identifier to each token; on reuse, revoke only tokens in that family | Adds a column and a query while providing a bounded benefit: a legitimate user with multiple concurrent sessions on different devices would not lose all sessions on reuse of one. Accepted as a future enhancement if multi-session isolation becomes a product requirement. The current model treats all sessions for a user as a single revocation unit |

## Consequences

### Positive

- Refresh token theft is detectable: a stolen token used by an attacker surfaces as a reuse event that forces re-authentication, terminating both the attacker's and the owner's sessions.
- The damage window after theft is bounded: the attacker can only use the stolen token until the legitimate owner next attempts a refresh, at which point the reuse event fires. The attacker cannot silently maintain access indefinitely.
- Logout and password reset propagate to all sessions immediately: no outstanding refresh tokens survive a deliberate credential change.
- The refresh token carries no business context: role changes and business changes take effect at the next access token issuance, without waiting for re-authentication.

### Negative

- Reuse detection cannot distinguish legitimate double-use (client bug, race condition) from theft. Total revocation penalises the user in both cases. This is a deliberate trade-off: the cost of a false positive (forced re-authentication) is low; the cost of a false negative (undetected token theft) is high.
- Multi-device users are affected by reuse events across all devices simultaneously. If token reuse fires for any reason, all sessions are terminated. This is the current boundary of the revocation unit; future multi-session support would narrow it.
- The refresh token endpoint requires access to the database on every call: the database lookup to check revocation status is unavoidable by design, as it is the revocation check that makes reuse detection possible.

### Neutral

- Access tokens remain valid after refresh token revocation until their expiration. A user whose sessions are terminated by a reuse event may still hold a valid access token from the most recent successful refresh. This residual validity window is bounded by the access token expiration and is accepted as consistent with the design.

## Engineering Principle

A refresh token that can be reused after theft provides no security beyond a long-lived access token. Rotation converts every refresh token into a single-use token: once presented, it is spent. Reuse detection converts the expected behaviour of a stolen token — silent reuse — into a security event that terminates the attacker's access. The cost is that the revocation signal is binary: once reuse is detected, the entire user session is terminated. This is not a limitation to be refined away but a deliberate design choice: certainty of containment at the cost of guaranteed re-authentication is the correct trade-off when the alternative is uncertain containment at the cost of undetected persistence.

## Related

- [ADR-016](./ADR-016-tenant-isolation-pattern.md) — the JWT's business context, derived at access token issuance time, is the source of the `businessId` claim that `AuthorizationService` uses for tenant isolation
- [ADR-006](./ADR-006-user-identity-model.md) — the `User` entity whose identity is encoded in the JWT subject claim; global email uniqueness ensures unambiguous authentication lookup

## Source Code Reference

- `RefreshToken.java` *(published — SC-1)* — the persistence entity; `revoked`, `revokedAt`, `expiresAt`, `deviceInfo`, `ipAddress`; `isValid()`, `isExpired()`, `revoke()` methods
- `RefreshTokenService.verifyAndRotate()` *(published — SC-1)* — the single path through which refresh tokens are consumed; performs revocation-first check, total user revocation on reuse, and new token issuance
- `RefreshTokenService.revokeAllUserTokens()` *(published — SC-1)* — total revocation; called on reuse detection, logout (all-sessions variant), and password reset
- `JwtService.generateRefreshToken()` — issues a new refresh token with a UUID `jti` claim; carries only the user subject, no business or role claims
- `JwtService.generateAccessToken()` — issues a new access token with the cu