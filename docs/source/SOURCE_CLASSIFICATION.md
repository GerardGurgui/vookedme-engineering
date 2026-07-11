# Source Classification
## vookedme-engineering — Source Code Journey

**Version:** 1.0  
**Date:** 2026-07-11  
**Status:** Canonical — editorial taxonomy for all published source artefacts

---

## Purpose

Every source artefact published in this repository belongs to one of the categories defined in this document. The classification determines where the artefact sits in the reading order, what quality level is expected, and what publication criteria it must meet.

The classification system mirrors the ADR Journey's FOUNDATIONAL → CORE → ADVANCED → REFERENCE progression, but adapted for source code. A source artefact's classification reflects its position in the architectural dependency chain — not its technical complexity, and not the effort required to sanitise it.

---

## Classification Reference

---

### FOUNDATIONAL

**What it means:** A FOUNDATIONAL source artefact is load-bearing. Every other published source artefact either depends on it directly or operates within the context it establishes. A reader who has not read the FOUNDATIONAL artefacts cannot make full sense of anything else in the repository.

**Publication criteria:**
- The artefact must be structural — it defines invariants, boundaries, or contracts that every other component respects
- The artefact must be self-contained — a reader with Java and Spring Boot familiarity can understand it without prior knowledge of the system
- The artefact must have no optional reading prerequisites — it is always the starting point

**Expected quality level:** The highest standard. FOUNDATIONAL source is the first impression of the codebase. It must be clean, its comments translated and coherent, its annotations immediately legible. Any ambiguity in a FOUNDATIONAL class suggests an ambiguity in the architecture.

**Typical examples:**
- `AuthorizationService` — the tenant isolation gate; every protected operation passes through this class
- `Appointment` entity — the central domain object; every other domain component writes to, reads from, or reacts to an appointment
- `AppointmentStatus` — the six-state FSM enum; every status transition in the system is constrained by this type
- `AppointmentSource` — the write-once origin enum; the ADR-007 derive principle made visible in a field declaration
- `BaseEntity` — the shared audit base; establishes the `id` / `createdAt` / `updatedAt` contract for every entity

**Reading position:** Published first, referenced often.

---

### CORE DOMAIN

**What it means:** A CORE DOMAIN source artefact implements the primary business logic — the state machine, the event system, the assignment engine, the audit trail. These artefacts are not structural in the sense that FOUNDATIONAL artefacts are, but they are what the system *does*.

**Publication criteria:**
- The artefact must implement a business rule or domain invariant that is described in a published ADR
- The artefact must be publishable without requiring unpublished context from excluded files
- The artefact may require reading FOUNDATIONAL artefacts first, but requires no further prerequisites

**Expected quality level:** High. CORE DOMAIN source demonstrates the engineering decisions captured in the ADRs. Comments must be accurate, the relationship to the relevant ADR must be navigable via the "Source Code Reference" section.

**Typical examples:**
- `AppointmentEvent` — the event carrying mutation context to all downstream listeners
- `AppointmentAuditLog` + `AppointmentAuditListener` — the "committed ⟺ audited" invariant; ADR-003 implemented
- `EventActor` + `SourceActor` — the actor attribution model
- `RefreshToken` + `RefreshTokenService` — token rotation with reuse detection; ADR-018 implemented
- `BlockedSlot` + `BlockedSlotStatus` + `BlockedSlotPolicy` — the blocked-slot FSM; ADR-002 implemented

**Reading position:** Published in the first two batches, after FOUNDATIONAL artefacts are in place.

---

### SECURITY

**What it means:** A SECURITY source artefact implements a security control — authentication, authorisation, request validation, rate limiting, or defence-in-depth. Security artefacts are published as a group because their engineering value is highest when read together: each control addresses a different threat, and the composition of controls is itself the security architecture.

**Publication criteria:**
- The artefact must implement a security control that is either described in a published ADR or evident from its class name and responsibility
- The artefact must contain no credential values, endpoint URLs, or production identifiers in string literals
- The artefact must be free of references to the production deployment environment

