# ADR-003 — Three-Layer Audit Architecture

**Status:** Accepted  
**Date:** 2026-04-28  
**Domain:** audit  
**Editorial:** CORE

> **Engineering Question Answered:** When a system needs to audit different categories of state change — from lightweight "who last modified this?" to cross-resource forensic records — how do you avoid forcing all audit data into a single model that serves none of the use cases well?

---

## Problem

An appointment scheduling system generates multiple categories of auditable event. Some events are high-frequency and per-resource: who rescheduled this appointment, who toggled the payment flag. Others are cross-resource and security-sensitive: who changed a business setting, who deactivated an employee, who approved a schedule block. These two categories have different query patterns, different storage requirements, and different performance characteristics. Choosing a single audit model that serves both categories equally means either paying the performance cost of a general-purpose audit table for every dashboard query, or accepting that sensitive cross-resource events have no structured storage at all.

## Context

Two competing audit philosophies existed in the codebase before this decision:

**Philosophy A — Named columns per event**: for actions with well-known, schema-stable audit metadata, store that metadata as named columns directly on the entity being audited. A `toggle-paid` audit, for example, lives as `paid_changed_by_user_id`, `paid_changed_at`, and `paid_changed_reason` on the `appointments` table. This is the pattern already in use for payment audit.

**Philosophy B — Canonical audit log table**: a single general-purpose table with columns for `tenantId`, `actorId`, `actorRole`, `resource`, `resourceId`, `action`, `before`, `after`, `reason`, `ipAddress`, `userAgent`, and `createdAt`. Every auditable action writes a row here. Query anything from one place.

An audit review found that Philosophy A was partially implemented — only payment had named columns; rescheduling, assignment, cancellation, and deactivation were not audited at all. Philosophy B was prescribed but not implemented. Neither philosophy was complete; neither had been formally chosen over the other.

The two philosophies are not interchangeable. The correct choice depends on the action being audited. Forcing everything into named columns produces an unworkable entity schema for cross-resource events. Forcing everything into a generic audit table makes dashboard queries that read named columns perform an unnecessary join with JSONB extraction.

## Decision

Implement both, scoped by action type. The architecture has three layers:

### Layer 1 — Universal last-modified tracking on every mutable table

Two columns added to all entity tables:

- `updated_by_user_id BIGINT REFERENCES users(id)` — nullable for system-initiated actions
- `updated_at TIMESTAMP NOT NULL DEFAULT NOW()`

A JPA `@PreUpdate` listener stamps both fields on every save. This is the cheapest possible audit baseline: who last touched this row, and when. No before/after state; just the last actor. Layer 1 is automatic — engineers write no explicit audit code for it.

### Layer 2 — Named columns for per-entity, hot-path audit

The named-column pattern is retained for actions that are:

- Frequent — queried directly in dashboards and list views
- Per-resource — the audit metadata fits naturally as columns on the entity being audited
- Schema-stable — the field set is well-known and unlikely to expand

Examples in use:

- `appointments.paid_changed_*` — `paid_changed_by_user_id`, `paid_changed_at`, `paid_changed_reason`
- `appointments.last_reschedule_*` — `last_reschedule_by_user_id`, `last_reschedule_at`, `last_reschedule_reason`
- `appointments.last_assigned_*` — `last_assigned_by_user_id`, `last_assigned_at`
- `appointments.cancelled_*` — `cancelled_by_user_id`, `cancelled_at`, `cancellation_reason`
- `users.deactivated_*` — `deactivated_by_user_id`, `deactivated_at`, `deactivation_reason`
- `blocked_slots.*` — `approved_*`, `rejected_*`, `cancelled_*` (established by ADR-002)

### Layer 3 — `audit_logs` table for cross-resource and sensitive actions

A single general-purpose audit table absorbs events that do not fit naturally on a single entity — settings changes, role changes, approvals that cross entity boundaries, and any action with forensic significance:

```sql
CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    actor_user_id BIGINT REFERENCES users(id),  -- nullable: system-initiated actions
    actor_role VARCHAR(20),                      -- role snapshot at action time
    resource_type VARCHAR(50) NOT NULL,          -- 'APPOINTMENT', 'BLOCKED_SLOT', 'BUSINESS_SETTINGS', ...
    resource_id BIGINT,                          -- nullable: business-wide actions
    action VARCHAR(50) NOT NULL,                 -- 'CANCELLED', 'APPROVED', 'SETTING_CHANGED', ...
    before JSONB,                                -- field snapshot before action; nullable for creates
    after JSONB,                                 -- field snapshot after action; nullable for deletes
    reason TEXT,                                 -- optional free-text justification
    ip_address VARCHAR(45),                      -- IPv4 or IPv6
    user_agent VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_business_resource ON audit_logs(business_id, resource_type, resource_id);
CREATE INDEX idx_audit_logs_actor ON audit_logs(actor_user_id, created_at DESC);
CREATE INDEX idx_audit_logs_business_created ON audit_logs(business_id, created_at DESC);
```

