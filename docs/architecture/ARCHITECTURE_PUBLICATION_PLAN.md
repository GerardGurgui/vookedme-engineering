# Architecture Publication Plan

**Journey:** Architecture Experience Journey  
**Document version:** 1.0  
**Date:** 2026-07-11  
**Status:** Canonical

> This document is the publication roadmap for the Architecture Experience Journey. It defines six batches, their contents, dependencies, rationale, and effort. No visual artefact is published until its batch is executed.

---

## Batch Overview

| Batch | Name | Assets | Category | Priority | Effort | Status |
|---|---|---|---|---|---|---|
| AX-1 | Context & System Entry | System Context, Bot-to-Backend Flow | FOUNDATIONAL + ARCHITECTURE | Critical | Low-Medium | Planned |
| AX-2 | Domain Model | Core Entity Relationship Diagram | DOMAIN | Critical | Medium | Planned |
| AX-3 | FSM & Temporal Boundary | Appointment FSM + Boundary, BlockedSlot FSM | GOVERNANCE | Critical | Medium | Planned |
| AX-4 | Security Architecture | Tenant Isolation, Filter Chain, JWT Rotation | SECURITY | High | Low-Medium | Planned |
| AX-5 | Audit & Compliance | Three-Layer Audit, Legitimation Gate | RUNTIME + REFERENCE | Medium | Medium | Planned |
| AX-6 | Navigation | Repository Reading Map | REFERENCE | Low-Medium | Low | Planned |

---

## AX-1 — Context & System Entry

### Rationale

AX-1 answers the first question every reader has before reading anything else. It is the visual equivalent of the README's opening paragraph: what is this system, what does it connect to, and what is its most distinctive runtime behaviour? No reader should encounter domain detail before they have a system boundary.

AX-1 is the prerequisite for every subsequent batch. Every other diagram assumes the reader knows what the system is. Without AX-1, diagrams in later batches are context-free.

### Assets

**Asset AX-1-A — System Context Diagram**
Category: FOUNDATIONAL
Engineering question: What is this system, who uses it, and what external systems does it integrate with?
Content: Customer actor (WhatsApp), business owner actor (administration panel), Evolution API (messaging infrastructure), n8n (orchestration layer), Spring Boot backend (the system under review), PostgreSQL (persistence). Boundary annotations showing what data crosses each integration point.
Placement: `docs/architecture/ARCHITECTURE.md` — first visual, before any prose in that document.

**Asset AX-1-B — Bot-to-Backend Runtime Flow**
Category: ARCHITECTURE
Engineering question: What is the complete runtime path from a WhatsApp customer message to a committed database write, and where does the boundary between probabilistic interpretation and deterministic enforcement sit?
Content: Customer message → Evolution API webhook dispatch → n8n orchestration (LLM interpretation, state derivation per ADR-007) → webhook call with HMAC signature → Spring Boot (idempotency check, FSM transition validation, audit write, notification dispatch) → PostgreSQL. Key invariant annotations: "LLM interprets" at n8n boundary; "backend decides" at Spring Boot boundary; HMAC verification point; idempotency checkpoint.
Placement: `docs/architecture/ARCHITECTURE.md` — second visual, after the system context.
ADR cross-reference: ADR-007, ADR-014.

### Deliverable

`docs/architecture/ARCHITECTURE.md` — first population of this document.

### Dependencies

None. AX-1 has no prerequisite visual assets. Every element named in AX-1-A and AX-1-B is already documented in the README and ADRs.

### Effort

Low (AX-1-A: System Context is a bounded, low-complexity diagram). Medium (AX-1-B: Bot-to-Backend spans three systems and requires careful annotation of invariants without introducing implementation detail).

---

## AX-2 — Domain Model

### Rationale

AX-2 answers the question that all technical readers need answered before they can evaluate any other diagram: how is the domain modelled? The entity graph is the vocabulary that every subsequent visual artefact assumes. Without the ERD, a reader encountering `AppointmentAuditLog` or `CustomerLegitimationAuditLog` in later diagrams has no anchor.

AX-2 should follow AX-1 because the system context (AX-1-A) establishes what the system IS before the domain model establishes how it is represented. Publishing the domain model without the system context leaves readers without orientation.

### Assets

