# Architecture Experience Audit

**Journey:** Architecture Experience Journey  
**Document version:** 1.0  
**Date:** 2026-07-11  
**Status:** Canonical

> This document is the architectural equivalent of `docs/source/SOURCE_PUBLICATION_AUDIT.md`. It establishes the justification, scope, and editorial principles for the Architecture Experience Journey before a single visual artefact is produced.

---

## 1. Why This Journey Exists

### 1.1 The completion problem

At v1.4.0, this repository contains:

- 17 Architecture Decision Records documenting every significant design decision
- 50 production source artefacts demonstrating those decisions in code
- 23 test artefacts specifying domain contracts in executable form
- A complete editorial framework (Foundation Phase): standards, publication pipeline, release strategy

The Foundation, ADR, and Source Code Journeys have together produced a repository with exceptional technical depth. An engineer who reads all seventeen ADRs holds a precise and accurate understanding of every architectural decision in this system. The trade-off is time investment: that understanding requires several thousand words and a sustained reading session.

**What no phase has addressed:** the repository has no visual entry point. There is no diagram, no schema, no flow, no map that a reader can absorb before reading the first word. The `docs/architecture/` directory has been a placeholder since v0.1.0. Four ADRs contain forward references to `docs/governance/state-machines.md` that do not resolve.

The Architecture Experience Journey exists to close this gap.

### 1.2 Why visual artefacts are highest-value now

Visual artefacts built before the technical foundation is complete require constant revision as the system evolves. Built after the foundation is stable, they represent decisions that will not change.

At v1.4.0:
- Every ADR is published and accepted
- Every source artefact is published and sanitised
- Every domain concept has a formal record
- The editorial standards are established

The content that visual artefacts will represent is now frozen. This is the correct moment to build them.

### 1.3 The sequencing principle

The three Journeys follow a deliberate sequencing:

1. **ADR Journey** — establishes *why*. Every design decision is recorded before visuals attempt to represent it.
2. **Source Code Journey** — establishes *how*. Every implemented concept has a published source artefact before visuals reference it.
3. **Architecture Experience Journey** — establishes *what*. Every visual is built from stable, accurate source material.

A visual that precedes its ADR is speculation. A visual that precedes its source is unverified. A visual built from both is documentation.

---

## 2. Problems This Journey Solves

**Problem 1 — No visual entry point at any reading depth.**
A reader who opens the repository at the 10-second level (a recruiter scanning quickly), the 5-minute level (an engineering manager evaluating depth), or the 30-minute level (a staff engineer exploring the domain) all face the same obstacle: they must read prose before forming any mental model. Visual artefacts serve all three reading levels simultaneously.

**Problem 2 — `docs/architecture/` has been a placeholder since v0.1.0.**
The release strategy document designated `docs/architecture/` for population at v0.2.0. It was not populated during the ADR or Source Code Journeys because those Journeys were correctly sequenced to establish content before representation. The Architecture Experience Journey finally populates this directory.

**Problem 3 — Four ADRs carry unresolved forward references to governance documents.**
ADR-002, ADR-004, ADR-011, and ADR-017 all reference `docs/governance/state-machines.md` as `*(planned)*`. This document does not exist. AX-3 (FSM & Temporal Boundary batch) will publish it and close all four forward references.

**Problem 4 — The embedded Mermaid diagrams in the ADRs are not independently addressable.**
ADR-017's six-state appointment FSM, ADR-011's two-plane temporal model, ADR-002's BlockedSlot FSM, and ADR-007's derive architecture diagram are all valuable. But they are embedded within ADR prose, reachable only after reading significant context. The Architecture Experience Journey promotes these to standalone, cross-referenced artefacts placed where their primary audience encounters them first.

**Problem 5 — The system's most distinctive concepts require too much reading before they become legible.**
The temporal boundary principle — the most sophisticated architectural decision in this repository — currently requires reading approximately 3,000 words (ADR-011 in full) before it can be visualised. A single combined diagram (Appointment FSM + Temporal Boundary, with PFT rule annotations) would make the same concept legible in under two minutes.

