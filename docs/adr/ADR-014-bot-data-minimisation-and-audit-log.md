# ADR-014 — Bot Data Minimisation and Appointment Audit Log

**Status:** Accepted  
**Date:** 2026-06-02  
**Domain:** privacy / audit  
**Editorial:** ADVANCED

> **Engineering Question Answered:** When a language model-driven interface receives structured data from the backend, how do you prevent sensitive fields from entering the model's context — and how do you build a forensic record of every appointment lifecycle event that preserves immutable accountability without retaining personal content that may later be subject to erasure?

---

## Problem

A legal and privacy review identified two separate structural deficiencies in the bot integration layer.

**Deficiency 1 — Oversized webhook responses.** The bot consumed the same `AppointmentResponse` DTO that the administration panel uses. This DTO included fields that the bot had no operational need for: internal service notes potentially containing sensitive content, payment status and payment metadata, internal user identifiers from cancellation records, and customer personal identifiers. The bot's language model received this full payload in its context on every response. The sensitive fields were excluded from the model's effective input at the orchestration layer, but this exclusion was enforced by the orchestration workflow — a downstream filter — not at the backend's output boundary. The invariant was correct but fragile: any change to the orchestration workflow could silently reintroduce the excluded fields into the model's context without a backend-layer test catching it.

**Deficiency 2 — No immutable appointment audit record.** A derived audit view existed that reconstructed the bot's operational history at query time from the `Appointment` table's current state. This view was useful for operational dashboards but did not constitute a forensic record: derived state reflects only the latest snapshot, not the sequence of who did what and when. For dispute resolution and accountability under data protection obligations, an immutable log of every appointment lifecycle event — with actor attribution — was absent.

## Context

The three-layer audit architecture documented in ADR-003 defines a generic `audit_logs` table (Layer 3) that absorbs cross-resource and security-sensitive events — settings changes, role changes, approval workflows. The `appointment_audit_log` described in this ADR is a distinct table, not a Layer 3 extension. It serves a different purpose: purpose-built forensic accountability for every transition in the appointment lifecycle, with richer appointment-domain structure and a stronger immutability guarantee than the generic audit infrastructure.

The bot and the administration panel share the same backend service layer. The same `AppointmentService` methods handle operations from both entry points. The entry points are distinguished by their authentication path: panel requests carry a JWT; bot requests carry a webhook key verified by HMAC signature. Actor attribution must work correctly for both paths.

## Decision

### Decision 1 — Slim boundary DTO for bot webhook responses

A dedicated response type — `WebhookAppointmentResponse` — is introduced for all webhook endpoints consumed by the bot. This type exposes only the fields the bot needs operationally:

- **Included:** appointment identifier, datetime, status, service name, offering name, price, duration, assigned employee name.
- **Excluded:** customer service notes, appointment notes, customer phone number, customer name, all internal entity identifiers (business, customer, offering, employee), payment status and payment metadata, cancellation actor identifiers, reschedule count.

The mapping to `WebhookAppointmentResponse` happens at the `WebhookController` boundary — the outermost layer before the response leaves the backend. The `AppointmentService` continues to produce its full internal representation; the slim DTO is a transformation at the boundary, not a change to internal service logic.

A dedicated integration test verifies that the webhook endpoints do not return the excluded fields. If a future change reintroduces any excluded field into the webhook response, the test fails at the boundary. The invariant is enforced by the backend, not by the orchestration layer.

### Decision 2 — Appointment audit log (append-only, purpose-built)

An `appointment_audit_log` table records every mutating operation in the appointment lifecycle:

- Operations recorded: creation, confirmation, decline, cancellation request, cancellation approval, cancellation rejection, reschedule, employee reassignment, status update, no-show recording, completion recording.
- Fields recorded per entry: business identifier, appointment identifier, event type, actor type (`SourceActor` enum), actor user identifier (nullable), previous status, new status, structured diff of changed fields, correlation identifier, timestamp in UTC.

The table is append-only. It accepts `INSERT` operations only. An enforcement trigger at the database level raises an exception on any `UPDATE` or `DELETE` attempt. The application's repository layer is insert-only by contract; the database trigger is the backstop that makes this guarantee independent of application code.

### Decision 3 — Domain event publishing with transactional write