**Expected quality level:** Precise and complete. Security artefacts are read by engineers evaluating security decisions. Ambiguity or incomplete comments undermine the artefact's value as evidence. Every non-obvious choice (constant-time comparison, body replay for HMAC, generic 401 on all failure paths) must be commented.

**Typical examples:**
- `WebhookSignatureFilter` — HMAC-SHA256 validation with `CachedBodyHttpServletRequest` for body replay
- `WebhookApiKeyFilter` — API key constant-time validation; defence in depth
- `TurnCorrelationFilter` — turn ID binding to MDC; forensic correlation without persistent thread state
- `JwtAuthenticationFilter` — JWT extraction and validation in the filter chain
- `LegalAcceptanceFilter` — consent enforcement as a filter, not a service-layer check
- `RateLimitingFilter` — in-memory sliding-window rate limiter with explicit single-instance scope note
- `ConsentService` — GDPR consent recording with explicit UTC timestamp (not wall-clock)
- `JvmTimezoneInvariant` — startup guard encoding an architectural liability as a runtime invariant

**Reading position:** Published together in a dedicated batch (SC-5) after the domain model and event system are established. Security artefacts reference the domain model; they make most sense when the domain is already familiar.

---

### BOT DOMAIN

**What it means:** A BOT DOMAIN source artefact implements the bot-specific layer of the system — the derive architecture (ADR-007), the narrative rendering pipeline, the privacy masking, and the observability correlation. The distinguishing characteristic of the bot domain is its purity: the core artefacts are pure functions operating on appointment state, not stateful services with their own storage.

**Publication criteria:**
- The artefact must implement the derive principle (state derived from appointments, not maintained independently) or a bot-specific engineering pattern
- Pure-function artefacts must preserve their purity guarantees in the published version — the annotations documenting the purity contract are not optional
- Narrative output strings that are locale-specific must be clearly annotated as locale output, not documentation

**Expected quality level:** The purity guarantees documented in class comments are the editorial centrepiece of the bot domain artefacts. The comments must remain accurate and complete. Dropping the purity documentation to simplify sanitisation is not acceptable — the documentation *is* the engineering value.

**Typical examples:**
- `BotEventResolver` — pure derive function: `Appointment` state → `List<BotEvent>`
- `BotNarrativeRenderer` — pure renderer: `BotEvent` + `BotNarrativeContext` → locale string
- `BotPhoneMaskingService` — pure utility: E.164 phone → masked display string
- `BotEvent`, `BotEventType`, `BotNarrativeContext` — domain types for the derive pipeline
- `BotEventService` — orchestrator; loads appointments, applies filters, resolves actor names

**Reading position:** Published in Batch SC-3, after FOUNDATIONAL and CORE DOMAIN artefacts. The derive principle makes most sense once the reader understands the appointment domain.

---

### PRIVACY INFRASTRUCTURE

**What it means:** A PRIVACY INFRASTRUCTURE source artefact implements the consent and legitimacy model described in ADR-013 and ADR-015. These artefacts are published as a group because their value is in the composition: the gate, the state machine, the transactional writer, the audit log, and the architecture guard tests together constitute a verifiable claim that privacy is enforced architecturally.

**Publication criteria:**
- The artefact must implement a component of the consent or legitimacy model
- Architecture guard tests (ArchUnit-based) that enforce legitimation invariants are classified here and are mandatory alongside the infrastructure they guard
- Field names must be consistent with the translation established in ADR-013 (`channelLegitimacyStatus`, not the internal column name)

**Expected quality level:** Highest scrutiny. Privacy artefacts are the most sensitive material in the repository. The published version must be correct — not just sanitised, but accurate in its representation of what the system does. If the published code and the published ADR-013 are inconsistent, one of them is wrong.

**Typical examples:**
- `OutboundLegitimacyGate` — fresh-read per customer, default-deny, feature-flag gated
- `CustomerLegitimacyService` — the legitimation state machine
- `LegitimacyTransactionalWriter` — atomically writes status update and audit row
- `CustomerLegitimationAuditLog` — metadata-only consent record (no content)
- `LegitimacyDecision` — the gate's return value: `ALLOW` or `DENY` with reason
- `LegitimationArchitectureGuardTest` — ArchUnit guard preventing re-introduction of bypassed paths
- `CustomerLegitimacyServiceIT`, `LegitimationEnforcementE2EIT` — integration tests specifying legitimation behaviour