`AuditLogService.write(...)` is the single writer. It is called from domain event listeners (`AppointmentEventListener`, `BlockedSlotEventListener`, `BusinessSettingsEventListener`) after the originating transaction commits — the same pattern used for notification dispatch.

### Catalogue: which layer for which action

| Action | Layer 1 (`updated_*`) | Layer 2 (named columns) | Layer 3 (`audit_logs`) |
|---|---|---|---|
| Appointment create | ✅ | — | — |
| Appointment reschedule | ✅ | ✅ `last_reschedule_*` | — |
| Appointment assign / unassign | ✅ | ✅ `last_assigned_*` | ✅ crosses employee boundary |
| Appointment cancel | ✅ | ✅ `cancelled_*` | ✅ sensitive |
| Appointment toggle paid | ✅ | ✅ `paid_changed_*` | — |
| BlockedSlot REQUESTED → APPROVED | ✅ | ✅ `approved_*` (ADR-002) | ✅ approval is auditable event |
| BlockedSlot REQUESTED → REJECTED | ✅ | ✅ `rejected_*` (ADR-002) | ✅ |
| BlockedSlot APPROVED → CANCELLED | ✅ | ✅ `cancelled_*` (ADR-002) | ✅ |
| Customer create / edit | ✅ | — | — |
| Customer soft-archive | ✅ | — | — |
| Schedule modify | ✅ | — | ✅ crosses business operations |
| Settings changed | — | — | ✅ always — settings are sensitive |
| Role / permission changed | — | — | ✅ always — security-sensitive |
| Employee deactivate / unlink | ✅ | ✅ `deactivated_*` | ✅ |
| Offering archive / price change | ✅ | — | ✅ |

### Decision rule for engineers

When adding a new auditable action:

- Does the audit data fit on the resource as 3–4 named columns **and** is it queried per-resource in dashboards? → **Layer 2**.
- Is the action cross-resource, security-sensitive, or a state machine transition requiring a forensic record? → **Layer 3** (`audit_logs`).
- Always: `updated_by_user_id` and `updated_at` are stamped automatically by the `@PreUpdate` listener — **Layer 1 requires no engineer action**.

Adding a new auditable action does not require an ADR amendment — consult the rule above and place the event at the appropriate layer. Changing the placement rule itself requires a new ADR.

## Rationale

Layer 1 is cheap: one migration, one JPA listener, zero per-action engineering work. It covers the most common forensic question — "who was the last actor on this row?" — for every entity in the system.

Layer 2 is preserved for the actions already using named columns, because migrating them to `audit_logs` would be churn without benefit. Dashboard queries that read `paid_changed_at` directly would become JSONB extractions from a joined table, with no compensating advantage.

Layer 3 absorbs the actions that do not fit naturally on a single entity: settings changes, role changes, and approval workflows that span multiple entities. These events also benefit from forensic context — `ip_address` and `user_agent` — that named columns do not capture.

The hybrid model means engineers have a clear, deterministic placement decision for every new auditable action, rather than a case-by-case judgment under implicit rules.

## Consequences

### Positive

- Universal last-touched tracking for every mutable row, with no per-action engineering cost.
- Named columns preserved for high-frequency per-resource queries — no performance regression on dashboards.
- Cross-resource and security-sensitive actions have first-class structured storage.
- New auditable actions have a deterministic placement decision, documented here.

### Negative

- Two retrieval paths exist for "show me the audit history of resource X." A UI that wants to show the complete history of an appointment must merge data from named columns (Layer 2) and `audit_logs` (Layer 3). Mitigation: `AuditService.getResourceHistory(businessId, resourceType, resourceId)` returns a unified DTO from both layers.
- `audit_logs` grows unbounded. At the expected volume — tens of events per day per business — this is a small table for years. Monitoring and a retention policy are required regardless.
- The Layer 3 table is append-only at the application layer. Enforcement at the database level (an INSERT-only role for the application user) is recommended.

### Neutral

- The retention and access control policies below are part of this decision. They are documented here rather than in a separate governance document to ensure the architectural constraints and the operational constraints stay in sync.

## Retention Policy

**Layer 1 and Layer 2**: retained for the lifetime of the parent row. No separate retention mechanism required.

**Layer 3 (`audit_logs`)**: retained indefinitely. At target volume the table remains small. GDPR right-to-erasure requests may require replacing customer-identifying content in `before`/`after` JSONB fields with `[REDACTED]` — the row is preserved, the personal data within it is not.

## Access Control

- **OWNER / ADMIN**: full read access to their business's `audit_logs` entries via a dedicated endpoint.
- **EMPLOYEE**: read-only access to entries where they are the actor — their own action history.
- **No role**: deletes from `audit_logs`. The table is append-only.

## Alternatives Considered