**Asset AX-2-A — Core Entity Relationship Diagram**
Category: DOMAIN
Engineering question: What are the core domain entities, what are their load-bearing relationships, and how is multi-tenancy expressed structurally in the schema?
Content: Business, User, Customer, Appointment, Offering, Schedule, BlockedSlot, RefreshToken, AppointmentAuditLog, CustomerLegitimationAuditLog, ConsentAudit. Relationships shown with cardinality. `business_id` FK on all business-scoped entities explicitly annotated. Audit entities (those recording history, not current state) visually distinguished. Column-level detail excluded.
Placement: `docs/architecture/DATA_MODEL.md` — sole visual in that document.
ADR cross-reference: ADR-001 (money field on Appointment), ADR-004 (Customer lifecycle), ADR-006 (User identity), ADR-008 (timestamp strategy), ADR-016 (tenant isolation expressed via `business_id`).

### Deliverable

`docs/architecture/DATA_MODEL.md` — first population of this document.

### Dependencies

AX-1 (system context establishes reader orientation before domain detail is introduced). AX-2 can be executed in parallel with AX-1 in practice, but should be presented to readers after AX-1.

### Effort

Medium. The entity list is defined by the published source artefacts. The diagram requires a deliberate filtering decision: which relationships are architecturally load-bearing and which are implementation detail? 78 Flyway migrations mean the full schema is larger than the diagram should show. The filtering discipline is the engineering work in this batch.

---

## AX-3 — FSM & Temporal Boundary

### Rationale

AX-3 produces the repository's flagship visual artefact: the Appointment FSM + Temporal Boundary combined diagram. This is the only diagram in the Journey that does not exist in any form elsewhere. The six-state FSM (ADR-017) and the temporal boundary principle (ADR-011) are documented in separate ADRs, each with an embedded Mermaid. No combined version exists. The combination — FSM + boundary line + PFT annotations + operational/closure plane labels — answers the question that makes the ADR-011 architecture immediately legible.

AX-3 also closes the four unresolved forward references in the ADR set. ADR-002, ADR-004, ADR-011, and ADR-017 all reference `docs/governance/state-machines.md` as `*(planned)*`. AX-3 publishes that document.

AX-3 should follow AX-2 because the FSM diagrams reference entity names (Appointment, BlockedSlot) that readers should have already encountered in the ERD. In practice, the FSM diagrams are self-contained enough that this dependency is soft.

### Assets

**Asset AX-3-A — Appointment FSM + Temporal Boundary (Combined)**
Category: GOVERNANCE
Engineering question: What are the six legal states of an appointment, what transitions are permitted and by whom, and how does `appointment.datetime` divide the lifecycle into two operationally distinct planes with different rules?
Content: Six states — PENDING, CONFIRMED, CANCELLATION_REQUESTED, CANCELLED, COMPLETED, NO_SHOW. All valid transitions with actor annotations. A vertical boundary marker at `appointment.datetime`. Operational plane (left of boundary) and Closure plane (right of boundary) annotated. PFT rule references (PFT-1 through PFT-7) on boundary-crossing operations. Terminal state notation.
Placement: `docs/governance/state-machines.md` — first visual in that document.
ADR cross-reference: ADR-017 (FSM design), ADR-011 (temporal boundary principle).

**Asset AX-3-B — BlockedSlot FSM (Standalone)**
Category: GOVERNANCE
Engineering question: What are the five legal states of a blocked slot, what transitions are permitted and by whom, and which state is the sole determinant of calendar availability?
Content: Five states — REQUESTED, APPROVED, REJECTED, CANCELLED, EXPIRED. Transitions with actor annotations. APPROVED-only calendar visibility invariant annotated. Terminal state notation.
Placement: `docs/governance/state-machines.md` — second visual in that document.
ADR cross-reference: ADR-002 (BlockedSlot state machine).

### Deliverable

`docs/governance/state-machines.md` — first population of this document, closing four *(planned)* forward references from ADR-002, ADR-004, ADR-011, ADR-017.

### Dependencies

AX-1 (context), AX-2 (entity vocabulary helpful for first-time readers of the governance document).

### Effort

Medium (AX-3-A: the combined diagram requires designing a visual that does not yet exist anywhere in the repository — the synthesis of ADR-017 and ADR-011 into a single diagram is the primary design challenge). Low (AX-3-B: content is well-defined in ADR-002; translation to standalone is straightforward).