**Reading position:** Published in Batch SC-4, before the security infrastructure batch. The consent model and the security model are related but distinct; consent is part of the domain, security is part of the infrastructure.

---

### OBSERVABILITY

**What it means:** An OBSERVABILITY source artefact implements instrumentation, correlation, or diagnostic capability — the infrastructure that makes the system's behaviour visible without altering it. Observability artefacts are distinct from security artefacts even when they share a filter chain: security filters enforce invariants; observability filters record execution context.

**Publication criteria:**
- The artefact must implement a cross-cutting observability concern (MDC correlation, Sentry PII scrubbing, actuator contributors)
- The artefact must be free of production-specific configuration values
- The engineering reasoning behind the implementation choice (why MDC rather than `@RequestScope`, why `@PostConstruct` rather than a configuration property) must be preserved in comments

**Expected quality level:** High clarity on the "why." Observability artefacts frequently contain non-obvious implementation choices driven by runtime constraints (thread model, filter ordering, `RequestContextHolder` availability). The class comment is often the most important documentation in the file.

**Typical examples:**
- `TurnContext` — MDC-based turn correlation; includes explanation of why MDC was chosen over `@RequestScope`
- `SentryBeforeSendCallback` — PII scrubbing before Sentry transmission
- `AppointmentMetrics` — Micrometer counters
- `ObservabilityHelper` — shared observability utility

**Reading position:** Published alongside the relevant domain artefact. `TurnContext` is published with `TurnCorrelationFilter` in Batch SC-2; `SentryBeforeSendCallback` is published in Batch SC-5.

---

### TESTING

**What it means:** A TESTING source artefact is an integration test, unit test, or architecture guard test that specifies a domain invariant, security contract, or architectural constraint. Tests are first-class artefacts in this repository — they are published not as supplementary material but as the specification of the system's behaviour.

**Publication criteria:**
- The test must be specifying a domain invariant, security contract, or architectural constraint that cannot be expressed as clearly in prose
- Integration tests must use real infrastructure (Testcontainers with PostgreSQL) — no mocked database tests are classified here; those are unit tests
- The test must be self-contained: its purpose must be legible from its class name and test method names without reading the production code first
- Tests that exist primarily for coverage rather than specification are not published

**Expected quality level:** Test method names are the specification. A test named `assertIdempotentBotWebhookDoesNotCreateDuplicate` is a specification statement. A test named `test3` is not publishable. All test method names in published tests must express their invariant in English.

**Typical examples:**
- `AppointmentConcurrencyIntegrationTest` — optimistic locking under concurrent slot booking
- `BotWebhookIdempotencyIT` — webhook event ID prevents duplicate appointment creation
- `TemporalBoundaryIT` — appointments cannot transition after their datetime
- `LegitimationArchitectureGuardTest` — ArchUnit: legitimation bypass paths cannot be re-introduced
- `BotNotesMinimizationIT` — GDPR minimisation: 14 test cases for note handling
- `WebhookSignatureFilterIT` — HMAC validation rejects invalid signatures
- `PhoneRaceIT` — race condition: two concurrent registrations for the same phone number
- `AuthFlowIntegrationTest` — JWT issuance, refresh rotation, and reuse detection

**Reading position:** Published alongside the production artefacts they specify. Architecture guard tests are published in Batch SC-4 alongside the legitimation infrastructure they guard.

---

### UTILITY

**What it means:** A UTILITY source artefact provides a general-purpose function with no domain-specific context — phone normalisation, email validation, exception types. Utility artefacts have the broadest applicability outside this codebase and the lowest sanitisation cost.

**Publication criteria:**
- The artefact must have no domain-specific logic — it must be directly reusable in any Java/Spring application
- The artefact must not depend on any business entity or domain service
- If the artefact's implementation has a non-obvious constraint (minimum input length, E.164-only input, null-safe behaviour), that constraint must be documented

