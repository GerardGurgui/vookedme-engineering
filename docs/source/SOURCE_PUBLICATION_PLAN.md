# Source Publication Plan
## vookedme-engineering — Source Code Journey

**Version:** 1.0  
**Date:** 2026-07-11  
**Status:** Canonical — publication roadmap for the Source Code Journey

---

## Overview

The Source Code Journey publishes the Java backend source code in six editorial batches. The batches are ordered by architectural dependency: each batch assumes the reader has already read the previous ones, and each published artefact can reference already-published artefacts in its cross-links.

The order is not determined by technical complexity or sanitisation effort. It is determined by the same principle that governed the ADR Journey: **FOUNDATIONAL artefacts before CORE, CORE before domain-specific, domain-specific before infrastructure.**

---

## Source Batch SC-1 — Structural Foundation

**Target release:** v0.9.0  
**Editorial classifications:** FOUNDATIONAL, SECURITY (webhook), CORE DOMAIN (auth)

### Objective

Establish the three structural pillars of the system: the tenant isolation model, the appointment domain object, and the webhook security stack. A reader who completes this batch will understand what the system is, how it isolates tenants, what its central domain object contains, and how webhook requests are authenticated before they reach business logic.

This batch answers the question: *what are the invariants that everything else must respect?*

### Engineering Narrative

The tenant isolation model is not a database feature — it is enforced at the application layer on every operation. `AuthorizationService` is the control plane: seven guard methods, each enforcing a specific permission boundary, each called from the service layer before any mutation. The model is legible in the guards: `checkBusinessAccess` (is this user a member of this business?), `checkOwnerOrAdmin` (does this operation require elevated authority?), `checkSelfOrOwnerOrAdmin` (can this user act on their own record, or only an elevated actor?). Reading all seven guards takes less than ten minutes and gives a complete picture of the multi-tenant permission model.

The appointment entity is the central domain object. It is not a simple data container — the field declarations are a record of architectural decisions. `@Version Long version` is the concurrency model (optimistic locking, not pessimistic). `String webhookEventId` is the idempotency model (unique per business, null for non-webhook sources). `AppointmentSource source` is the derive principle (write-once, set at creation, never mutable). `AppointmentStatus status` is the FSM state. Reading the entity with the ADRs as context — specifically ADR-017, ADR-007, and ADR-011 — converts a field listing into an architectural statement.

The webhook security stack demonstrates defence in depth. `WebhookApiKeyFilter` and `WebhookSignatureFilter` are two independent security controls on the same request path. The API key filter is fast and simple — constant-time string comparison, generic 401 on all failure paths. The signature filter is more complex — it must consume the request body for HMAC computation, then make it available to downstream handlers. `CachedBodyHttpServletRequest` is the engineering solution to that problem: the body is read once, stored in memory, and a new `InputStream` is provided on each subsequent read. The pattern is general; the implementation is complete.

### Artefacts

**Production source:**
- `auth/service/AuthorizationService.java` — tenant isolation gate (FOUNDATIONAL, TRANSLATED)
- `appointment/entity/Appointment.java` — central domain object (FOUNDATIONAL, TRANSLATED)
- `appointment/entity/AppointmentStatus.java` — six-state FSM enum (FOUNDATIONAL, SANITISED)
- `appointment/entity/AppointmentSource.java` — write-once origin enum (FOUNDATIONAL, SANITISED)
- `common/entity/BaseEntity.java` — shared entity base (FOUNDATIONAL, SANITISED)
- `common/event/EventActor.java` — actor model record (FOUNDATIONAL, SANITISED)
- `common/event/SourceActor.java` — actor type enum (FOUNDATIONAL, SANITISED)
- `webhook/security/WebhookSignatureFilter.java` — HMAC-SHA256 with body replay (SECURITY, SANITISED)
- `webhook/security/WebhookApiKeyFilter.java` — API key constant-time validation (SECURITY, SANITISED)
- `auth/entity/RefreshToken.java` — token entity with revocation model (CORE DOMAIN, SANITISED)
- `auth/service/RefreshTokenService.java` — rotation with reuse detection (CORE DOMAIN, TRANSLATED)