Audit log entries are written via domain event listeners, not by instrumenting each service method individually. The `AppointmentService` already publishes domain events for most lifecycle transitions; the audit log is populated by listeners that subscribe to these events.

The audit write occurs within the same database transaction as the business operation. Mutation committed and audit record written are atomic: no appointment state change can be committed without a corresponding audit record, and no audit record can be written for an operation that failed and rolled back.

One gap in existing event coverage was identified and closed: the payment-toggle operation did not publish a domain event. A new event type was added for this operation to complete the audit coverage.

**Alternative considered and rejected — Hibernate Envers:** Envers provides automatic revision tracking but does not capture actor type without a custom revision listener, creates supplementary `_AUD` tables that contain the full row state rather than semantic diffs, and produces revision records that represent ORM-level changes rather than domain-meaningful events. For dispute resolution, a record that describes what business action was performed and by whom is more useful than a record that describes which database columns changed values.

### Decision 4 — Explicit actor parameter propagation

Actor attribution — who caused this operation — is resolved once at the entry point and passed as an explicit parameter to the event publishing call. It is not inferred from thread context.

The alternative — a thread-local actor context — was considered and rejected. Thread-local state in a thread-pool environment is a recognised source of incorrect attribution: if a thread is reused across requests without being properly cleared, the actor from the previous request's thread contaminates the attribution of the next request's event. For a forensic audit record, incorrect actor attribution is worse than no attribution.

Explicit parameter propagation eliminates this risk. The `SourceActor` enum defines the possible actor types: `OWNER`, `EMPLOYEE`, `ADMIN`, `CUSTOMER`, `BOT`, `SYSTEM`, `SCHEDULER`. The `actor_user_id` field is populated when the actor is an authenticated platform user (`OWNER`, `EMPLOYEE`, or `ADMIN`) and is null for all other actor types — customers, the bot, and automated jobs do not correspond to authenticated `User` entities.

A database check constraint enforces the semantic rule: `actor_user_id NOT NULL` if and only if `triggered_by` is one of the authenticated user types.

### Decision 5 — No personal content in the audit record

The audit log is immutable. Personal content stored in an immutable record creates a permanent conflict with the right to erasure under data protection law: if a customer exercises their erasure right, any personal content in the append-only log cannot be removed.

The audit log avoids this conflict by design:

- **Free-text fields** (appointment notes, service notes): the audit record stores only a flag indicating whether a reason was provided and which actor type provided it. The content itself is never written to the audit log.
- **Structured fields** (status, assigned employee, datetime, offering): before and after values are recorded as they are not personal data.
- **Customer identifiers**: the audit record carries the appointment identifier, not the customer's name or phone number. The customer's identity can be derived from the appointment record if needed; it need not be duplicated in an immutable log.

### Decision 6 — No foreign key constraints on historical identifiers

The `appointment_audit_log` records `appointment_id` and `actor_user_id` without database foreign key constraints. This is intentional.

A foreign key with `ON DELETE SET NULL` or `ON DELETE CASCADE` would cause write operations against historical rows when the referenced record is deleted — a mutation of the audit record after the fact. For a forensic log whose immutability guarantee is enforced at the database level, a foreign key that triggers downstream row modifications is architecturally contradictory. The identifiers in the audit log are historical facts; they remain accurate even if the records they reference are later deleted. Application-layer logic maintains referential correctness; the database-level immutability guarantee takes precedence over referential enforcement.

## Alternatives Considered

| Option | Why Rejected |
|---|---|
| Filter sensitive fields at the orchestration layer only | Enforcement depends on the orchestration workflow being correctly maintained. A workflow change can silently reintroduce excluded fields. Backend boundary enforcement is independent of the orchestration layer and is verifiable by automated tests. |
| Extend the generic `audit_logs` table (ADR-003 Layer 3) | The generic table is designed for cross-resource events with security forensic context. Appointment lifecycle events benefit from appointment-domain-specific structure: status transitions, before/after diffs keyed to appointment fields, and actor attribution that distinguishes `CUSTOMER`, `BOT`, and `SCHEDULER` as first-class types alongside authenticated users. Squeezing this into the generic table would produce JSONB fields where typed columns are more useful. |
| Derive audit history from the `Appointment` table at query time | This is the approach already in use for the operational bot audit view (ADR-007). Derived state is not a forensic record: if an appointment is updated, the previous state is lost. A dispute about what happened and when requires a record of what the state was at each point in time, not just what it is now. |
| Record the full text content of cancellation reasons and notes | Immutable log entries cannot be erased. Any personal content in a cancellation reason — a customer's explanation for why they cannot attend — would be permanently retained regardless of erasure requests. The flag-only approach records the fact that a reason existed without retaining its content. |

