# Source Code Journey

This directory contains the editorial framework for the Source Code Journey — the structured publication of the VookedMe backend Java source code.

The Source Code Journey follows the same editorial discipline as the ADR Journey: source artefacts are published in coherent batches, ordered by architectural significance, with every published file sanitised of internal identifiers and translated to natural technical English.

---

## Status

> **v1.4.0 — Source Code Journey complete.** SC-6 is published. 50 production artefacts and 23 tests are now live across all six batches. The temporal boundary principle (ADR-011), the BlockedSlot FSM and permission policy (ADR-002), phone normalisation, concurrent phone registration, and the full audit constraint test battery close the engineering narrative. Every ADR in the ADR Journey now has at least one published source artefact in its Source Code Reference section.

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
| SC-2 | Event System and Audit Trail | CORE DOMAIN, OBSERVABILITY, TESTING | **Published (v1.0.0)** |
| SC-3 | Bot Domain | BOT DOMAIN, TESTING | **Published (v1.1.0)** |
| SC-4 | Privacy Infrastructure | PRIVACY INFRASTRUCTURE, TESTING | **Published (v1.2.0)** |
| SC-5 | Security Infrastructure | SECURITY, OBSERVABILITY, TESTING | **Published (v1.3.0)** |
| SC-6 | Temporal Boundary and Concurrency | CORE DOMAIN, TESTING, UTILITY | **Published (v1.4.0)** |

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

## SC-2 Artefacts

**Production source** (`src/main/java/com/vookedme/botmanager/`):

| Artefact | Package | Editorial Category |
|---|---|---|
| `AppointmentEvent.java` | `common/event` | CORE DOMAIN |
| `AppointmentAuditLog.java` | `appointment/entity` | CORE DOMAIN |
| `AppointmentAuditListener.java` | `appointment/audit` | CORE DOMAIN |
| `TurnContext.java` | `config/observability` | OBSERVABILITY |
| `TurnCorrelationFilter.java` | `webhook/security` | OBSERVABILITY |

**Integration tests** (`src/test/java/com/vookedme/botmanager/`):

| Artefact | Package | What it verifies |
|---|---|---|
| `AppointmentAuditFlowIT.java` | `appointment` | End-to-end audit pipeline: actor attribution for PANEL, BOT, SCHEDULER, and bulk cancel paths; correlationId shared across bulk operations |
| `BotWebhookIdempotencyIT.java` | `appointment` | Webhook idempotency: partial UNIQUE INDEX as authoritative source of truth; sequential retry, concurrent race, cross-tenant scoping, NULL eventId legacy path |
| `TurnCorrelationIT.java` | `webhook` | Forensic turn_id contract: persisted from header, NULL on fallback; multi-call grouping; re-anchor log contains no PII |

---

## SC-3 Artefacts

**Production source** (`src/main/java/com/vookedme/botmanager/`):

| Artefact | Package | Editorial Category |
|---|---|---|
| `BotEvent.java` | `bot/domain` | BOT DOMAIN |
| `BotEventType.java` | `bot/domain` | BOT DOMAIN |
| `BotNarrativeContext.java` | `bot/domain` | BOT DOMAIN |
| `BotEventResolver.java` | `bot/service` | BOT DOMAIN |
| `BotNarrativeRenderer.java` | `bot/service` | BOT DOMAIN |
| `BotPhoneMaskingService.java` | `bot/service` | BOT DOMAIN |

**Tests** (`src/test/java/com/vookedme/botmanager/`):

| Artefact | Package | What it verifies |
|---|---|---|
| `BotEventResolverTest.java` | `bot` | Exhaustive 11-branch derivation coverage and purity guarantees: class shape, no Spring wiring, no instance state, determinism, immutable output |
| `BotNarrativeRendererTest.java` | `bot` | Full locale output coverage; exact Spanish template strings verified as locale constants; defensive fallbacks; purity contract |
| `BotPhoneMaskingServiceTest.java` | `bot` | Spain pilot, international known prefixes, generic 2-digit fallback, rejection paths, purity contract |
| `BotRecentRelevantReadIT.java` | `bot` | Re-anchoring read: active-status inclusion, terminal-status exclusion, 24-hour window boundary precision, verified absence of CANCELLED rows |

---

## SC-4 Artefacts

**Production source** (`src/main/java/com/vookedme/botmanager/`):