**Expected quality level:** Concise and accurate. Utility artefacts should need no explanation beyond their method signatures and a brief class comment. If a utility artefact requires extensive documentation to be used correctly, it may need to be redesigned before publication.

**Typical examples:**
- `PhoneNormalizer` — E.164 normalisation for phone inputs
- `EmailUniquenessValidator`, `PhoneUniquenessValidator` — custom constraint validators
- `BotPhoneMaskingService` — E.164 masking for display (classified here as well as BOT DOMAIN because of its general utility)
- `BaseEntity` — shared entity base (classified here as well as FOUNDATIONAL because of its structural role)
- Exception types (`BadRequestException`, `ConflictException`, `ForbiddenException`, `ResourceNotFoundException`, `UnauthorizedException`)

**Reading position:** Published with the first relevant batch that references them. Exception types are published with the FOUNDATIONAL batch; utility validators are published with the domain artefacts they validate.

---

## Publication Type Reference

Every published source artefact also carries a **publication type** distinct from its editorial classification. The editorial classification describes where the artefact sits in the reading order. The publication type describes what transformation was applied during migration.

| Type | Meaning |
|---|---|
| SANITISED | Original class published with internal references removed, comments translated to English, package namespace updated |
| ANNOTATED | Original class published with sanitisation plus explanatory inline comments added for clarity |
| TRANSLATED | Original class with substantial Spanish-language comments; translation is the primary migration effort |
| PRIVATE | Not published; content may appear in Case Studies or Engineering Investigations |

---

## Classification Summary Table

This table will be populated as each batch is planned and executed. It serves as the canonical record of what has been classified and what publication type applies.

