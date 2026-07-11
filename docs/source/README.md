# Source Code Journey

This directory contains the editorial framework for the Source Code Journey — the structured publication of the VookedMe backend Java source code.

The Source Code Journey follows the same editorial discipline as the ADR Journey: source artefacts are published in coherent batches, ordered by architectural significance, with every published file sanitised of internal identifiers and translated to natural technical English.

---

## Status

> **v1.1.0 — SC-3 published.** Six SC-3 production artefacts and four tests are live. The bot domain orchestration layer — `BotEventResolver`, `BotNarrativeRenderer`, `BotPhoneMaskingService`, and supporting domain types — is now readable alongside SC-1 and SC-2.

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

## Relationship to the ADR Journey

The ADR Journey is complete. Seventeen ADRs describe *why* the system is designed the way it is.

The Source Code Journey publishes the code that implements those decisions. Every published source batch populates the "Source Code Reference" sections of the relevant ADRs — converting them from standalone arguments