---

## 3. Repository Strengths

**Textual precision.** Every ADR follows a consistent structure: engineering question, problem statement, context, decision, rationale, consequences, alternatives considered, engineering principle. The content is precise and unambiguous. Visual artefacts have reliable source material to draw from.

**Editorial classification.** The three-tier editorial classification (FOUNDATIONAL / CORE / ADVANCED) established in the ADR Journey provides a natural priority ordering for visual artefact publication. FOUNDATIONAL concepts get visual artefacts first.

**Sanitisation discipline.** The Source Code Journey established a rigorous sanitisation process. Every visual artefact inherits the same discipline: no private identifiers, no internal references, no production data.

**Pre-commit security scanning.** The gitleaks pre-commit hook ensures no credentials or tokens enter any visual artefact.

**Embedded diagram precedent.** Four ADRs already contain Mermaid diagrams. These establish the diagram format (Mermaid), validate that GitHub renders it correctly, and provide content that the standalone versions can build from.

**Cross-reference density.** The ADR Source Code Reference sections create a rich cross-reference graph between decisions and implementation. Visual artefacts can anchor to this graph by linking to both the relevant ADR and the relevant published source artefact.

---

## 4. Repository Weaknesses

**No visual entry point.** The most significant weakness. A reader who opens the repository today and does not know where to start has no visual aid. The README describes the system in prose. The architecture directory is empty.

**`docs/architecture/` is empty.** Every sub-document planned for this directory (`ARCHITECTURE.md`, `DATA_MODEL.md`, `SECURITY.md`) remains absent.

**`docs/governance/` is empty.** Five documents are planned (`state-machines.md`, `permissions.md`, `destructive-actions.md`, `audit-requirements.md`, `cancellation-request-workflow.md`). None exist. AX-3 closes the two most urgent.

**`docs/case-studies/` is empty.** Four case studies are planned. None exist. These are out of scope for this Journey but depend on AX-3 (the Temporal Boundary case study requires the FSM + boundary diagram to exist before the case study narrative can reference it).

**`docs/engineering/` is empty.** One deep-dive is planned (`CUSTOMER_LEGITIMATION.md`). Out of scope for early batches.

**Four *(planned)* forward references remain unresolved in the ADR set.** ADR-002, ADR-004, ADR-011, ADR-017 all reference `docs/governance/state-machines.md`. AX-3 resolves these.

---

## 5. Existing Visual Assets

The repository currently contains four embedded Mermaid diagrams inside ADR documents. These are the complete inventory of existing visual content.

**ADR-017 — Six-State Appointment FSM (stateDiagram)**
Location: `docs/adr/ADR-017-appointment-fsm-design.md`
Content: The six-state lifecycle diagram — PENDING, CONFIRMED, CANCELLATION_REQUESTED, CANCELLED, COMPLETED, NO_SHOW — with all valid transitions.
Limitation: Embedded within ADR-017 prose. Not standalone. Does not show the temporal boundary or PFT rule annotations.

**ADR-011 — Temporal Boundary Two-Plane Model (graph LR)**
Location: `docs/adr/ADR-011-appointment-temporal-boundary.md`
Content: Operational and closure plane separation, PFT-1 expiry path, key transitions within each plane.
Limitation: Embedded. Shows only the boundary-crossing behaviour; does not include the full six-state FSM. Needs to be read alongside ADR-017's diagram.

**ADR-002 — BlockedSlot FSM (stateDiagram)**
Location: `docs/adr/ADR-002-blocked-slot-state-machine.md`
Content: The five-state BlockedSlot lifecycle — REQUESTED, APPROVED, REJECTED, CANCELLED, EXPIRED — with actor-annotated transitions.
Limitation: Embedded. Not addressable as a standalone governance reference.

**ADR-007 — BotEventResolver Derive Architecture (graph TD)**
Location: `docs/adr/ADR-007-bot-panel-derive-architecture.md`
Content: The pure-function derive architecture: Appointment table → BotEventService → BotEventResolver → bot audit and activity views.
Limitation: Embedded. Shows only the derive pattern, not the full bot-to-backend runtime flow.