| Option | Why Rejected |
|---|---|
| Layer 1 only | Answers "who was last here?" but cannot reconstruct history — who made each of the last ten changes, in what order. Insufficient for settings-change or approval audit queries. |
| Layer 3 only | Dashboard queries that read named audit columns directly would degrade to JSONB extraction from a join. Performance regression on the most-trafficked surfaces for no architectural benefit. |
| Per-entity event tables (`appointment_events`, `blocked_slot_events`, …) | Every new auditable resource requires a new table, repository, and service. Cross-resource queries ("all actions by user X today") require N joins. Operational overhead scales with the number of entity types. |
| External audit store (log aggregation services only) | Forensic data must survive in primary storage for legal purposes. Retention contracts with external log providers are not a substitute for a queryable primary audit record. |

## Engineering Principle

Different categories of auditable event have different query patterns, different storage requirements, and different failure modes. Forcing all audit data into a single model produces a system that does none of the jobs well — either the general-purpose table degrades dashboard queries, or the named-column pattern becomes unworkable for cross-resource events. The correct response is to classify events by their query pattern at decision time, establish a clear placement rule, and enforce that rule through review rather than through convention. A two-path audit system with a documented decision rule is simpler to maintain than a single-path system where the path was chosen by default.

## Related

- [ADR-002](./ADR-002-blocked-slot-state-machine.md) — BlockedSlot state transitions (APPROVED, REJECTED, CANCELLED) are Layer 3 events; the named audit columns on `blocked_slots` are the Layer 2 complement
- [ADR-007](./ADR-007-bot-panel-derive-architecture.md) — the bot audit view relies on the Layer 2 named audit columns established by this decision; the operational vs forensic audit distinction in ADR-007 maps directly to the Layer 2 / Layer 3 split here
- [ADR-011](./ADR-011-appointment-temporal-boundary.md) — temporal boundary governs which appointment audit events are in the operational plane and which in the closure plane
- [ADR-004](./ADR-004-customer-lifecycle-states.md) — customer status transitions (archive, anonymisation) are Layer 3 events
- [Governance: audit-requirements.md](../governance/audit-requirements.md) — operational specification: what to audit per resource type, retention tiers, and role-based access control *(planned)*

## Source Code Reference

- `AppointmentEvent.java` *(published — SC-2)* — the mutation event published after every appointment state change; carries actor attribution (`triggeredBy`, `actorUserId`), correlation context (`correlationId` for bulk operations), and turn identifier (`turnId`) for the audit listener
- `AppointmentAuditLog.java` *(published — SC-2)* — the Layer 3 forensic audit entity for the appointment domain; append-only; `occurred_at` uses `TIMESTAMPTZ` (UTC); `actor_user_id` NULL for non-panel actors, enforced by `chk_audit_actor_user_id` database constraint
- `AppointmentAuditListener.java` *(published — SC-2)* — the synchronous `@EventListener` that writes the audit row atomically within the mutation's transaction; **committed ⟺ audited**; enforces `chk_audit_actor_user_id` redundantly at the application layer; builds structured `detail` JSON without free-text content (data minimisation)
- `AppointmentAuditFlowIT.java` *(published — SC-2)* — end-to-end integration test of the audit pipeline: verifies PANEL/OWNER, BOT, SCHEDULER, and bulk day-close attribution paths; confirms `correlationId` is shared across all appointments cancelled in the same bulk operation
- `BotWebhookIdempotencyIT.java` *(published — SC-2)* — integration tests for webhook idempotency: sequential duplicate retry (same `webhook_event_id` posted twice → same appointment, count 1), concurrent race (two threads with the same event ID → exactly one appointment persisted, UNIQUE INDEX is the authoritative arbiter), and cross-tenant isolation (same event ID in different tenants creates two independent appointments)
- `TurnCorrelationIT.java` *(published — SC-2)* — verifies the forensic `turn_id` contract: a valid UUID in the `X-Turn-Id` header is persisted to `audit_log.turn_id`; a missing or non-UUID header produces NULL (graceful degradation with no fabricated forensic identifiers); the same header across multiple mutations produces the same `turn_id` in all audit rows; re-anchor reads are correlated by `turnId` and contain no customer phone number
- `AppointmentAuditLogConstraintsIT.java` *(published — SC-6)* — database-level constraint verification for the audit schema: `chk_audit_actor_user_id` (panel actors require non-null `actor_user_id`; non-panel actors must have null), enum-bounded `event_type` and `triggered_by`, and append-only enforcement via `trg_appointment_audit_immutable`; uses raw `JdbcTemplate` inserts to verify constraints independently of the service stack
- `AuditLogService.java` — the single writer for the generic Layer 3 audit table (distinct from the domain-specific `appointment_audit_log`); called from event listeners for cross-resource events
- `BlockedSlotEventListener.java` — writes Layer 3 entries for block approval, rejection, and cancellation events
- `BusinessSettingsEventListener.java` — writes Layer 3 entries for all business settings changes
- `AuditService.getResourceHistory(...)` — unified DTO merging Layer 2 named columns and Layer 3 rows for a given resource
