# ADR-006 — User Identity Model

**Status:** Accepted  
**Date:** 2026-05-03  
**Domain:** identity  
**Editorial:** CORE

> **Engineering Question Answered:** In a multi-tenant scheduling platform where the same human being might be an operator of one business and a customer of another, how do you separate the global identity of the person from their role within a specific business — so that authentication, authorisation, and GDPR attribution each have a clear, unambiguous subject?

---

## Problem

An appointment scheduling platform has two distinct categories of human actor:

**Operators** — the owners and employees who manage a business through the platform. They authenticate, receive a JWT, access the administration panel, and perform privileged operations. Their identity is tied to the platform account, not to any particular business.

**Customers** — the people who book appointments at a specific business. Their relationship is with the business, not with the platform. They do not authenticate through the platform. Their phone number, appointment history, and contact preferences are records held by the business.

A naive identity model might conflate these two categories under a single `users` table — representing every person who appears in the system as a user. This conflation produces a set of ambiguities that compound as the system grows:

- If the same phone number appears as both an operator and a customer (at a different business), are they the same entity? Do they share a password? Can data from one context be accessed in another?
- GDPR right-to-erasure applies differently: erasing an operator account has security implications; erasing a customer's commercial record has data-retention implications. The two operations are not the same.
- Authentication requires a globally unambiguous identity. A phone number that means one person in a business-A context and a different person in a business-B context cannot serve as a login credential.

## Context

The platform serves N independent businesses on a shared database. A single deployment handles all of them. The data isolation boundary is the business (ADR-016).

Within that architecture, the identity model must answer two questions that have different correct answers depending on which category of person is being considered:

- For operators: identity is global. An owner authenticates once; their identity is the same regardless of which business's panel they are looking at. An operator's phone number must be unique across the entire platform.
- For customers: identity is per-business. The same person can be a customer at two different businesses with no coupling between those relationships. A customer is a commercial relationship record held by one business.

## Decision

Two separate entity types represent the two categories:

| Entity | Represents | Phone uniqueness | Scope |
|---|---|---|---|
| `users` | A human operator — owner or employee — of a business | **UNIQUE GLOBAL** across the platform | Platform-wide |
| `customers` | A commercial relationship between a person and one specific business | UNIQUE **per business** | Per-business |

### The User entity

`users` is the authentication identity: the human being who has an account on the platform. A user's phone number is the login credential and must be globally unique — there can be no ambiguity about who is logging in. A user belongs to exactly one business in the current model (see the Membership table path below).

`users.phone` carries a UNIQUE constraint at the database level — not `UNIQUE(phone, business_id)`, but `UNIQUE(phone)` globally. This is not a business-scoped uniqueness constraint. It is a platform-wide invariant: one phone number, one operator identity.

### The Customer entity

`customers` is a commercial relationship record. It exists within the context of a single business. The same person can have customer records at multiple businesses — and those records are independent, with no coupling. One business cannot see or modify another business's customer records. `customers.phone` is unique per business — a phone number can appear in multiple businesses' customer tables without conflict.

### What a customer is not

A customer record does not authenticate. It carries no password, no JWT, no session. The customer's interaction with the system is through WhatsApp — they send a message and the orchestration layer resolves the booking workflow on their behalf. The customer's phone number is the communication channel, not an authentication credential.

A customer record may belong to the same human being who is also a user (operator) on the platform — this is an incidental overlap, not a modelled relationship. The two entity types have independent lifecycles, independent GDPR obligations, and independent data retention rules.

### The Membership table path (future)

The current model — one user, one business — is correct for the MVP. It produces a simple authentication model: the JWT's business context is unambiguous.

If the product requires that one person manages multiple businesses (a franchise operator, a consultant who works with several clients), the extension path is a `Membership` table:

```
User (global) ↔ Membership ↔ Business + Role
```

This model allows one user to have different roles in different businesses, while preserving the global uniqueness of the user's identity. The JWT would carry the authenticated `userId`; the business context would be selected per-request from the user's memberships. `AuthorizationService` would resolve the membership for the requested business.

This path requires refactoring the JWT claims and the authorisation service. It is tracked as a future enhancement — not in the current implementation.

## Rationale

**Authentication model is simple and auditable.** A globally unique phone number means the login lookup is unambiguous. There is no business-scoped disambiguation step during authentication. The JWT carries one identity.