**Tests:**
- `AppointmentConcurrencyIntegrationTest.java` — optimistic locking under concurrent writes (TESTING, SANITISED)
- `AuthFlowIntegrationTest.java` — JWT issuance, rotation, and reuse detection (TESTING, SANITISED)
- `WebhookSignatureFilterIT.java` — HMAC validation contract (TESTING, SANITISED)

### Dependencies

None. SC-1 is the starting point for the Source Code Journey. Published ADRs it relates to: ADR-016 (tenant isolation → `AuthorizationService`), ADR-017 (appointment FSM → `Appointment`, `AppointmentStatus`), ADR-007 (derive architecture → `AppointmentSource`), ADR-018 (JWT rotation → `RefreshTokenService`).

### Sanitisation Effort

**Medium.** `AuthorizationService` contains Spanish-language comments throughout and requires full translation. `Appointment` contains Spanish-language field comments requiring translation. `RefreshTokenService` contains one Spanish comment (the rotation step) requiring translation. All other artefacts in this batch are in English and require only package namespace update and removal of internal cross-reference comments.

### Expected Repository Impact

The `src/main/java/com/vookedme/botmanager/` package structure is created. Eleven production classes and three test classes are published. All ADR "Source Code Reference" sections for ADR-016, ADR-017, ADR-007, and ADR-018 can be populated with working links. The `docs/source/README.md` index is updated to reflect SC-1 as published.

---

## Source Batch SC-2 — Event System and Audit Trail

**Target release:** v0.10.0  
**Editorial classifications:** CORE DOMAIN, OBSERVABILITY, TESTING

### Objective

Show how state changes in the appointment domain propagate to audit records, notification triggers, and observability metadata — all within the same database transaction. A reader who completes this batch will understand: what information flows with a mutation event, how the forensic audit log is written atomically with the mutation, and how conversational turn context is threaded through the request without relying on request-scoped beans.

This batch answers the question: *what happens after an appointment changes state?*

### Engineering Narrative

`AppointmentEvent` is one of the most information-dense classes in the codebase. It carries not just the mutation itself (`type`, `appointmentId`, `businessId`) but the context that allows every downstream listener to make decisions without re-loading the appointment: `triggeredBy` (who caused the mutation), `source` (was this a bot or panel action?), `previousStatus` (what state did the appointment leave?), `correlationId` (is this part of a bulk operation?), `turnId` (is this turn forensically traceable?). Each field was added at a specific point in the system's evolution to allow a specific listener to discriminate a specific path. The field evolution — documented in the class comment — is the history of the notification system's requirements.

`AppointmentAuditListener` is the forensic audit trail. It is a synchronous `@EventListener` (not `@TransactionalEventListener`) — it runs inside the same transaction as the mutation that published the event. The consequence is atomic: the audit row and the business mutation commit together or roll back together. A gap in the audit log cannot exist without a gap in the business data. The comment "committed ⟺ audited" is the invariant. It is not aspirational — it is enforced by the transaction model.

`TurnContext` is the observability companion to the conversational coherence model (ADR-012). It binds a turn identifier to the request thread via MDC — not via `@RequestScope` — because MDC is guaranteed to be available in the filter chain before the Spring request context is initialised, and the same thread that handles the HTTP filter chain handles the `@Transactional` method and the synchronous `@EventListener`. The design note in the class comment explains why the apparently simpler `@RequestScope` approach was rejected: the bean would be available in the controller but not necessarily in the filter, and a test that passes in the controller tier may fail silently in the filter tier. MDC has no such failure mode.

### Artefacts

**Production source:**
- `common/event/AppointmentEvent.java` — mutation event with full actor and correlation context (CORE DOMAIN, ANNOTATED)
- `appointment/entity/AppointmentAuditLog.java` — forensic audit row entity (CORE DOMAIN, SANITISED)
- `appointment/audit/AppointmentAuditListener.java` — atomic audit write in transaction (CORE DOMAIN, TRANSLATED)
- `config/observability/TurnContext.java` — MDC-based turn correlation (OBSERVABILITY, TRANSLATED)
- `webhook/security/TurnCorrelationFilter.java` — turn ID binding in filter chain (OBSERVABILITY, TRANSLATED)

