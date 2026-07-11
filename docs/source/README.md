# Source Code Journey

This directory contains the editorial framework for the Source Code Journey — the structured publication of the VookedMe backend Java source code.

The Source Code Journey follows the same editorial discipline as the ADR Journey: source artefacts are published in coherent batches, ordered by architectural significance, with every published file sanitised of internal identifiers and translated to natural technical English.

---

## Status

> **v0.9.0 — SC-1 published.** Eleven production artefacts and three integration tests are live. The architectural backbone of the system — tenant isolation, the appointment domain entity, the six-state FSM, JWT refresh rotation, webhook security, and the event actor model — is now readable.

---

## Framework Documents

| Document | Purpose |
|---|---|
| [SOURCE_PUBLICATION_AUDIT.md](./SOURCE_PUBLICATION_AUDIT.md) | Publication philosophy, what will be published, what will never be published, sanitisation strategy |
| [SOURCE_CLASSIFICATION.md](./SOURCE_CLASSIFICATION.md) | Editorial taxonomy — classification categories, publication criteria, classification table |
| [SOURCE_PUBLICATION_PLAN.md](./SOURCE_PUBLICATION_PLAN.md) | Publication roadmap — six batches, each with objective, artefacts, dependencies, and sanitisation effort |

---

## Publication Roadmap

| Batch | Name | Classification Focus | Status |
|---|---|---|---|
| SC-1 | Structural Foundation | FOUNDATIONAL, SECURITY, CORE DOMAIN | **Published (v0.9.0)** |
| SC-2 | Event System and Audit Trail | CORE DOMAIN, OBSERVABILITY, TESTING | Planned |
| SC-3 | Bot Domain | BOT DOMAIN, TESTING | Planned |
| SC-4 | Privacy Infrastructure | PRIVACY INFRASTRUCTURE, TESTING | Planned |
| SC-5 | Security Infrastructure | SECURITY, OBSERVABILITY, TESTING | Planned |
| SC-6 | Temporal Boundary and Concurrency | CORE DOMAIN, TESTING, UTILITY | Planned |

---

## SC-1 Artefacts

**Production source** (`src/main/java/com/vookedme/botmanager/`):

| Artefact | Package | Editorial Category |
|---|---|---|
| `BaseEntity.java` | `common/entity` | FOUNDATIONAL |
| `SourceActor.java` | `common/event` | FOUNDATIONAL |
| `EventActor.java` | `common/event` | FOUNDATIONAL |
| `AppointmentStatus.java` | `appointment/entity` | CORE DOMAIN |
| `AppointmentSource.java` | `appointment/entity` | CORE DOMAIN |
| `ApprovalDecisionSource.java` | `appointment/entity` | CORE DOMAIN |
| `PaymentMethod.java` | `appointment/entity` | CORE DOMAIN |
| `Appointment.java` | `appointment/entity` | CORE DOMAIN |
| `RefreshToken.java` | `auth/entity` | SECURITY |
| `RefreshTokenService.java` | `auth/service` | SECURITY |
| `AuthorizationService.java` | `auth/service` | FOUNDATIONAL |
| `WebhookSignatureFilter.java` | `webhook/security` | SECURITY |
| `WebhookApiKeyFilter.java` | `webhook/security` | SECURITY |

**Integration tests** (`src/test/java/com/vookedme/botmanager/`):

| Artefact | Package | What it verifies |
|---|---|---|
| `AppointmentConcurrencyIntegrationTest.java` | `appointment` | Partial unique index prevents double-booking under concurrent inserts |
| `AuthFlowIntegrationTest.java` | `auth` | Full login → refresh → logout flow against real PostgreSQL |
| `WebhookSignatureFilterIT.java` | `webhook` | HMAC validation: GET bypass, POST rejection without HMAC, POST acceptance with correct HMAC |

---

## Relationship to the ADR Journey

The ADR Journey is complete. Seventeen ADRs describe *why* the system is designed the way it is.

The Source Code Journey publishes the code that implements those decisions. Every published source batch populates the "Source Code Reference" sections of the relevant ADRs — converting them from standalone arguments into linked pairs of decision and implementation.

A reader of ADR-016 (Tenant Isolation) can now follow the reasoning directly into `AuthorizationService`. A reader of ADR-017 (Appointment FSM) can read the `Appointment` entity and `AppointmentStatus` enum. A reader of ADR-018 (JWT Refresh Rotation) can read `RefreshToken` and `RefreshTokenService`.

---

## Editorial Categories

Source artefacts are published under one of seven editorial categories. Full definitions are in [SOURCE_CLASSIFICATION.md](./SOURCE_CLASSIFICATION.md).

- **FOUNDATIONAL** — load-bearing structure; every other artefact depends on these
- **CORE DOMAIN** — primary business logic and domain invariants
- **SECURITY** — security controls across the request lifecycle
- **BOT DOMAIN** — the derive architecture and pure-function pattern
- **PRIVACY INFRASTRUCTURE** — consent enforcement and architecture guard tests
- **OBSERVABILITY** — instrumentation, correlation, and diagnostic tooling
- **TESTING** — integration tests and architecture guard tests as specification
- **UTILITY** — general-purpose utilities with no domain-specific context

---

## Reading Path

A senior engineer reading the source for the first time should follow this path (approximately 30–45 minutes for SC-1 alone; 60 minutes when all batches are published):

**SC-1 (now available):**

1. `AuthorizationService` — the tenant isolation gate; seven guard methods; every service entry point calls one before touching data
2. `Appointment` entity — the central domain object; 6-state FSM, idempotency key, bot approval audit, temporal boundary helper
3. `AppointmentStatus`, `AppointmentSource` — FSM states and typed origin classification
4. `EventActor`, `SourceActor` — the actor model for audit attribution
5. `WebhookSignatureFilter` — HMAC-SHA256 with body replay (CachedBodyHttpServletRequest); defence-in-depth composition with WebhookApiKeyFilter
6. `RefreshToken`, `RefreshTokenService` — rotation with reuse detection; total revocation on compromise

**SC-2 and beyond (planned):**

7. `AppointmentEvent` — what information flows with a mutation
8. `AppointmentAuditListener` — committed ⟺ audited
9. `BotEventResolver` — pure derive function
10. `OutboundLegitimacyGate` — default-deny consent enforcement
11. `RateLimitingFilter` — deliberate simplicity with explicit scope note
12. `JvmTimezoneInvariant` — an architectural liability as a startup guard
