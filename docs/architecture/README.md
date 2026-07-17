# Architecture Experience Journey

> **Status:** v1.9.0 — AX-1, AX-2, AX-3, and AX-5 published; AX-4 in draft, Figures A, B, and C frozen, pending Figure D. System Context, Container Diagram, Core Domain Model, Appointment/BlockedSlot FSMs, and Security Architecture live. Authorization Architecture text is frozen; Figures A, B, and C are frozen and awaiting Figure D before publication.

The Architecture Experience Journey is the third editorial phase of this repository.

The Foundation Phase established standards and structure.
The ADR Journey documented *why* every significant design decision was made.
The Source Code Journey published *how* those decisions are implemented.

This Journey answers *what* the system is — making the repository understandable in the shortest possible time through carefully curated visual artefacts.

---

## Why This Journey Exists

The Foundation and ADR phases produced a repository with exceptional textual depth. An engineer who reads all seventeen ADRs holds a precise understanding of every architectural decision in this system. A recruiter or engineering manager who reads the README and three foundational ADRs understands the engineering thinking behind the product.

What neither phase addressed: **the repository has no visual entry point**. A reader who opens it for the first time must invest several thousand words before forming a coherent mental model of what the system is, how it is shaped, or where to look first. The technical foundation is now complete. Visual artefacts can now be built from stable, accurate content — rather than from a system still in motion.

The Architecture Experience Journey exists to close that gap.

---

## Relationship to the ADR and Source Code Journeys

Every visual artefact in this Journey is subordinate to its corresponding ADR.

The ADR is the authority. The visual is the illustration.

A diagram never introduces new information — it compresses existing information into a form that can be absorbed faster. When an ADR and a diagram disagree, the ADR is correct and the diagram requires revision.

---

## Framework Documents

The Journey is governed by four documents in this directory:

| Document | Purpose |
|---|---|
| [ARCHITECTURE_EXPERIENCE_AUDIT.md](./ARCHITECTURE_EXPERIENCE_AUDIT.md) | Why this Journey exists; repository strengths and weaknesses; existing and missing visual assets; editorial philosophy |
| [ARCHITECTURE_VISUAL_CLASSIFICATION.md](./ARCHITECTURE_VISUAL_CLASSIFICATION.md) | The seven visual categories; purpose, engineering question, complexity, and publication criteria for each |
| [ARCHITECTURE_PUBLICATION_PLAN.md](./ARCHITECTURE_PUBLICATION_PLAN.md) | The complete publication roadmap — seven batches, dependencies, rationale, effort |
| [ARCHITECTURE_STYLE_GUIDE.md](./ARCHITECTURE_STYLE_GUIDE.md) | The visual language specification — editorial principles, naming conventions, annotation rules, what must never appear |

---

## Publication Batches

| Batch | Name | Visual Category | Priority | Status |
|---|---|---|---|---|
| AX-1 | Context & System Entry | FOUNDATIONAL + ARCHITECTURE | Critical | [Published (v1.6.0)](./ARCHITECTURE.md) |
| AX-2 | Domain Model | DOMAIN | Critical | [Published (v1.7.1)](./DATA_MODEL.md) |
| AX-3 | FSM & Temporal Boundary | GOVERNANCE | Critical | [Published (v1.8.0)](../governance/state-machines.md) |
| AX-4 | Authorization Architecture | GOVERNANCE + ARCHITECTURE | High | [Draft — Figures A/B/C frozen, pending D](./AUTHORIZATION.md) |
| AX-5 | Security Architecture | SECURITY | High | [Published (v1.9.0)](./SECURITY.md) |
| AX-6 | Audit & Compliance | RUNTIME + REFERENCE | Medium | Planned |
| AX-7 | Navigation | REFERENCE | Low-Medium | Planned |

Full batch specifications, dependencies, and rationale are in [ARCHITECTURE_PUBLICATION_PLAN.md](./ARCHITECTURE_PUBLICATION_PLAN.md).

---

## What This Journey Does Not Do

This Journey does not create new architectural documentation.
It does not introduce design decisions.
It does not add engineering content that is not already present in the ADRs or source artefacts.

Every diagram in this Journey represents a decision already recorded in an ADR and already implemented in published source. The visual artefacts are editorial tools — they make existing knowledge faster to acquire.

---

## Related

- [ADR Journey](../adr/README.md) — the *why* behind every design decision
- [Source Code Journey](../source/README.md) — the *how* in published source artefacts
- [Governance](../governance/README.md) — domain rules that visual artefacts in AX-3 