---

## AX-4 — Security Architecture

### Rationale

AX-4 addresses the security architecture — the third load-bearing dimension of the system after domain model (AX-2) and FSM/temporal boundary (AX-3). The three security visuals form a coherent set that engineers reviewing the security model need together: how tenant data is isolated, what the request pipeline looks like, and how sessions are protected against theft.

All three visuals in AX-4 have highly stable content — the security architecture is not expected to change. The filter chain order and the tenant isolation gate are established in published source artefacts.

AX-4 should follow AX-1 (system context provides the architectural frame) and AX-2 (the tenant isolation diagram references tenant-scoped entities by name). AX-4 is independent of AX-3.

### Assets

**Asset AX-4-A — Tenant Isolation Architecture**
Category: SECURITY
Engineering question: How does the system ensure that data belonging to one tenant is structurally inaccessible to another, and where exactly in the request lifecycle is this enforced?
Content: HTTP request → `JwtAuthenticationFilter` (extracts `userId` from token) → `AuthorizationService.requireMembership()` gate (the single enforcement point) → service layer with `businessId` propagated as explicit parameter → PostgreSQL queries with `business_id` filter. Structural impossibility annotations: "business identity derived from token, never from request body"; "default deny — any non-member request is rejected with 403".
Placement: `docs/architecture/SECURITY.md` — first visual.
ADR cross-reference: ADR-016 (tenant isolation pattern).

**Asset AX-4-B — Security Filter Chain**
Category: SECURITY
Engineering question: What is the complete security pipeline for an incoming HTTP request, in what order are security checks applied, and what is the exit response when each check fails?
Content: Six filters in registration order: `RateLimitingFilter` (429 on excess), `JwtAuthenticationFilter`/`WebhookApiKeyFilter` (401 on invalid credential), `LegalAcceptanceFilter` (403 on terms not accepted), `WebhookSignatureFilter` (401 on invalid HMAC), `TurnCorrelationFilter` (graceful degradation, no exit). Annotation: which filters apply to panel requests vs. webhook requests.
Placement: `docs/architecture/SECURITY.md` — second visual.
ADR cross-reference: ADR-016 (authentication), ADR-018 (JWT), ADR-014 (webhook signature).

**Asset AX-4-C — JWT Refresh Token Rotation Sequence**
Category: SECURITY (sequence diagram — touches RUNTIME but primarily a security artefact)
Engineering question: What is the complete token rotation lifecycle, and at what exact point is token theft detected and contained?
Content: Happy path: client presents refresh token → server validates `jti` against database → token revoked → new token issued → returned to client. Theft detection branch: client presents already-revoked token → server detects reuse → ALL tokens for that user revoked → total session termination.
Placement: `docs/architecture/SECURITY.md` — third visual.
ADR cross-reference: ADR-018 (JWT refresh token rotation).

### Deliverable

`docs/architecture/SECURITY.md` — first population of this document.

### Dependencies

AX-1 (system context), AX-2 (entity vocabulary for tenant-scoped entities). Independent of AX-3.

### Effort

Low (AX-4-A and AX-4-B: content is precisely specified in ADRs and published source; translation to visual is straightforward). Medium (AX-4-C: the sequence diagram requires careful scoping of the failure branch without overcomplicating the happy path).

---

## AX-5 — Audit & Compliance

### Rationale

AX-5 addresses two cross-cutting concerns that the ADRs document rigorously but that benefit from visual compression: the three-layer audit architecture (ADR-003) and the outbound legitimation gate (consent enforcement architecture). These are the most GDPR-relevant visuals in the repository.

AX-5 has lower priority than AX-1 through AX-4 because the ADR prose for both concepts is already highly legible — the visual is additive rather than prerequisite. An engineer reading ADR-003 can understand the three-layer architecture without a diagram. The diagram makes it faster, not possible.

### Assets

**Asset AX-5-A — Three-Layer Audit Architecture**
Category: REFERENCE
Engineering question: Which category of event goes to which audit layer, and how does a single appointment operation produce entries in multiple layers simultaneously?
Content: Three layers — L1 (`@PreUpdate` universal tracking), L2 (named audit columns per entity), L3 (`audit_logs` table). A representative flow showing one appointment cancellation producing: L1 `updated_by` stamp, L2 `cancelled_*` column write, L3 `audit_logs` row. Placement decision rule annotated.
Placement: `docs/architecture/ARCHITECTURE.md` — added to existing document as a third visual.
ADR cross-reference: ADR-003 (three-layer audit architecture).