| Artefact | Package | Editorial Category |
|---|---|---|
| `LegitimacyDecision.java` | `customer/legitimation` | PRIVACY INFRASTRUCTURE |
| `OutboundLegitimacyGate.java` | `customer/legitimation` | PRIVACY INFRASTRUCTURE |
| `LegitimacyTransactionalWriter.java` | `customer/legitimation` | PRIVACY INFRASTRUCTURE |
| `CustomerLegitimacyService.java` | `customer/legitimation` | PRIVACY INFRASTRUCTURE |
| `CustomerLegitimationAuditLog.java` | `customer/entity` | PRIVACY INFRASTRUCTURE |
| `LegitimationState.java` | `customer/legitimation` | PRIVACY INFRASTRUCTURE |
| `CustomerLegitimationEventType.java` | `customer/entity` | PRIVACY INFRASTRUCTURE |
| `OriginOfLegitimation.java` | `customer/entity` | PRIVACY INFRASTRUCTURE |
| `ReasonOfDeny.java` | `customer/entity` | PRIVACY INFRASTRUCTURE |

**Tests** (`src/test/java/com/vookedme/botmanager/`):

| Artefact | Package | What it verifies |
|---|---|---|
| `LegitimationArchitectureGuardTest.java` | `customer/legitimation` | ArchUnit bytecode rules: single-write-owner for legitimation fields; audit row construction and persistence restricted to `customer.legitimation`; writer encapsulation |
| `LegitimationWiringGuardTest.java` | `customer` | Source-reading wiring guard: existing-customer legitimation deferred to AFTER_COMMIT event; new-customer legitimation inline; listener is `@Async` with background failure handling |
| `CustomerLegitimacyServiceIT.java` | `customer` | State machine transitions against real PostgreSQL: UNEVALUATED → LEGITIMATE, LEGITIMATE → DENIED, DENIED → REACTIVATED; silent no-op over explicit opt-out; idempotency; OWNER attestation path |
| `LegitimationEnforcementE2EIT.java` | `customer` | End-to-end gate enforcement with flag ON: fresh-read suppresses stale entity; default-deny (null); allow (true); real `@ConfigurationProperties` binding verified |

---

## SC-5 Artefacts

**Production source** (`src/main/java/com/vookedme/botmanager/`):

| Artefact | Package | Editorial Category |
|---|---|---|
| `RateLimitingFilter.java` | `security/ratelimit` | SECURITY |
| `JwtAuthenticationFilter.java` | `auth/security` | SECURITY |
| `LegalAcceptanceFilter.java` | `auth/security` | SECURITY |
| `ConsentService.java` | `auth/service` | SECURITY |
| `ConsentAudit.java` | `auth/entity` | CORE DOMAIN |
| `ConsentType.java` | `auth/entity` | CORE DOMAIN |
| `JvmTimezoneInvariant.java` | `config` | SECURITY |
| `SentryBeforeSendCallback.java` | `config/observability` | OBSERVABILITY |

**Tests** (`src/test/java/com/vookedme/botmanager/`):

| Artefact | Package | What it verifies |
|---|---|---|
| `BotConsentEnforcementIT.java` | `auth` | DPA gate, legal gate, role-based enforcement, priority ordering of the two consent gates |
| `WebhookDataMinimizationTest.java` | `webhook` | Reflection-based field and JSON-key scan; enforces ADR-014 / ADR-015 bot data minimisation invariant |
| `RateLimitingFilterTest.java` | `security` | Per-IP and per-path bucket isolation; locale response message; `resetForTests()` contract |
| `SentryBeforeSendCallbackTest.java` | `observability` | PII scrub (user, headers, query, body, extras, tags, breadcrumbs); noise filter (4xx + domain exceptions); 5xx pass-through |

---

## SC-6 Artefacts

**Production source** (`src/main/java/com/vookedme/botmanager/`):

| Artefact | Package | Editorial Category |
|---|---|---|
| `BlockedSlot.java` | `schedule/entity` | CORE DOMAIN |
| `BlockedSlotStatus.java` | `schedule/entity` | CORE DOMAIN |
| `BlockedSlotPolicy.java` | `schedule/policy` | CORE DOMAIN |
| `PhoneNormalizer.java` | `common/util` | UTILITY |
| `BadRequestException.java` | `common/exception` | CORE DOMAIN |
| `ConflictException.java` | `common/exception` | CORE DOMAIN |
| `ForbiddenException.java` | `common/exception` | CORE DOMAIN |
| `ResourceNotFoundException.java` | `common/exception` | CORE DOMAIN |
| `UnauthorizedException.java` | `common/exception` | CORE DOMAIN |

**Tests** (`src/test/java/com/vookedme/botmanager/`):

