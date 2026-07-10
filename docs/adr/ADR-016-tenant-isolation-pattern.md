# ADR-016 — Tenant Isolation Pattern

**Status:** Accepted  
**Date:** 2026-04-28  
**Domain:** security / multi-tenancy  
**Editorial:** FOUNDATIONAL

> **Engineering Question Answered:** In a multi-tenant system where a single deployment serves multiple independent businesses on a shared database, how do you ensure that one business's data is completely inaccessible to another — by structural design rather than by developer convention?

---

## Problem

A single deployment of VookedMe serves N independent businesses. Their data — customers, appointments, employees, offerings, schedules — must be completely isolated. Two businesses must never share, observe, or interfere with each other's data.

In a shared database, the boundary between tenants is not enforced by the storage layer by default. Every query, every service call, every data access point is a potential isolation failure. The risk is not limited to read access: a write operation that operates on the wrong tenant's data is a data integrity failure.

The isolation requirement is structural: it must be impossible to bypass, not merely unlikely to forget.

## Context

The system uses a single PostgreSQL database. All tenants share the same schema. Every business-scoped entity — `Customer`, `Appointment`, `Offering`, `Schedule`, `Notification` — carries a `business_id` foreign key. The `User` entity is globally scoped (see ADR-006) but access to business-scoped data is restricted to users who belong to that business.

The access pattern requires knowing: (1) which authenticated user is making the request, and (2) whether that user is authorised to act on behalf of the requested business. This check must happen before any business-scoped data is touched.

There are two broad implementation strategies:

**Database-layer enforcement (Row Level Security)**: PostgreSQL policies that filter all queries to the authenticated tenant's rows automatically, at the storage engine.

**Application-layer enforcement**: a validation gate in the service layer that is called explicitly at every entry point, with business identity derived from the authenticated session rather than from the request.

## Decision

Tenant isolation is enforced at the application layer through a single, explicit `AuthorizationService`. Every service method that accesses business-scoped data calls this service before performing any operation. The business identity used for isolation is derived from the authenticated JWT — it is never taken from the request body or request path without verification.

### The isolation contract

1. **Single enforcement point.** `AuthorizationService` is the only place in the application where business membership is verified. Every business-scoped operation entry point calls it. There is no second path.

2. **Business identity from the authenticated token.** The authenticated user's `userId` is extracted from the JWT by the authentication filter. The `businessId` is taken from the request path but validated against the authenticated user's membership — it is never trusted without verification.

3. **Default deny.** If the authenticated user is not a member of the requested business, the request is rejected with a 403 response. The response does not distinguish "business does not exist" from "you are not a member": the caller receives no information about tenants they do not belong to.

4. **Explicit parameter propagation.** Once verified, the `businessId` is passed as an explicit parameter to all downstream service logic. Downstream methods do not re-derive the business identity from thread context or re-query it — they use what was validated at the entry point.

5. **Role-scoped gates.** `AuthorizationService` provides role-aware variants: `requireOwner(businessId)`, `requireEmployee(businessId)`, `requireAdmin()`. These enforce both business membership and the minimum role required for the operation.

### What this makes structurally impossible

- A request authenticated for Business A cannot read or write Business B's data: the service layer rejects it before any data access occurs.
- A request cannot bypass isolation by supplying a different `businessId` in the request body: the body is never used as the authority for business identity.
- A developer cannot accidentally expose cross-tenant data by omitting a filter: the absence of the `AuthorizationService` call is visible in code review and caught by integration tests.

## Rationale

**Transparency.** Application-layer enforcement is written in Java — the same language as the rest of the system. Any engineer reviewing a service method can see whether `AuthorizationService` is called, at what point, and with what arguments. The complete isolation contract is in one class.

**Testability.** The isolation gate is a service that can be mocked in unit tests and exercised fully in integration tests. Isolation failures produce clear exceptions with identifiable stack traces. Cross-tenant access scenarios are testable as ordinary Java tests.

**Consistency with the rest of the security model.** Authentication, authorisation, and role enforcement all live in the application layer. Placing isolation enforcement in the same layer means all security logic can be reviewed in one place.

**Portability.** Application-layer isolation is not coupled to PostgreSQL-specific features. A decision to change the storage engine, add a read replica, or run analytical queries against a different database does not require re-implementing isolation at the storage layer.

## Alternatives Considered