## Consequences

### Positive

- Sensitive fields — service notes, appointment notes, personal identifiers — are structurally absent from the language model's context. The exclusion is enforced at the backend boundary and verified by an automated test; it does not depend on any orchestration-layer filter remaining correctly configured.
- Every appointment lifecycle event has a forensic record: who performed the action, in what role, at what time, with what before/after state. The record is immutable by database-level enforcement, not convention.
- Actor attribution is explicit and type-safe. The `SourceActor` enum distinguishes human operators from the bot, from automated jobs — a distinction that matters for accountability in a system where the same appointment can be modified by all three.
- The audit log introduces no erasure conflict. Personal content that may later be subject to erasure is not written to the append-only table.

### Negative

- The webhook response exposes less information to the orchestration layer than the full response DTO provides. Orchestration logic that previously relied on excluded fields must be updated to not require them. Fields that are operationally necessary for the bot were verified and preserved; no legitimate use case was broken.
- The synchronous audit write within the business transaction means that an audit write failure rolls back the business operation. This is the correct behaviour for forensic integrity, but it means a transient database issue affecting the audit log also prevents the appointment operation from completing. The audit table is simple (insert-only, no computed values) and this failure mode is expected to be rare.
- The absence of foreign key constraints on `appointment_id` and `actor_user_id` means database referential integrity tooling cannot detect orphaned audit records. This is an accepted trade-off for immutability.

### Neutral

- The administration panel continues to receive the full `AppointmentResponse` DTO. The minimisation applies exclusively to the bot's webhook path.

## Engineering Principle

Data minimisation at a system boundary is not a filter applied after the fact — it is a contract enforced at the boundary itself. A filter that lives downstream of the boundary depends on the correctness of everything between the boundary and the filter; a contract enforced at the boundary depends only on the boundary. These are different guarantees with different reliability properties. When the excluded data is sensitive and the exclusion is a legal requirement, the difference matters. Similarly, an audit record that depends on the current state of the database is not a record of what happened — it is a record of what the current state implies happened. Immutability is not a performance optimisation or a convenience; it is the property that makes an audit log useful as evidence.

## Related

- [ADR-003](./ADR-003-hybrid-audit-strategy.md) — the three-layer audit architecture; the generic `audit_logs` table (Layer 3) is distinct from the `appointment_audit_log` described here; the two tables serve different purposes and are not interchangeable
- [ADR-007](./ADR-007-bot-panel-derive-architecture.md) — the bot audit view derives from the `Appointment` table at query time; ADR-014's append-only log is the forensic complement to that operational view
- [ADR-015](./ADR-015-art9-gdpr-minimisation-conversational-flow.md) — the enforcement that notes from the bot path are never persisted; ADR-014 records that the operation occurred, not the content of any notes provided
- [ADR-006](./ADR-006-user-identity-model.md) — the `User` entity and `SourceActor` attribution; `actor_user_id` is non-null only for authenticated users (OWNER, EMPLOYEE, ADMIN) in the identity model

## Source Code Reference

- `AppointmentAuditLog.java` *(published — SC-2)* — the append-only forensic audit entity; `occurred_at` uses `TIMESTAMPTZ`; no direct PII (references appointment by id); `detail` column holds structured JSON-as-text metadata only, never free-text content; `actor_user_id` NULL for CUSTOMER/BOT/SYSTEM/SCHEDULER
- `AppointmentAuditListener.java` *(published — SC-2)* — the synchronous `@EventListener`; writes the audit row within the same transaction as the mutation; the cancellation reason is reduced to `{reasonProvided, reasonOrigin}` in `detail`, never the reason content; `actor_user_id` is forced to null for non-panel roles
- `AppointmentEvent.java` *(publish