| Artefact | Package | What it verifies |
|---|---|---|
| `TemporalBoundaryIT.java` | `appointment` | PFT-1/3/4/5: CR expiry cutoff, rejection of CR decisions on past appointments, reminder suppression, outbound notification suppression, 15-minute decision window |
| `TemporalBoundaryEvidenceIT.java` | `appointment` | Adversarial audit evidence: exact boundary instant, job idempotency, SQL-backdated expiry, zombie reminder sweep, bot-path temporal guards, OWNER/ADMIN/EMPLOYEE role matrix |
| `BotNotesMinimizationIT.java` | `appointment` | ADR-015 discard invariant: 14 parametrised cases across Art.9 special-category inputs and benign operational context; both batteries produce `notes == null` |
| `AppointmentAuditLogConstraintsIT.java` | `appointment` | Database-level audit constraints: `chk_audit_actor_user_id`, enum-bounded `event_type`/`triggered_by`, append-only `trg_appointment_audit_immutable` |
| `PhoneRaceIT.java` | `phone` | Concurrent phone registration: `CountDownLatch` starting gun; `@RepeatedTest(5)`; exactly one success under concurrent INSERTs; UNIQUE constraint as authoritative arbiter |

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

**SC-2 (now available):**

7. `AppointmentEvent` — what information flows with a mutation; field evolution story in class Javadoc
8. `AppointmentAuditLog` — forensic append-only entity; why `occurred_at` is `TIMESTAMPTZ`; data minimisation constraints
9. `AppointmentAuditListener` — committed ⟺ audited; the synchronous `@EventListener` design and why it was chosen over `@TransactionalEventListener`
10. `TurnContext` — MDC-based turn identifier; why MDC over `@RequestScope`; forensic vs synthetic distinction
11. `TurnCorrelationFilter` — binds `X-Turn-Id` to the thread; correct degraded behaviour when header is absent

**SC-3 (now available):**

12. `BotEventType` — the 11-value taxonomy; every discriminator comment explains the 3-actor cancellation model
13. `BotEvent` — the immutable projection record; why the identifier is deterministic; why actor separation is explicit
14. `BotEventResolver` — the pure derive function; the 11 numbered branches; V69_DEPLOY_TIMESTAMP and the pre-audit-columns fallback heuristic; the exhaustive guard
15. `BotNarrativeRenderer` — locale output in pure function form; `compileTimeExhaustivenessCheck`; why Spanish strings are operator UI, not translatable comments
16. `BotPhoneMaskingService` — GDPR masking at the backend; the known-prefix table; generic fallback as explicit design decision

**SC-4 (now available):**

17. `OutboundLegitimacyGate` — default-deny consent enforcement; forty lines that make the privacy guarantee structural rather than conventional; null and false are both ineligible
18. `LegitimacyTransactionalWriter` — the atomic write unit; why the state update and audit row must be in the same transaction; idempotency of each transition
19. `CustomerLegitimacyService` — the public façade; bounded optimistic lock retry; why the retry lives here and not in the writer
20. `CustomerLegitimationAuditLog` — metadata-only consent record; `OffsetDateTime` for UTC audit timestamps; no PII; no FK for immutability
21. `LegitimationArchitectureGuardTest` — how ArchUnit enforces that the single-write-owner invariant cannot be violated silently

**SC-5 (now available):**

22. `RateLimitingFilter` — deliberate simplicity: `ConcurrentHashMap` + `AtomicReference` in-memory; the class comment states exactly what changes at multi-node scale
23. `JwtAuthenticationFilter` — JWT extraction; access-token vs refresh-token discrimination via presence of the `role` claim
24. `LegalAcceptanceFilter` — consent enforcement in the filter chain; whitelist for "fix the problem" endpoints; DPA gate applies only to OWNER (architectural lock)
25. `ConsentService` — `OffsetDateTime.now(ZoneOffset.UTC)` for legal evidence; why UTC here when the rest of the system uses `LocalDateTime` (wall-clock); idempotency per version
26. `JvmTimezoneInvariant` — a production incident as a startup guard; `@PostConstruct` hard-fail; alias tolerance; `InfoContributor` for operational visibility

**SC-6 (now available):**

27. `BlockedSlot`, `BlockedSlotStatus`, `BlockedSlotPolicy` — the five-state FSM for employee leave requests; `BlockedSlotPolicy` as a single-source-of-truth permission class; why only `APPROVED` blocks affect calendar availability
28. `PhoneNormalizer` — canonical E.164 normalisation; `\p{Z}` Unicode separator handling; the strong contract (returns canonical, null, or throws — never a partial value); PII excluded from error messages
29. `TemporalBoundaryIT`, `TemporalBoundaryEvidenceIT` — the formal executable specification of ADR-011; adversarial evidence suite including SQL-backdated appointment rows and zombie reminder sweep with empirically verified zero outbound sends