**Asset AX-5-B — OutboundLegitimacyGate Flow**
Category: RUNTIME
Engineering question: What is the complete execution path of an outbound communication attempt, and where exactly is consent enforced before any message is dispatched?
Content: Notification queued → `OutboundLegitimacyGate.evaluate(customer)` → `CustomerLegitimacyService` computes `LegitimationState` → ALLOW branch (message dispatched) / BLOCK branch (message suppressed, suppression event logged to `CustomerLegitimationAuditLog`).
Placement: `docs/engineering/CUSTOMER_LEGITIMATION.md` — first visual in that deep-dive.
ADR cross-reference: No dedicated ADR exists for the legitimation gate — the ADR for this would be the recommended next ADR if the ADR Journey were extended.

### Deliverable

`docs/architecture/ARCHITECTURE.md` updated with AX-5-A. `docs/engineering/CUSTOMER_LEGITIMATION.md` first population with AX-5-B.

### Dependencies

AX-1 (context), AX-2 (entity vocabulary — audit log entities appear in AX-5-A), AX-3 (FSM context — appointment states appear in the audit flow).

### Effort

Low (AX-5-A: the ADR-003 table already specifies every layer; the visual translates a table to a flow). Medium (AX-5-B: the legitimation gate flow requires tracing published source artefacts across `OutboundLegitimacyGate`, `CustomerLegitimacyService`, `LegitimationState`, and `CustomerLegitimationAuditLog` to produce a coherent sequence).

---

## AX-6 — Navigation

### Rationale

AX-6 is the last batch because the Repository Reading Map references everything that exists. A reading map built before its content is complete would require constant revision. Published last, it can be stable.

### Assets

**Asset AX-6-A — Repository Reading Map**
Category: REFERENCE
Engineering question: Where does a reader with role X and time budget Y start, and what is the recommended sequence?
Content: A matrix or flow showing three reading levels (10 seconds / 5 minutes / 30 minutes) against three reader profiles (Staff Backend Engineer, Software Architect, Technical Recruiter / Hiring Manager). Each cell maps to specific documents in the repository. Entry points are the first visual in each Architecture batch.
Placement: `docs/README.md` or as a standalone `docs/meta/reading-guide.md` referenced from there.

### Deliverable

`docs/README.md` updated with the reading map, or a new `docs/meta/reading-guide.md`.

### Dependencies

AX-1 through AX-5. The reading map cannot be accurate until the content it maps exists.

### Effort

Low. The content is fully determined by the documents that exist at this point. The design work is choosing the matrix format and determining the minimum set of entry points per profile.

---

## Dependency Graph

```
AX-1 (Context & System Entry)
  ↓
AX-2 (Domain Model) ←→ AX-3 (FSM & Temporal Boundary)
  ↓                          ↓
AX-4 (Security Architecture)
  ↓
AX-5 (Audit & Compliance)
  ↓
AX-6 (Navigation)
```

AX-2 and AX-3 are independent of each other and can be executed in either order. All other batches follow the dependency chain.

---

## Publication Order Summary and Rationale

**AX-1 first** because it is the visual prerequisite for every other batch. Without a system context, all subsequent diagrams are context-free.

**AX-2 and AX-3** are the two highest-impact batches after AX-1. AX-3 contains the repository's flagship visual (the combined FSM + Temporal Boundary) and closes four unresolved ADR forward references. AX-2 provides the entity vocabulary that makes every other diagram readable. Both should be published before AX-4 because security diagrams reference tenant-scoped entities (AX-2) and the audit flow in AX-5 references appointment states (AX-3).

**AX-4 after AX-2** because the tenant isolation diagram references entity names that readers should recognise. AX-4 is independent of AX-3.

**AX-5 after AX-3** because the audit architecture flow (AX-5-A) references appointment states that readers should already understand from the FSM diagram.

**AX-6 last** because the navigation map references everything. It has no engineering content of its own — it is pure editorial infrastructure.
