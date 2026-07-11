# Source Code Journey

This directory contains the editorial framework for the Source Code Journey — the structured publication of the VookedMe backend Java source code.

The Source Code Journey follows the same editorial discipline as the ADR Journey: source artefacts are published in coherent batches, ordered by architectural significance, with every published file sanitised of internal identifiers and annotated with its architectural context.

---

## Status

> **v0.8.0 — Source Code Journey framework established.** Publication begins at SC-1 (v0.9.0).

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
| SC-1 | Structural Foundation | FOUNDATIONAL, SECURITY, CORE DOMAIN | Planned |
| SC-2 | Event System and Audit Trail | CORE DOMAIN, OBSERVABILITY, TESTING | Planned |
| SC-3 | Bot Domain | BOT DOMAIN, TESTING | Planned |
| SC-4 | Privacy Infrastructure | PRIVACY INFRASTRUCTURE, TESTING | Planned |
| SC-5 | Security Infrastructure | SECURITY, OBSERVABILITY, TESTING | Planned |
| SC-6 | Temporal Boundary and Concurrency | CORE DOMAIN, TESTING, UTILITY | Planned |

---

## Relationship to the ADR Journey

The ADR Journey is complete. Seventeen ADRs describe *why* the system is designed the way it is.

The Source Code Journey publishes the code that implements those decisions. Every published source batch populates the "Source Code Reference" sections of the relevant ADRs — converting them from standalone arguments into linked pairs of decision and implementation.

A reader of ADR-016 (Tenant Isolation) will be able to follow a link directly to `AuthorizationService`. A reader of ADR-013 (Customer Communication Policy) will be able to follow a link to `OutboundLegitimacyGate`. The ADR explains the reasoning; the source code is the evidence.

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

*Populated as batches are published.*

When SC-1 through SC-6 are published, a senior engineer reading the source for the first time should follow this path (approximately 45–60 minutes):

1. `AuthorizationService` — the tenant isolation model
2. `Appointment` entity — the central domain object
3. `AppointmentStatus`, `AppointmentSource` — the FSM state and origin types
4. `AppointmentEvent` — what information flows with a mutation
5. `AppointmentAuditListener` — committed ⟺ audited
6. `BotEventResolver` — pure derive function
7. `OutboundLegitimacyGate` — default-deny consent enforcement
8. `WebhookSignatureFilter` — HMAC-SHA256 with body replay
9. `RateLimitingFilter` — deliberate simplicity with explicit scope note
10. `JvmTimezoneInvariant` — an architectural liability as a startup guard
