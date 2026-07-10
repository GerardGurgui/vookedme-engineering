# ADR-005 — Email Identity — Global Uniqueness

**Status:** Accepted  
**Date:** 2026-04-30  
**Domain:** identity  
**Editorial:** REFERENCE

> **Engineering Question Answered:** In a multi-tenant platform where each business has its own set of operator accounts, should the email address used to log in be unique per business or unique across the entire platform — and what are the architectural consequences of each choice?

---

## Problem

The `users` table holds the operator accounts for a multi-tenant scheduling platform: business owners and employees who authenticate, receive a JWT, and access the administration panel. Each operator belongs to a business. The natural design question that arises in any multi-tenant system is whether identity constraints should be scoped to the tenant or applied globally.

For email specifically, the question is: should the same email address be usable as separate operator accounts in different businesses, or must one email address correspond to exactly one operator identity across the entire platform?

## Context

The platform is multi-tenant: a single deployment serves multiple independent businesses on a shared database, with application-layer data isolation (ADR-016). Each business has its own operators, customers, appointments, and settings. The data is isolated; the infrastructure is shared.

Within this architecture, the `users.email` column carries a `UNIQUE` constraint. The question is whether that constraint should be `UNIQUE(email)` — globally unique across all businesses — or `UNIQUE(email, business_id)` — unique only within a single business's operator set.

This question arose as part of an integrity review. The constraint had been global since the schema was initially defined; the review asked whether that choice was intentional and whether it was the correct long-term decision.

## Decision

**`users.email` carries a UNIQUE constraint that is global across the entire platform.** One email address maps to exactly one operator identity. A person who works at two different businesses on the platform uses the same account for both — a model that requires a future Membership table extension (described in ADR-006) rather than two separate accounts with the same email.

## Rationale

### Authentication model remains simple

The login flow resolves an email to a user account: `findByEmail(email)` returns at most one result. With a global unique constraint, this lookup is unambiguous — no disambiguation step, no additional identifier, no business context required at the authentication stage. The JWT carries one identity.

Changing to per-business uniqueness would require every login to be qualified: either the user provides a business identifier alongside their email (adding friction to an operation that happens frequently), or the system presents a disambiguation screen when multiple accounts exist for the same email (adding a step that most users would never see and would find confusing when they did).

### Audit trail is unambiguous

An operator's email is the identifier that appears in audit logs, security events, and session records. A platform-wide audit trace can follow a single email across all events and know it refers to one person. Per-business uniqueness means `person@example.com` in business A and `person@example.com` in business B are different database records with the same audit identifier — distinguishable by joining on business context, but not distinguishable from the log entry alone.

### Established SaaS convention

The major multi-tenant productivity platforms — Slack, GitHub, Notion, Linear — operate with a global email identity. A user invited to multiple workspaces or organisations uses one account and selects the active context. The user's mental model is: one email, one account, multiple workspaces. Per-business uniqueness would require users who work across multiple businesses to maintain separate credentials for each — a departure from the convention they have already internalised from other software.

### Forward compatibility with SSO and external authentication

If the platform later integrates with an external identity provider — Google Sign-In, Microsoft Entra, SAML-based SSO — the mapping from the external identity's email to the platform account must be deterministic. With global uniqueness, the mapping is a direct lookup. With per-business uniqueness, the same email might resolve to multiple platform accounts, and the authentication callback would need to know which business the user is authenticating into before resolving their identity — a circular dependency that complicates every SSO implementation.

### Multi-employment is handled without changing the constraint

The realistic edge case for per-business uniqueness is a person who wants to hold operator accounts at two different businesses simultaneously using the same email. This scenario is handled by the account lifecycle without relaxing the uniqueness constraint:

When a person leaves one business to join another, their account at the first business is soft-deactivated. They create a new account at the second business using the same email. This works because the constraint is on active accounts — a soft-deactivated account can be excluded from the uniqueness check, or the old account's email can be cleared at deactivation. The flow requires a deliberate deactivation action, which is the correct behaviour: an operator carrying active credentials for a business they no longer work at is a security problem, not a convenience feature.

## Alternatives Considered

| Option | Why Rejected |
|---|---|
| `UNIQUE(email, business_id)` | The same email would represent different database identities in different businesses. Login requires an additional business identifier for disambiguation. Audit trail requires a business qualifier to be meaningful. SSO integration becomes a circular resolution problem. The constraint change would require refactoring the authentication service, JWT generation, and every audit reference — a significant cost for a model that introduces complexity without resolving any current limitation. |
| Unified person table with role flags | A single `persons` table where each row can hold both operator and customer roles for different businesses. Merges two entity types with different identity semantics, different GDPR obligations, and different lifecycle rules. This is the conflation problem that ADR-006 resolves by separating `users` (operators) and `customers` (commercial relationships) into distinct tables. |

## Consequences

### Positive

- Authentication is a single, unambiguous database lookup. No multi-step identity resolution.
- One email address, one audit identity. Security logs require no business-context join to be readable.
- Compatible with all future SSO integration patterns without schema changes.
- The operator experience follows the established convention: one account, potentially used across multiple business contexts.

### Negative

- A person who holds simultaneous operator roles at two different businesses cannot be represented directly in the current model. The resolution path — the Membership table described in ADR-006 — is not yet implemented. Until it is, simultaneous multi-business operator access requires separate accounts or a structural change.
- In the rare case that two distinct people share an email address (for example, a shared business email), only one can hold an operator account. This is considered an edge case with a clear resolution: each person requires a personal email address to maintain a separate operator identity.

### Reversibility

The decision is reversible. Changing to per-business uniqueness in the future would require removing the global constraint and replacing it with a composite one, refactoring the authentication service, updating JWT generation, and reviewing every audit reference. The estimated effort is substantial but bounded. The trigger for revisiting this decision would be evidence that a significant fraction of operators need simultaneous multi-business access, or entry into a market where shared email addresses are common.

## Engineering Principle

The scope of an identity constraint determines the complexity of every system layer that consumes that identity. A globally unique email address makes authentication, audit, and SSO simple at the cost of one edge case: a person with two simultaneous jobs at different businesses requires either separate accounts or a Membership table. A per-business unique email address handles that edge case by introducing complexity into authentication, audit, and SSO integration for every user, not just the multi-employer edge case. The correct trade-off is to optimise for the common case — one email, one identity — and address the edge case through a focused model extension rather than by relaxing the global constraint.

## Related

- [ADR-006](./ADR-006-user-identity-model.md) — the User/Customer entity separation; `users.email` is the global operator identity, while `customers` have per-business scope and no authentication role
- [ADR-016](./ADR-016-tenant-isolation-pattern.md) — tenant isolation; the global email identity is how the JWT is anchored, while business data isolation is enforced by `AuthorizationService` separately

## Source Code Reference

*Populated when source code is present.*

- `AuthService.java` — `findByEmail()` used for login lookup; relies on global uniqueness for unambiguous resolution
- `UserDetailsServiceImpl.java` — Spring Security `loadUserByUsername()` implementation; resolves email to the authenticated principal
- `JwtService.generateAccessToken()` — encodes the authenticated identity into the JWT; carries the globally unique `userId` as subject