| Class | Package | Editorial | Publication Type | Batch |
|---|---|---|---|---|
| `AuthorizationService` | auth | FOUNDATIONAL | TRANSLATED | SC-1 |
| `Appointment` | appointment | FOUNDATIONAL | TRANSLATED | SC-1 |
| `AppointmentStatus` | appointment | FOUNDATIONAL | SANITISED | SC-1 |
| `AppointmentSource` | appointment | FOUNDATIONAL | SANITISED | SC-1 |
| `BaseEntity` | common | FOUNDATIONAL | SANITISED | SC-1 |
| `EventActor` | common | FOUNDATIONAL | SANITISED | SC-1 |
| `SourceActor` | common | FOUNDATIONAL | SANITISED | SC-1 |
| `WebhookSignatureFilter` | webhook | SECURITY | SANITISED | SC-1 |
| `WebhookApiKeyFilter` | webhook | SECURITY | SANITISED | SC-1 |
| `RefreshToken` | auth | CORE DOMAIN | SANITISED | SC-1 |
| `RefreshTokenService` | auth | CORE DOMAIN | TRANSLATED | SC-1 |
| `AppointmentConcurrencyIntegrationTest` | appointment | TESTING | SANITISED | SC-1 |
| `AuthFlowIntegrationTest` | auth | TESTING | SANITISED | SC-1 |
| `WebhookSignatureFilterIT` | webhook | TESTING | SANITISED | SC-1 |
| `AppointmentEvent` | common | CORE DOMAIN | ANNOTATED | SC-2 |
| `AppointmentAuditLog` | appointment | CORE DOMAIN | SANITISED | SC-2 |
| `AppointmentAuditListener` | appointment | CORE DOMAIN | TRANSLATED | SC-2 |
| `TurnContext` | config | OBSERVABILITY | TRANSLATED | SC-2 |
| `TurnCorrelationFilter` | webhook | OBSERVABILITY | TRANSLATED | SC-2 |
| `AppointmentAuditFlowIT` | appointment | TESTING | SANITISED | SC-2 |
| `BotWebhookIdempotencyIT` | webhook | TESTING | SANITISED | SC-2 |
| `TurnCorrelationIT` | webhook | TESTING | SANITISED | SC-2 |
| `BotEventResolver` | bot | BOT DOMAIN | TRANSLATED | SC-3 |
| `BotNarrativeRenderer` | bot | BOT DOMAIN | ANNOTATED | SC-3 |
| `BotPhoneMaskingService` | bot | BOT DOMAIN | SANITISED | SC-3 |
| `BotEvent` | bot | BOT DOMAIN | SANITISED | SC-3 |
| `BotEventType` | bot | BOT DOMAIN | SANITISED | SC-3 |
| `BotNarrativeContext` | bot | BOT DOMAIN | SANITISED | SC-3 |
| `BotEventResolverTest` | bot | TESTING | SANITISED | SC-3 |
| `BotNarrativeRendererTest` | bot | TESTING | ANNOTATED | SC-3 |
| `BotPhoneMaskingServiceTest` | bot | TESTING | SANITISED | SC-3 |
| `BotRecentRelevantReadIT` | bot | TESTING | SANITISED | SC-3 |
| `OutboundLegitimacyGate` | customer | PRIVACY INFRASTRUCTURE | SANITISED | SC-4 |
| `CustomerLegitimacyService` | customer | PRIVACY INFRASTRUCTURE | TRANSLATED | SC-4 |
| `LegitimacyTransactionalWriter` | customer | PRIVACY INFRASTRUCTURE | TRANSLATED | SC-4 |
| `CustomerLegitimationAuditLog` | customer | PRIVACY INFRASTRUCTURE | SANITISED | SC-4 |
| `LegitimacyDecision` | customer | PRIVACY INFRASTRUCTURE | SANITISED | SC-4 |
| `LegitimationArchitectureGuardTest` | customer | TESTING | SANITISED | SC-4 |
| `LegitimationWiringGuardTest` | customer | TESTING | SANITISED | SC-4 |
| `CustomerLegitimacyServiceIT` | customer | TESTING | SANITISED | SC-4 |
| `LegitimationEnforcementE2EIT` | customer | TESTING | SANITISED | SC-4 |
| `RateLimitingFilter` | security | SECURITY | TRANSLATED | SC-5 |
| `JwtAuthenticationFilter` | auth | SECURITY | SANITISED | SC-5 |
| `LegalAcceptanceFilter` | auth | SECURITY | SANITISED | SC-5 |
| `ConsentService` | auth | SECURITY | TRANSLATED | SC-5 |
| `ConsentAudit` | auth | CORE DOMAIN | SANITISED | SC-5 |
| `JvmTimezoneInvariant` | config | SECURITY | TRANSLATED | SC-5 |
| `SentryBeforeSendCallback` | config | OBSERVABILITY | SANITISED | SC-5 |
| `BotConsentEnforcementIT` | bot | TESTING | SANITISED | SC-5 |
| `WebhookDataMinimizationTest` | webhook | TESTING | SANITISED | SC-5 |
| `RateLimitingFilterTest` | security | TESTING | SANITISED | SC-5 |
| `SentryBeforeSendCallbackTest` | config | TESTING | SANITISED | SC-5 |
| `BlockedSlot` | schedule | CORE DOMAIN | SANITISED | SC-6 |
| `BlockedSlotStatus` | schedule | CORE DOMAIN | SANITISED | SC-6 |
| `BlockedSlotPolicy` | schedule | CORE DOMAIN | ANNOTATED | SC-6 |
| `TemporalBoundaryIT` | appointment | TESTING | SANITISED | SC-6 |
| `BotNotesMinimizationIT` | bot | TESTING | SANITISED | SC-6 |
| `AppointmentAuditLogConstraintsIT` | appointment | TESTING | SANITISED | SC-6 |
| `PhoneRaceIT` | customer | TESTING | SANITISED | SC-6 |
| `PhoneNormalizer` | common | UTILITY | SANITISED | SC-6 |
| `AppointmentService` | appointment | — | PRIVATE | — |
| `WebhookDebugController` | webhook | — | PRIVATE | — |
| `AssignmentExplainService` | appointment | — | PRIVATE | — |
| All configuration YML files | config | — | PRIVATE | — |
| Flyway migration files | — | — | PRIVATE | — |

*Rows marked PRIVATE will not be published. Rows without a Batch assignment will be assigned in a future planning revision.*
