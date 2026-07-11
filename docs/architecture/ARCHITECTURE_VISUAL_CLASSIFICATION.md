# Architecture Visual Classification

**Journey:** Architecture Experience Journey  
**Document version:** 1.0  
**Date:** 2026-07-11  
**Status:** Canonical

> This document defines the taxonomy governing every visual artefact in the Architecture Experience Journey. Every diagram published in this Journey must belong to one of the seven categories defined here. Category membership determines placement, publication criteria, and maintenance expectations.

---

## How to Use This Document

When a new visual artefact is proposed:

1. Identify the engineering question it answers.
2. Match the question to the category whose purpose it serves.
3. Verify the artefact meets the publication criteria for that category.
4. Place it according to the placement rules for that category.

If a proposed artefact does not fit any category, or fits multiple categories equally, it should be decomposed into two artefacts — one per question — or rejected.

---

## Category Index

| Category | Engineering question type | Primary placement |
|---|---|---|
| [FOUNDATIONAL](#foundational) | What is this system? | `docs/architecture/ARCHITECTURE.md` |
| [ARCHITECTURE](#architecture) | How is the system structured? | `docs/architecture/ARCHITECTURE.md` |
| [DOMAIN](#domain) | How is the domain modelled? | `docs/architecture/DATA_MODEL.md` |
| [GOVERNANCE](#governance) | What transitions and actions are legal? | `docs/governance/` |
| [RUNTIME](#runtime) | What happens step by step when X occurs? | `docs/architecture/` or `docs/engineering/` |
| [SECURITY](#security) | Where is the security boundary and what does it enforce? | `docs/architecture/SECURITY.md` |
| [REFERENCE](#reference) | Given X, what is Y? | `docs/architecture/` or `docs/governance/` |

---

## FOUNDATIONAL

### Purpose

FOUNDATIONAL diagrams orient the reader to the system before any domain knowledge is assumed. They answer the question "what is this system and what does it connect to?" in the first 10–30 seconds of a reading session. A reader who has never heard of VookedMe should be able to form an accurate high-level mental model from a FOUNDATIONAL diagram alone.

### Engineering Question Answered

- What actors interact with this system?
- What external systems does it integrate with?
- What data crosses each boundary?
- What is the system's scope?

### Expected Complexity

**Low.** FOUNDATIONAL diagrams deliberately exclude domain detail. No entity names, no state names, no implementation detail. Only system boundaries, external actors, and integration relationships. A reader with no domain context should be able to understand the diagram completely.

### Publication Criteria

- Reviewed for accuracy of external system names and integration types
- No internal identifiers, domain-specific labels, or implementation detail
- Consistent with the deployment model described in the README
- Every external actor named in the diagram is mentioned in the README
- Caption states the engineering question and links to the relevant ADR (if applicable) or the README

### Examples

- System Context Diagram (C4 Level 1): WhatsApp customer → Evolution API → n8n → Spring Boot backend → PostgreSQL

---

## ARCHITECTURE

### Purpose

ARCHITECTURE diagrams show the internal structure of the system at component or container level. They expand beyond the system boundary to show the major deployable units and their responsibilities, or the major internal components and their relationships. They answer "how is the system decomposed and how do the parts communicate?" for a reader who has already understood the system boundary.

### Engineering Question Answered

- What are the major components or containers in this system?
- What does each component own and what does each produce?
- How do the components communicate at runtime?
- What is the end-to-end path for the system's primary use case?

### Expected Complexity

**Medium.** Shows internal structure but not implementation detail. Component names match Java package names where components have published source artefacts. Communication paths are annotated with protocol (HTTP, webhook, PostgreSQL query) but not with method signatures or SQL.

### Publication Criteria

- Every component named in the diagram has either a published source artefact or a clearly labelled external system designation
- Communication paths annotated with protocol type
- No method signatures, no field names, no SQL
- Consistent with the system context diagram (every actor from FOUNDATIONAL appears where applicable)
- Caption states the engineering question and links to the relevant ADR

### Examples

- Bot-to-Backend Runtime Flow: the end-to-end path from a WhatsApp customer message to a committed database write, across Evolution API, n8n, and Spring Boot

---

## DOMAIN

### Purpose

DOMAIN diagrams show how the domain is modelled — what entities exist, what relationships they carry, and what structural invariants they enforce. They answer "how is the domain represented?" for an engineer who needs to understand the data model before reading the source code.

### Engineering Question Answered

- What are the core domain entities?
- What are the load-bearing FK relationships?
- How is multi-tenancy expressed in the schema?
- Which entities are audit entities, which are lifecycle entities, which are configuration entities?

### Expected Complexity

**Medium-High.** Entity relationship diagrams can be dense. DOMAIN diagrams must be filtered to architecturally significant relationships. An entity's complete column list is out of scope — only the entity name, its role, and its FK relationships appear. Column-level detail lives in the Flyway migration files.

### Publication Criteria

- Every entity in the diagram has either a published source artefact or a Flyway migration
- Column detail is excluded — only entity names, cardinality, and relationship types
- Multi-tenancy anchoring (`business_id` on every business-scoped entity) is visible
- Audit entities (those that record history rather than current state) are visually distinguished
- Caption states the engineering question and links to the relevant ADRs (ADR-001, ADR-004, ADR-006, ADR-008 where applicable)

### Examples

- Core Entity Relationship Diagram: Business, User, Customer, Appointment, Offering, Schedule, BlockedSlot, RefreshToken, AppointmentAuditLog, CustomerLegitimationAuditLog, ConsentAudit

---

## GOVERNANCE

### Purpose

GOVERNANCE diagrams show formal rules: state machines, permission matrices, and lifecycle constraints. They are the visual representations of rules that the system enforces — not descriptions of what the system does, but definitions of what it is allowed to do. They serve as authoritative references that engineers consult when implementing or reviewing transitions.

### Engineering Question Answered

- What are the legal states of entity X?
- What transitions are permitted and by whom?
- What is the entry condition for state Y?
- What makes a transition from A to B illegal?

### Expected Complexity

**Medium.** State machines are dense but bounded: the state count is small and the transition table is finite. GOVERNANCE diagrams must not speculate — every state and every transition must be documented in an accepted ADR. Actor annotations on transitions (who can trigger this) are included. Terminal state notation is explicit.

### Publication Criteria

- Every state in the diagram corresponds to a published enum value in a source artefact
- Every transition corresponds to a row in an ADR's transition table
- Actor annotations match the ADR's transition rules
- Terminal states are visually distinguished
- Caption states the engineering question and links to the defining ADR
- After publication, the corresponding ADR's Source Code Reference section is updated to link to this diagram
- After publication, the forward references in any ADR pointing to this governance document are resolved

### Examples

- Appointment FSM + Temporal Boundary: six-state FSM with `appointment.datetime` boundary line, operational/closure plane annotations, PFT rule labels on boundary-crossing transitions
- BlockedSlot FSM (standalone): five-state lifecycle with actor annotations and APPROVED-only calendar visibility annotation

---

## RUNTIME

### Purpose

RUNTIME diagrams show the sequence of operations during a specific request or workflow. They answer "what happens step by step when X occurs?" by tracing the lifecycle of a request across system components. RUNTIME diagrams are scenario-specific: each diagram covers exactly one coherent scenario.

### Engineering Question Answered

- What is the sequence of operations for workflow X?
- In what order are components invoked?
- Where is invariant Y checked in the execution path?
- What happens at step Z when the failure condition is triggered?

### Expected Complexity

**Medium-High.** Sequence diagrams can become long. RUNTIME diagrams must be scoped to a single coherent scenario (one request type, one workflow, one failure branch). If a scenario has a happy path and three failure branches, they should be separate diagrams unless the branches are trivially short. Participant names match Java class names where possible.

### Publication Criteria

- Covers a scenario documented in at least one ADR
- Scoped to a single coherent scenario
- Happy path is always included; failure paths are included where they illustrate the key invariant
- Participant names match source artefact names where applicable
- No implementation detail (method bodies, SQL, configuration values)
- Caption states the specific scenario covered and the engineering question, with link to the relevant ADR

### Examples

- JWT Refresh Token Rotation Sequence: happy-path rotation and the reuse-detection branch (ADR-018)
- OutboundLegitimacyGate Flow: ALLOW and BLOCK paths for an outbound communication attempt

---

## SECURITY

### Purpose

SECURITY diagrams show security architecture: enforcement gates, filter chains, isolation boundaries, and the structural guarantees they provide. They answer "where is the security boundary and what does it enforce?" for engineers reviewing the security model. SECURITY diagrams must be precise about exit conditions: what happens when a check fails, not just when it succeeds.

### Engineering Question Answered

- Where is the security boundary?
- What is the order of security checks for an incoming request?
- What is the exit condition when check X fails?
- What makes cross-tenant data access structurally impossible?

### Expected Complexity

**Medium.** Security filter chains are linear and bounded. Isolation gates are architecturally simple but require precise annotation of what they check and what they reject. Every exit condition from a security check must be annotated with its HTTP response code and the principle it enforces.

### Publication Criteria

- Every filter or gate in the diagram has a published source artefact
- Exit conditions annotated with HTTP response code
- The structural guarantee of each check is stated (what it makes impossible, not just what it blocks)
- No credential values, no token content, no secret material
- Ordered correctly — the diagram reflects the actual filter chain registration order in the source
- Caption states the engineering question and links to the relevant ADR (ADR-016, ADR-018 where applicable)

### Examples

- Tenant Isolation Architecture: HTTP request → `JwtAuthenticationFilter` → `AuthorizationService` gate → service layer with explicit `businessId` propagation
- Security Filter Chain: the six filters in registration order with exit conditions
- JWT Refresh Token Rotation Sequence: positioned in RUNTIME but also relevant here for the security properties it enforces

---

## REFERENCE

### Purpose

REFERENCE artefacts provide structured lookup tables for stable, bounded information. They answer "given X, what is Y?" — the question a practitioner asks repeatedly while implementing or reviewing. REFERENCE artefacts are consulted rather than read; they are optimised for scan speed rather than narrative flow.

### Engineering Question Answered

- Which audit layer does action X belong to?
- Which role can trigger transition Y on entity Z?
- What is the retention policy for layer X data?

### Expected Complexity

**Low-Medium.** REFERENCE artefacts are tables and matrices, not flow diagrams. Rows and columns must have clear, consistent labels. Every cell must be verifiable against source or governance documents. REFERENCE artefacts should not need prose explanation — if a cell requires a footnote, it is a signal that the underlying concept needs an ADR, not a table entry.

### Publication Criteria

- Every row and cell is verifiable against a published source artefact, an accepted ADR, or a published governance document
- No ambiguous cells — each cell should have exactly one correct value
- Column and row headers are the canonical names used in the corresponding ADRs and source artefacts
- Caption states the lookup question and links to the relevant ADR
- Does not duplicate information already clearly expressed in an ADR's decision table — must provide a presentation advantage (faster scan, cross-cutting view)

### Examples

- Three-Layer Audit Catalogue: action × audit layer mapping with placement rationale (ADR-003)
- Repository Reading Map: role × time-budget × entry point matrix
