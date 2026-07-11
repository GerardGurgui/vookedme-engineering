# Source Publication Audit
## vookedme-engineering — Source Code Journey

**Version:** 1.0  
**Date:** 2026-07-11  
**Status:** Canonical — foundation document for the Source Code Journey

---

## Purpose

This document is the executive summary of the engineering publication audit conducted before the Source Code Journey began. It records the publication philosophy, what will be published, what will never be published, and the principles that govern every decision in between.

The audit itself was a complete inventory of 257 main source files and 119 test files across 15 packages. This document captures its conclusions. The full package-by-package assessment informed the classification matrix and the publication plan; it is not reproduced here.

---

## Publication Philosophy

The Source Code Journey follows the same philosophical commitment as the ADR Journey: **publish engineering knowledge, not operational detail.**

The private codebase is production software. It contains years of design decisions, trade-offs, bug fixes, and hard-won solutions. It also contains internal identifiers, Spanish-language comments, operational configuration values, deployment-specific assumptions, and business-sensitive context that belongs to a private production system. These two things live in the same files. The editorial task is to separate them.

Every published source artefact will satisfy three tests:

**1. The transferability test.** Would this code still be valuable to a senior backend engineer if the product it came from were discontinued tomorrow? If the answer is no — if the value is entirely contextual — the artefact is a candidate for Case Study treatment rather than direct publication.

**2. The stranger test.** Could an engineer who has never heard of VookedMe read this class and understand what problem it solves, why it solves it this way, and what they would need to change to adapt it to their own system? If yes, it is ready for publication. If the code requires prior knowledge of internal documents, internal identifiers, or the private system's operational context, it is not ready.

**3. The sanitisation completeness test.** After sanitisation, does the class contain any reference — in code, comments, field names, or string literals — to internal documents, production identifiers, internal taxonomy codes, customer data, or deployment-specific values? If a single such reference remains, the artefact is not ready.

---

## What Will Be Published

The audit identified five categories of publishable content:

**Security infrastructure.** The webhook security stack — HMAC-SHA256 validation with body replay, API key constant-time comparison, turn correlation, rate limiting — is publishable with minimal sanitisation. These classes contain complete engineering reasoning with no business context. They are the most immediately transferable content in the repository.

**Domain model.** The appointment entity and its supporting types (`AppointmentStatus`, `AppointmentSource`) are the clearest expression of the domain model. They show optimistic locking, idempotency key design, FSM state representation, and write-once source attribution in a single class. Every architectural choice is legible in the field declarations.

**Tenant isolation.** `AuthorizationService` is the structural guarantee of the multi-tenant model. It is short relative to what it does, and what it does is completely expressed in the class itself — no external context required. Publishing it alongside ADR-016 closes the loop between decision and implementation.

**Pure functions.** The bot domain contains three classes that are pure functions by design and by enforcement: `BotEventResolver` (derive bot events from appointment state), `BotNarrativeRenderer` (render narrative strings from events), `BotPhoneMaskingService` (mask E.164 phone numbers for display). These classes have explicit purity guarantees documented in their class comments, no business context, and unit tests that verify deterministic behaviour.

**Event system.** `AppointmentEvent` and its listener network show how state changes propagate through a Spring application within a single transaction. `AppointmentAuditListener` demonstrates the "committed ⟺ audited" invariant — the forensic audit row and the mutation commit or rollback together.

**Consent and legitimacy infrastructure.** `OutboundLegitimacyGate`, `CustomerLegitimacyService`, and `LegitimacyTransactionalWriter` are the architectural implementation of ADR-013. They are publishable because the legitimation domain was designed with structural separation from business context — the gate enforces a consent invariant that is general, not specific to any particular product policy.

**Architecture guard tests.** `LegitimationArchitectureGuardTest` and its siblings use ArchUnit to verify that architectural invariants cannot be violated silently. Publishing these alongside the legitimation infrastructure demonstrates that the privacy constraint is enforced at the architecture level, not just by convention.

**Infrastructure patterns.** `JvmTimezoneInvariant` encodes an architectural liability as a startup guard. `RateLimitingFilter` implements sliding-window rate limiting with an explicit note about its single-instance scope. Both are publishable because their engineering reasoning is fully self-contained and generalisable.

---

## What Will Never Be Published

The following content is permanently excluded from publication. The exclusion is unconditional — no sanitisation effort makes these publishable.

**Production configuration.** `application-prod.yml`, `application.yml`, and all profile-specific configuration files contain service URLs, Easypanel deployment references, and operational parameters specific to the production system. The `.env.example` (if created for publication) will be a generic template, not a sanitised version of the production file.

**`AppointmentService` in full.** At 5352 lines, this class is the core business logic engine. It contains Spanish comments throughout, references to internal design documents embedded in method comments, business-specific logic in shared paths, and internal identifier taxonomy that cannot be stripped without destroying the class's coherence. It is Case Study source material — the engineering problems it solves will be narrated in case studies, not exposed as raw code.

**Flyway migration history.** The 78 migration files collectively map the complete structural evolution of the schema, including internal version references, task identifiers, and migration names that correspond to internal planning artefacts. The migration count (78) and the evolution story are publishable as narrative; the files themselves are not.