**Tests:**
- `AppointmentAuditFlowIT.java` — end-to-end audit trail verification (TESTING, SANITISED)
- `BotWebhookIdempotencyIT.java` — idempotency key prevents duplicate creation (TESTING, SANITISED)
- `TurnCorrelationIT.java` — turn ID persisted to audit log on real bot turns (TESTING, SANITISED)

### Dependencies

SC-1 (`AppointmentEvent` references `SourceActor`; `AppointmentAuditListener` references `AppointmentEvent`; `TurnCorrelationFilter` is in the same request path as the webhook security filters published in SC-1).

### Sanitisation Effort

**Medium.** `AppointmentEvent` contains extensive Javadoc with internal cross-references (internal version numbers in the form "V65", "V60", "V-3a") that require removal, with the engineering reasoning they introduce retained. `AppointmentAuditListener` contains Spanish comments (primarily the description of the transactional model) requiring translation. `TurnContext` and `TurnCorrelationFilter` contain Spanish-language Javadoc requiring translation. `AppointmentAuditLog` is in English and requires only namespace update.

### Expected Repository Impact

Five production classes and three test classes published. ADR-003 "Source Code Reference" section can be populated. ADR-012 "Source Code Reference" section can be partially populated (the turn correlation component). The `src/` directory begins to show the event-driven architecture that connects the domain model to the observability layer.

---

## Source Batch SC-3 — Bot Domain

**Target release:** v0.11.0  
**Editorial classifications:** BOT DOMAIN, TESTING

### Objective

Publish the bot domain as a self-contained architectural unit. The central engineering insight is that the bot domain has no state of its own — it derives all its information from the appointment domain using pure functions. A reader who completes this batch will understand the derive principle (ADR-007), how pure functions are structured and enforced in a Spring application, and how GDPR minimisation shapes the bot's data-access model.

This batch answers the question: *how does the bot panel present system state without maintaining its own state?*

### Engineering Narrative

The three pure-function classes in the bot domain — `BotEventResolver`, `BotNarrativeRenderer`, `BotPhoneMaskingService` — share a design principle that is explicit and enforced: no Spring annotations, no repositories, no clock access, no mutable state. They are published as a group because their value is in the pattern, not in any individual class.

`BotEventResolver` is the most complex of the three. It takes an `Appointment` entity and derives a `List<BotEvent>` — the complete history of bot-relevant events for that appointment, inferred from the appointment's current state and its timestamp fields. The algorithm handles the full taxonomy of bot event types: proposed, auto-confirmed, owner approved, owner rejected, timeout expired, cancellation requested, and so on. It also handles a historical edge case (appointments created before the audit infrastructure was introduced) via a documented heuristic. The purity guarantee means the derivation is testable in complete isolation from the database.

`BotPhoneMaskingService` is the shortest publishable class in the repository — under 60 lines — and a clear illustration of GDPR minimisation by design. The bot audit list view shows a masked phone (`+34 *** *** 111`), not the full number. The masking happens in the service layer, before the response reaches the controller. The algorithm is explicit: known country code prefixes for the operating market (Spain) and likely neighbouring markets, with a 2-digit fallback. The class comment explicitly notes that the fallback is a conscious simplification, and that extending the known-prefix list without a concrete demand is a policy decision, not a technical one.

### Artefacts

**Production source:**
- `bot/service/BotEventResolver.java` — pure derive function: Appointment → BotEvents (BOT DOMAIN, TRANSLATED)
- `bot/service/BotNarrativeRenderer.java` — pure renderer: BotEvent + context → narrative string (BOT DOMAIN, ANNOTATED)
- `bot/service/BotPhoneMaskingService.java` — pure E.164 phone masking utility (BOT DOMAIN, SANITISED)
- `bot/domain/BotEvent.java` — bot event value object (BOT DOMAIN, SANITISED)
- `bot/domain/BotEventType.java` — bot event type enum (BOT DOMAIN, SANITISED)
- `bot/domain/BotNarrativeContext.java` — rendering context record (BOT DOMAIN, SANITISED)