| Option | Why Rejected |
|---|---|
| PostgreSQL Row Level Security (RLS) | Isolation logic lives in database policies — not in application code. A code reviewer cannot verify isolation by reading service methods. Local development and test environments require matching policy configuration. A schema migration that adds a new table must also add RLS policies; forgetting the policy creates a silent isolation gap. Hibernate integration with RLS requires configuration that is not obvious from the entity definitions. |
| Spring Data `@Filter` annotation per entity | Hibernate filters reduce boilerplate but the activation of the filter is invisible in service method signatures. An engineer who forgets to activate the filter does not see the gap in code review. Filter configuration scatters isolation logic across entity definitions rather than centralising it at the validation gate. |
| `@Aspect` (AOP) to intercept all repository calls | Intercepting all data access points via AOP reduces the risk of missed gates but makes the isolation logic invisible in service method bodies. Debugging an isolation failure requires understanding the AOP configuration. The behavioural contract is harder to test at the unit level. |
| Separate schema or database per tenant | Strong isolation at the storage layer, but prohibitive operational cost: schema migrations must be applied N times, connection pools scale with tenant count, and operational tooling must account for N databases. Rejected for the current deployment model; revisable if the number of tenants reaches a scale where per-tenant storage becomes the only viable option. |

## Consequences

### Positive

- Tenant isolation is structural. It does not depend on individual developers remembering to filter by `business_id` in every query.
- The complete isolation contract is readable in one class. Code review can verify isolation compliance for a service method in seconds.
- Integration tests cover the isolation boundary directly: authenticated requests for one business are rejected when they attempt to access another business's data.
- The audit log records every action with both actor identity and business scope. A forensic review of any security incident has a consistent, complete trail.

### Negative

- Every new service method that touches business-scoped data must call `AuthorizationService` at its entry point. This is a developer discipline requirement — the enforcement does not activate automatically. It is enforced through code review, integration test conventions, and the visibility of the absence.
- `businessId` is propagated as an explicit method parameter through the service layer. This adds a parameter to every business-scoped service method, which increases method signature length.

### Neutral

- The authentication filter that extracts the JWT claims adds one parsing operation per request. This is negligible in the context of a typical service call that queries the database.
- The `requireOwner()` / `requireEmployee()` role gates produce 403 responses for insufficient roles. The distinction between "not a member of this business" and "member but insufficient role" is intentionally not exposed to callers.

## Engineering Principle

Tenant isolation in a multi-tenant system must be structural, not conventional. A convention — "always filter by `business_id`" — is a developer discipline problem: any single omission creates a data exposure. A structural guarantee makes isolation a property of the architecture rather than a property of every individual implementation decision. The correct implementation is a single, mandatory validation gate at the service layer entry point, placed in a location that application developers can read, understand, and verify through code review. The weakness of application-layer enforcement is that it requires the gate to be called; the corresponding strength is that the presence or absence of the call is visible. Invisible enforcement — RLS policies, AOP interceptors — does not fail at development time when forgotten; it fails silently in production.

## Related

- [ADR-006](./ADR-006-user-identity-model.md) — users are global identities; this ADR governs how a global user's access to a specific business's data is validated
- [ADR-003](./ADR-003-hybrid-audit-strategy.md) — the three-layer audit architecture; every cross-resource action captures both actor identity and business scope
- [ADR-018](./ADR-018-jwt-refresh-token-rotation.md) — JWT refresh token rotation; governs the token lifecycle from which business identity is derived, including reuse detection and total session revocation on compromise

## Source Code Reference

*Populated when source code is present (v0.3.0+).*

- `AuthorizationService.java` — the single isolation enforcement point; `validateBusinessAccess(businessId)`, `requireOwner(businessId)`, `requireEmployee(businessId)`, `requireAdmin()` — all service entry points for business-scoped operations call one of these methods first
- `JwtAuthenticationFilter.java` — extracts authenticated `userId` and role from the JWT on every request; populates the `SecurityContext` used by `AuthorizationService`
- `BusinessService.java`, `AppointmentService.java`, `CustomerService.java`, etc. — all service entry points call `AuthorizationService` before any data operation; the call is the first statement after method signature and logging
- `SecurityIntegrationTest` — integration tests verifying that authenticated requests for Business A are rejected when they attempt to access Business B's data; covers all role-level variants
- `AuthFlowIntegrationTest` 