**Internal documentation cross-references.** Any comment that references an internal document by name (design documents, internal audit reports, internal planning documents) is stripped during sanitisation. These references cannot be replaced with abstract equivalents — the reference either disappears or the class is not published.

**Operational health infrastructure.** `ExternalServicesHealthIndicator` and `HealthCheckRestTemplate` contain external service URLs configured for the production deployment. The pattern is publishable in an Engineering Investigation; the implementation is not.

**Deployment configuration.** `CorsConfig`, `ReminderProperties`, and similar configuration-binding classes contain production domain names and operational thresholds that belong to the private system.

**The debug endpoint and its service.** `WebhookDebugController` and `AssignmentExplainService` are temporary validation-phase artefacts. They will be excluded until the validation phase concludes and the debug infrastructure is either promoted to production or removed.

---

## Sanitisation Strategy

Every published source file passes through the following sanitisation process before any publication decision is made:

**Language.** All Spanish-language comments are translated to English. Spanish-language string literals that constitute user-facing output (e.g. notification text, narrative strings) are annotated to indicate they are locale-specific output, not documentation.

**Internal references.** Every reference to an internal document, internal identifier, task code, or internal classification code is removed. If the comment's engineering value depends on the internal reference, the engineering point is restated without it.

**Sensitive string literals.** Service URLs, API endpoint paths, domain names, and operational identifiers in string literals are replaced with generic descriptive equivalents or removed if they serve only documentation purposes.

**Package namespace.** The private package namespace (`com.botmanager.api`) is replaced with the public namespace (`com.vookedme.botmanager`). This is applied uniformly across all published files.

**Class comments.** Comments that cross-reference to other classes by the private class name are updated to use the public name. Comments that reference internal governance sections (e.g. `AGENTS.md §21`, `INV #52`) are removed; the engineering point they were annotating is retained if expressible without the reference.

---

## Architectural Priorities

The Source Code Journey publishes in order of architectural significance. This is the same principle that governed the ADR Journey's FOUNDATIONAL → CORE → ADVANCED → REFERENCE progression.

**First priority: the load-bearing structure.** `AuthorizationService`, the appointment entity cluster, and the event backbone are the structural foundation of the system. Publishing them first gives every subsequent artefact a frame of reference.

**Second priority: the event propagation model.** `AppointmentEvent`, `AppointmentAuditListener`, and the listener network show how the system behaves after a mutation — the fan-out, the transactional integrity, the actor attribution. This is the connective tissue between the domain model and the observability layer.

**Third priority: the bot domain purity pattern.** The three pure-function classes in the bot domain are a design pattern worth isolating and naming. They are published as a group because their value is in the contrast — three classes with identical purity guarantees in a Spring application that otherwise uses dependency injection throughout.

**Fourth priority: the privacy infrastructure.** The legitimation gate and its surrounding infrastructure are published together with the architecture guard tests that enforce them. Publishing the tests alongside the implementation is not optional — the guard tests are the mechanism by which the privacy constraint becomes verifiable.

**Fifth priority: the security stack.** The webhook security filters and the JWT infrastructure are publishable with the least sanitisation effort and the broadest applicability. They are placed in Batch 5 not because they are less important, but because they are most useful to readers who already have the domain model as context.

---

## Engineering Goals

The Source Code Journey serves three engineering goals:

**Close the ADR loop.** Seventeen ADRs describe why the system is designed the way it is. The Source Code Journey publishes the code that implements those decisions. A reader of ADR-016 (Tenant Isolation) should be able to follow the "Source Code Reference" link at the bottom of the ADR and immediately find `AuthorizationService`. A reader of ADR-013 (Customer Communication Policy) should be able to follow the link and find `OutboundLegitimacyGate`. The code and the decision document become a single navigable unit.

**Demonstrate engineering, not just claims.** It is straightforward to write an ADR saying "we enforce privacy architecturally." It is more credible to show `OutboundLegitimacyGate` — forty lines of code where every communications path is gated, where the default is deny, where the legitimacy status is fresh-read on every call rather than cached. The source code is evidence. The ADR is argument. Together they constitute a full engineering statement.

**Make the test suite the specification.** The integration tests — particularly `LegitimationArchitectureGuardTest`, `BotWebhookIdempotencyIT`, `AppointmentConcurrencyIntegrationTest`, and `TemporalBoundaryIT` — are the most precise specification of the system's invariants. Publishing them transforms the repository from "this engineer claims the system behaves this way" to "these tests verify that the system behaves this way."

---

## What This Audit Excludes

The Source Code Journey does not cover:

- Architecture documentation (handled by the Architecture Journey, planned separately)
- Governance documents (planned separately as the Governance Journey)
- Case Studies (planned separately; several case studies are sourced from the private `AppointmentService`)
- Engineering Investigations (planned separately; specific engineering problems will be narrated without requiring the full source class)

These journeys proceed independently. The Source Code Journey creates the foundation — published source artefacts — that the Case Studies and Engineering Investigations will reference.

---

*This audit document is version-stable. It reflects the state of the private codebase as assessed during the pre-Source Journey audit (2026-07-11) and will not be updated as individual batches are published.*