**No cross-tenant identity leakage.** Separating users from customers means an operator's platform account has no structural relationship to their customer records at businesses they patronise. GDPR access and erasure requests for one entity type do not implicate the other.

**GDPR attribution is clear.** The data controller relationship differs between the two entities: user data is held by the platform operator; customer data is held by each business owner (the business is the data controller for their customers). This distinction drives different legal obligations, different retention policies, and different erasure workflows. A single unified entity would require case-by-case reasoning about which legal role applies.

**Future authentication patterns are unblocked.** Global user uniqueness is a prerequisite for any future authentication extension — two-factor authentication, password reset via phone, single sign-on. Per-business uniqueness would require rewriting the authentication model at the point one of these features becomes necessary.

## Alternatives Considered

| Option | Why Rejected |
|---|---|
| Single `users` table with UNIQUE(phone, business_id) | A person with the same phone number at two businesses would have two separate accounts. Password reset would need to identify which account the user intends — ambiguous. Cross-business auth is impossible because the identity anchor is per-business. Two-factor authentication cannot be uniquely attributed. |
| Unified `persons` table with `user_role` and `customer_role` flags | A single entity that can be both an operator and a customer conflates the authentication contract with the commercial relationship. GDPR erasure of the customer role must not erase the operator account (or vice versa). The legal obligations attached to each role are different. A flag cannot enforce that separation structurally. |
| Per-business user tables | N user tables for N businesses produces cross-tenant authentication complexity. A user who moves between businesses has their authentication identity duplicated. The global security model — one JWT, one identity — becomes impossible to implement cleanly. |

## Consequences

### Positive

- Authentication is globally unambiguous: one phone number, one operator identity, one JWT.
- GDPR obligations can be fulfilled independently for each entity type: erasing a customer record does not affect the operator account.
- The customer entity can have per-business uniqueness semantics without conflicting with the operator's global identity.
- No cross-tenant identity inference is possible: operator A of Business X cannot look up their customer record in Business Y through their operator account.

### Negative

- A person who works as an employee at two different businesses cannot hold two operator accounts with the same phone number in the current model. The Membership table extension (above) is the resolution path for this case, but it is not yet implemented.
- Developers must understand the distinction to work effectively in the codebase: confusing `User` for `Customer` (or using the wrong entity in a service method) produces semantically incorrect behaviour that is not always caught by type checking alone.

### Neutral

- Customer contact information (phone, name) is stored on `customers` — it is business-scoped. The platform does not hold a central directory of all customers across businesses. This is the correct behaviour given that each business's customer list is their own commercial data.

## Engineering Principle

When a system has two categories of person with different identity semantics — one global, one scoped to a context — conflating them into a single entity table trades short-term simplicity for long-term ambiguity. Each layer of the system that needs to reason about identity will develop its own conventions for disambiguating the two cases, and those conventions will diverge. The correct response is to separate the two categories at the entity level, with different uniqueness constraints, different lifecycle rules, and different GDPR obligations. The cost is one additional entity type; the benefit is that the identity contract is unambiguous everywhere in the system.

## Related

- [ADR-016](./ADR-016-tenant-isolation-pattern.md) — tenant isolation; the `User` entity is globally scoped, but access to business-scoped data is enforced by `AuthorizationService` against business membership
- [ADR-004](./ADR-004-customer-lifecycle-states.md) — the Customer lifecycle (ACTIVE / ARCHIVED / ANONYMIZED); the GDPR erasure path applies to the `customers` entity, not to `users`
- [ADR-003](./ADR-003-hybrid-audit-strategy.md) — audit architecture; Layer 1 (`updated_by_user_id`) tracks operator identity for all mutations; customer-initiated actions are attributed by customer identity through a separate audit path

## Source Code Reference

*Populated when source code is present (v0.3.0+).*

- `User.java` — the operator entity; `phone` field with UNIQUE constraint; `role` field (OWNER / EMPLOYEE / ADMIN); belongs to one `Business`
- `Customer.java` — the commercial relationship entity; `phone` field with a per-business UNIQUE constraint (`UNIQUE(phone, business_id)`); lifecycle governed by `CustomerStatus` (ADR-004)
- `AuthorizationService.java` — resolves the authenticated `User` from the JWT and validates their membership in the requested business; `Customer` objects are never used for authentication
- `WebhookController.java` — the bot-facing entry point; incoming requests are attributed to a `Customer` (resolved by phone number within the business's scope), not to a `User`