**Summary:** Four diagrams exist; all four are embedded within ADR prose; none is standalone; none answers the most fundamental questions a first-time reader has.

---

## 6. Missing Visual Assets

The following visual artefacts are absent from the repository and are the subject of this Journey's publication plan.

**System Context Diagram (C4 Level 1).** No diagram shows the system's external boundary: which actors use it, which external systems it integrates with, and what data crosses each boundary. This is the most fundamental missing visual.

**Bot-to-Backend Runtime Flow.** No diagram traces the complete runtime path from a WhatsApp customer message to a committed database write. This is the system's most distinctive architectural behaviour.

**Core Entity Relationship Diagram.** No diagram shows how the domain entities relate. The schema has 78 Flyway migrations and no visual representation of the resulting entity graph.

**Appointment FSM + Temporal Boundary (combined).** No diagram combines the six-state FSM with the temporal boundary line, PFT rule annotations, and operational/closure plane labels. This is the repository's flagship missing visual — it synthesises the two most important ADRs into a single legible artefact.

**BlockedSlot FSM (standalone).** The embedded version in ADR-002 is accurate but not independently addressable. A standalone version in `docs/governance/state-machines.md` closes the ADR forward references.

**Tenant Isolation Architecture.** No diagram shows how `AuthorizationService` functions as the single, explicit isolation gate: where the boundary sits, what it checks, and what it makes structurally impossible.

**Security Filter Chain.** No diagram shows the complete HTTP request pipeline through the six security filters, their ordering, and their exit conditions on failure.

**JWT Refresh Token Rotation Sequence.** No sequence diagram shows the rotation flow and the reuse detection branch — the point at which a presented revoked token triggers total session revocation.

**Three-Layer Audit Architecture.** No diagram shows the L1/L2/L3 audit placement pattern from ADR-003 with a representative action flowing through all three layers simultaneously.

**OutboundLegitimacyGate Flow.** No diagram shows the consent enforcement gate through which every outbound WhatsApp communication must pass.

---

## 7. Editorial Philosophy

**Every diagram answers exactly one engineering question.**
The engineering question is not inferred from the diagram — it is stated explicitly in the caption. A diagram without a stated engineering question is not ready for publication.

**Visual artefacts are subordinate to ADRs.**
The ADR is the authority. The diagram illustrates the ADR's decision. When a diagram and an ADR conflict, the diagram requires revision. The reverse is never true.

**Minimum surface area.**
A diagram should contain the minimum number of elements needed to answer its engineering question. Every element that does not directly serve the engineering question is a candidate for removal. Complexity is not a virtue.

**Stability as a publication criterion.**
A visual artefact representing a concept that is likely to change should be designed at a higher level of abstraction. A diagram that will require frequent revision should either not be published or be scoped to the stable elements of the concept.

**No decorative diagrams.**
Every visual artefact in this Journey exists to make an engineering concept faster to acquire. A diagram that exists to make the repository look impressive rather than to answer an engineering question will not be published.

---

## 8. Publication Principles

1. **No diagram precedes its ADR.** Every visual artefact has a published, accepted ADR as its source of authority.
2. **No diagram references unpublished source artefacts.** A diagram may reference a class or component only if that class or component has been published in the Source Code Journey.
3. **Every diagram has a caption.** The caption states the engineering question the diagram answers and links to the relevant ADR.
4. **Every diagram links from its ADR.** ADRs that gain a standalone diagram update their Source Code Reference section to link to the diagram's location.
5. **Diagrams are versioned with the repository.** A diagram published in AX-1 is tagged at the repository version at which it was published. If the diagram is later updated, the change is reflected in a commit message and the corresponding ADR.
6. **The style guide governs all visual decisions.** If a visual decision is not covered by the style guide, the style guide is updated before the diagram is published.
7. **Quality gate before every batch commit.** Navigation, links, naming, editorial consistency, and absence of private information are verified before any batch is committed.