**Tests:**
- `BotEventResolverTest.java` — pure function unit tests for all event types (TESTING, SANITISED)
- `BotNarrativeRendererTest.java` — renderer unit tests; narrative strings annotated as locale output (TESTING, ANNOTATED)
- `BotPhoneMaskingServiceTest.java` — masking unit tests for all country code cases (TESTING, SANITISED)
- `BotRecentRelevantReadIT.java` — re-anchoring: bot reads most recent relevant appointment (TESTING, SANITISED)

### Dependencies

SC-1 (`BotEventResolver` takes `Appointment` and uses `AppointmentStatus`, `AppointmentSource`). SC-2 (`AppointmentEvent` is published; `BotRecentRelevantReadIT` exercises the same re-anchoring mechanism as `TurnCorrelationIT`).

### Sanitisation Effort

**Medium.** `BotEventResolver` contains internal invariant codes (INV #51, #52, #54) and internal document cross-references (internal design document section references) in its Javadoc — all removed; the engineering points they annotate are retained. `BotNarrativeRenderer` contains Spanish-language narrative output strings that must be annotated as locale-specific output. `BotNarrativeRendererTest` has test assertions on Spanish strings — these are annotated as locale output, not translated. `BotPhoneMaskingService` is clean; namespace update only.

### Expected Repository Impact

Six production classes and four test classes published. ADR-007 "Source Code Reference" section can be fully populated. ADR-014 "Source Code Reference" section can be partially populated (phone masking). The bot domain is now fully legible: its derive architecture, its purity pattern, and its GDPR minimisation all have published implementations.

---

## Source Batch SC-4 — Privacy Infrastructure

**Target release:** v0.12.0  
**Editorial classifications:** PRIVACY INFRASTRUCTURE, TESTING

### Objective

Publish the legitimation and consent infrastructure as a complete, verifiable unit. The distinguishing feature of this batch is the architecture guard tests: they prove that the legitimation invariant cannot be violated silently, using ArchUnit to enforce the architectural constraint at the code level. A reader who completes this batch will understand: the channel legitimacy model (ADR-013), how structural enforcement differs from policy enforcement, and how architecture tests make the enforcement verifiable.

This batch answers the question: *how is consent enforced structurally, not just by convention?*

### Engineering Narrative

`OutboundLegitimacyGate` is forty lines of code that enforce the most significant privacy constraint in the system: a customer can only receive a WhatsApp message if their channel legitimacy status has been affirmatively established. The implementation is the policy: fresh-read per customer id (no stale cache can allow a disallowed send), `Boolean.TRUE.equals(customer.getChannelLegitimacyStatus())` (null-safe — null means not established, which is deny), feature-flag gated (the gate is wired but held off pending activation). Default-deny is not a configuration option — it is the code path that executes when the status is absent.

`LegitimacyTransactionalWriter` separates the write path — updating the status and inserting the audit row — from the service logic. The separation is not for testability; it is for transactional atomicity. Both writes (status update and audit row insertion) must succeed together. If the service method called the repository directly and the audit write failed after the status update, the status would change without a record. The `LegitimacyTransactionalWriter` wraps both operations in a single `@Transactional` method, making the atomicity a design commitment rather than an implementation detail.

`LegitimationArchitectureGuardTest` is the most important test in this batch. After a historical refactoring removed a `source == BOT` condition that had been used as a legitimation bypass path, this ArchUnit test was written to ensure the condition could not be re-introduced. The test uses static code analysis to scan every production class for the pattern — and fails the build if it finds one. Publishing this test alongside the legitimation gate shows that the privacy enforcement is not only present but protected against regression.

### Artefacts

**Production source:**
- `customer/legitimation/OutboundLegitimacyGate.java` — channel legitimacy gate, default-deny (PRIVACY INFRASTRUCTURE, SANITISED)
- `customer/legitimation/CustomerLegitimacyService.java` — legitimation state machine (PRIVACY INFRASTRUCTURE, TRANSLATED)
- `customer/legitimation/LegitimacyTransactionalWriter.java` — atomic status + audit write (PRIVACY INFRASTRUCTURE, TRANSLATED)
- `customer/entity/CustomerLegitimationAuditLog.java` — metadata-only consent record (PRIVACY INFRASTRUCTURE, SANITISED)
- `customer/legitimation/LegitimacyDecision.java` — gate return value: ALLOW or DENY (PRIVACY INFRASTRUCTURE, SANITISED)

**Tests:**
- `LegitimationArchitectureGuardTest.java` — ArchUnit: bypass pattern cannot be re-introduced (TESTING, SANITISED)
- `LegitimationWiringGuardTest.java` — ArchUnit: gate is wired on all outbound paths (TESTING, SANITISED)
- `CustomerLegitimacyServiceIT.java` — legitimation state machine integration test (TESTING, SANITISED)
- `LegitimationEnforcementE2EIT.java` — end-to-end: message is blocked when legitimacy absent (TESTING, SANITISED)

### Dependencies

SC-1 (the `Customer` entity's `channelLegitimacyStatus` field is part of the FOUNDATIONAL entity; `AuthorizationService` is used in the legitimation service path). SC-2 (the `CustomerEvent` family published in SC-2 triggers the legitimation listener).

### Sanitisation Effort

**Low to Medium.** `OutboundLegitimacyGate` is in English and requires only namespace update and removal of the internal feature-flag property name. `CustomerLegitimacyService` and `LegitimacyTransactionalWriter` contain Spanish comments requiring translation. All test classes are in English; internal comment cross-references are removed. Field names in `Customer` must be consistent with the ADR-013 translation convention (`channelLegitimacyStatus`).

### Expected Repository Impact

Five production classes and four test classes published. ADR-013 "Source Code Reference" section can be fully populated. ADR-015 "Source Code Reference" section can be partially populated. The legitimation infrastructure is now legible as a complete, verifiable unit — not just described in an ADR but demonstrated in working code with architecture guard tests.

---

## Source Batch SC-5 — Security Infrastructure

**Target release:** v0.13.0  
**Editorial classifications:** SECURITY, CORE DOMAIN (consent), OBSERVABILITY, TESTING

### Objective

Complete the security picture established in SC-1. SC-1 published the webhook security stack; SC-5 publishes the remaining security infrastructure: rate limiting, JWT filter chain, consent enforcement, the JVM timezone guard, and the Sentry PII scrubbing callback. A reader who completes this batch will have a complete view of every security control in the system.

This batch answers the question: *what are all the layers between a request and the business logic?*

### Engineering Narrative

`RateLimitingFilter` is a deliberate simplicity decision. It uses `ConcurrentHashMap` and `AtomicReference` — in-memory, single-instance, no Redis. The class comment documents this explicitly: the filter is sufficient for the current single-instance deployment, and the comment states exactly what changes when the deployment scales to multiple nodes (replace `ConcurrentHashMap` with `RedisTemplate`, preserve the interface). Publishing this filter alongside the comment demonstrates that simplicity chosen with full awareness of its limits is good engineering. It is not naïveté — it is a documented trade-off.

`JvmTimezoneInvariant` encodes the architectural liability described in ADR-008 as a runtime invariant. The `@PostConstruct` method aborts the JVM if the timezone is not `Europe/Madrid` (or an alias with an equivalent offset). The class also implements `InfoContributor`, making the timezone state visible at `GET /actuator/info`. Publishing this class alongside ADR-008 closes the loop between the ADR's description of the liability and the code that enforces the mitigation.

`ConsentService` is notable for one detail: it uses `OffsetDateTime.now(ZoneOffset.UTC)` — explicitly UTC — for consent timestamps. The rest of the system uses `LocalDateTime` (Madrid wall-clock, the ADR-008 liability). Consent timestamps use UTC because consent evidence requires an unambiguous instant, not a wall-clock value that depends on the JVM's timezone setting. This is the same principle as ADR-008 applied defensively before the full migration: for legal evidence, get the timezone right even if the business domain timestamps are still naive.

### Artefacts

**Production source:**
- `security/ratelimit/RateLimitingFilter.java` — in-memory sliding-window rate limiter (SECURITY, TRANSLATED)
- `auth/security/JwtAuthenticationFilter.java` — JWT extraction and validation (SECURITY, SANITISED)
- `auth/security/LegalAcceptanceFilter.java` — consent enforcement in filter chain (SECURITY, SANITISED)
- `auth/service/ConsentService.java` — GDPR consent recording with explicit UTC (SECURITY, TRANSLATED)
- `auth/entity/ConsentAudit.java` — consent record entity (CORE DOMAIN, SANITISED)
- `auth/entity/ConsentType.java` — consent type enum (CORE DOMAIN, SANITISED)
- `config/JvmTimezoneInvariant.java` — startup guard + actuator contributor (SECURITY, TRANSLATED)
- `config/observability/SentryBeforeSendCallback.java` — PII scrubbing before Sentry (OBSERVABILITY, SANITISED)

**Tests:**
- `BotConsentEnforcementIT.java` — consent gate blocks non-consented outbound message (TESTING, SANITISED)
- `WebhookDataMinimizationTest.java` — webhook responses contain no unnecessary PII (TESTING, SANITISED)
- `RateLimitingFilterTest.java` — rate limiter unit test: window, reset, and 429 response (TESTING, SANITISED)
- `SentryBeforeSendCallbackTest.java` — PII scrubbing: customer names and phones removed from events (TESTING, SANITISED)

### Dependencies

SC-1 (JWT infrastructure published in SC-1; `JwtAuthenticationFilter` references `JwtService`). SC-2 (Sentry callback references the observability model established there). SC-4 (consent enforcement references the legitimation model).

### Sanitisation Effort

**Medium.** `RateLimitingFilter` contains Spanish-language documentation (product-specific Spanish copy policy reference) requiring translation. `ConsentService` contains internal document cross-references (internal design document section references) requiring removal. `JvmTimezoneInvariant` has a long Spanish-language Javadoc block — the most translation-intensive file in this batch — describing the production incident that motivated the guard. The incident description is retained (it is the engineering motivation for the guard) but translated and stripped of internal references.

### Expected Repository Impact

Eight production classes and four test classes published. ADR-008 "Source Code Reference" section can be fully populated (JvmTimezoneInvariant). ADR-018 "Source Code Reference" section can be completed (JWT filter). The security picture is now complete: every security control from rate limiting at the auth endpoints to HMAC validation at the webhook to consent enforcement in the filter chain is published.

---

## Source Batch SC-6 — Temporal Boundary and Concurrency

**Target release:** v0.14.0  
**Editorial classifications:** CORE DOMAIN, TESTING, UTILITY

### Objective

Complete the source journey with the temporal boundary enforcement model and the blocked slot state machine. This batch is also the home for the concurrency and race condition tests — the clearest specification of the invariants that the system must maintain under concurrent load. A reader who completes this batch will have a complete picture of how time governs the appointment lifecycle and how the system handles concurrent access to shared scheduling state.

This batch answers the question: *what happens at the boundary between appointment time and the present, and how does the system handle multiple actors competing for the same slot?*

### Engineering Narrative

`BlockedSlotPolicy` is a pattern class rather than an implementation class. It contains no business logic in the narrow sense — it delegates to a single field read on the `Business` entity. Its value is architectural: it is the single source of truth for "who can do what with a blocked slot." Every permission decision about blocked slots is made by consulting this class, not by duplicating the rule inline in the service. The class comment articulates the pattern explicitly: one place to read, one place to test, one place to extend, and a guarantee that the backend's permission model agrees with the frontend's UX constraints.

`TemporalBoundaryIT` and `TemporalBoundaryEvidenceIT` are the formal specification of ADR-011. They verify that the temporal boundary — the appointment's datetime as the dividing line between the operational plane (before) and the closure plane (after) — is enforced mechanically. Confirming a past appointment fails. Approving a cancellation request after the appointment time has passed fails. The tests run against a real PostgreSQL instance (Testcontainers) with datetime values explicitly set to test the boundary conditions. They are the most complete translation of ADR-011's engineering principle into executable specification.

`PhoneRaceIT` tests a race condition: two concurrent requests attempting to register the same phone number. The expected behaviour is that exactly one succeeds and one fails with a conflict response — the database unique constraint is the arbitrator, not application-layer logic. The test verifies that the system does not produce silent corruption (two records with the same phone) under concurrent load.

### Artefacts

**Production source:**
- `schedule/entity/BlockedSlot.java` — blocked slot entity with FSM state (CORE DOMAIN, SANITISED)
- `schedule/entity/BlockedSlotStatus.java` — blocked slot state enum (CORE DOMAIN, SANITISED)
- `schedule/policy/BlockedSlotPolicy.java` — permission policy class (CORE DOMAIN, ANNOTATED)
- `common/util/PhoneNormalizer.java` — E.164 phone normalisation utility (UTILITY, SANITISED)
- `common/exception/BadRequestException.java` — standard exception type (UTILITY, SANITISED)
- `common/exception/ConflictException.java` — standard exception type (UTILITY, SANITISED)
- `common/exception/ForbiddenException.java` — standard exception type (UTILITY, SANITISED)
- `common/exception/ResourceNotFoundException.java` — standard exception type (UTILITY, SANITISED)

**Tests:**
- `TemporalBoundaryIT.java` — boundary: status transitions fail after appointment datetime (TESTING, SANITISED)
- `TemporalBoundaryEvidenceIT.java` — boundary evidence: comprehensive datetime boundary assertions (TESTING, SANITISED)
- `BotNotesMinimizationIT.java` — GDPR: 14 test cases for appointment notes minimisation (TESTING, SANITISED)
- `AppointmentAuditLogConstraintsIT.java` — audit log: schema constraints enforced on all event types (TESTING, SANITISED)
- `PhoneRaceIT.java` — race: concurrent phone registration produces exactly one success (TESTING, SANITISED)

### Dependencies

SC-1 (`BlockedSlot` shares `BaseEntity`; exception types are used across all previously published classes). SC-2 (`TemporalBoundaryIT` creates appointments and exercises the same FSM enforced by the audit listener). SC-4 (`BotNotesMinimizationIT` exercises legitimation paths alongside notes handling).

### Sanitisation Effort

**Low.** The artefacts in this batch are predominantly in English and contain minimal internal references. `BlockedSlotPolicy` contains an internal design document cross-reference (AGENTS §6) requiring removal; the engineering reasoning it annotates is retained. Exception types require only namespace update. Test classes require only namespace update and removal of any Spanish comment strings in assertion messages.

### Expected Repository Impact

Eight production classes and five test classes published. ADR-002 "Source Code Reference" section can be populated (BlockedSlot, BlockedSlotPolicy). ADR-011 "Source Code Reference" section can be completed (TemporalBoundaryIT). ADR-015 "Source Code Reference" section can be completed (BotNotesMinimizationIT). The Source Code Journey is complete. The `docs/source/README.md` index is updated to reflect all six batches as published.

---

## Completion Criteria

The Source Code Journey is considered complete when:

1. All six batches (SC-1 through SC-6) are published
2. Every ADR's "Source Code Reference" section contains working links to the corresponding published source
3. The `docs/source/README.md` index accurately reflects all published artefacts
4. The classification summary table in `SOURCE_CLASSIFICATION.md` is fully populated
5. CI passes on the published source (`mvn compile` and `mvn test` are green)
6. No broken links exist between published documents and published source

---

## What Remains After SC-6

The Source Code Journey does not publish everything. The artefacts marked PRIVATE in the classification matrix — `AppointmentService`, the Flyway migration history, production configuration — remain private. Their engineering value is captured in other forms:

The Case Studies published separately will narrate the engineering problems that `AppointmentService` solves: the temporal boundary, the slot algorithm, the assignment engine, the cancellation timeout flow. These narratives communicate more to a reader in 2000 words than the full 5352-line class would.

The "78 Migrations" case study will narrate the schema evolution story without publishing the migration files. The migrations are evidence of 78 structural decisions; the case study is the reading guide that makes those decisions coherent.

The Engineering Investigations published separately will address specific technical questions — why MDC over `@RequestScope`, how the `AFTER_COMMIT` / synchronous `@EventListener` choice was made for different notification paths, when `ConcurrentHashMap` stops being sufficient — without requiring the full source context.

The Source Code Journey creates the foundation. The Case Studies and Engineering Investigations are the interpretation layer built on top of it.
