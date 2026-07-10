# ADR Publication Standard
## vookedme-engineering

**Version:** 1.1  
**Date:** 2026-07-10  
**Status:** Canonical — applies to all ADRs published in this repository

---

## Purpose

Every Architecture Decision Record published in `docs/adr/` follows this standard. The standard ensures that:

- All ADRs are readable by an engineer who has never seen the codebase
- All ADRs communicate engineering knowledge, not operational or legal detail
- All ADRs are written in English
- All ADRs are free of references to internal documents, bug IDs, or production data
- All ADRs follow an identical structure so readers can navigate across them efficiently

This standard applies equally to ADRs migrated from the private repository and to new ADRs written directly in this repository.

---

## Template

```markdown
# ADR-NNN — Title

**Status:** [Accepted | Proposed | Superseded by ADR-NNN | Deprecated]  
**Date:** YYYY-MM-DD  
**Domain:** [domain-slug] (e.g. appointment, auth, customer, gdpr, infrastructure)  
**Editorial:** [FOUNDATIONAL | CORE | ADVANCED | REFERENCE]

> **Engineering Question Answered:** One sentence stating the engineering question
> this ADR answers, phrased so a reader can immediately know whether this ADR is
> relevant to them.

---

## Problem

One paragraph. What situation made this decision necessary?

Write it so that an engineer who has never worked on this system immediately
understands why the decision had to be made. A production incident, a legal
constraint, a design gap, or an emerging complexity — whatever forced the choice.

Do not state the decision here. State the problem.

## Context

The constraints, prior state, and technical landscape that shaped the decision space.

Include:
- What existed before this decision
- The constraints that ruled out obvious solutions
- Why the alternatives considered were genuinely viable before being rejected

Do not include:
- References to internal documents
- Bug IDs or incident ticket numbers
- Production-specific data (appointment IDs, row counts, commit hashes)
- Sprint or phase planning references

## Decision

**State the decision in the first sentence.**

Then elaborate. Explain the rationale. Make clear why this option rather than the
alternatives. The first sentence should be understandable in isolation — an
engineer skimming the ADR index should know what was decided without reading
further.

If the decision has sub-decisions (D1, D2, D3), give each a sub-heading.

If a diagram communicates the decision more clearly than prose, include one.
Prefer Mermaid over ASCII art. A diagram in the Decision section is preferred
over one placed elsewhere.

## Alternatives Considered

| Option | Description | Why Rejected |
|---|---|---|
| Option A | ... | ... |
| Option B | ... | ... |

Every alternative that was seriously considered must appear here. "We didn't think
of it" is not a valid alternative. "We considered it and rejected it because..."
is the intended format.

## Consequences

### Positive
What becomes easier. What constraints are lifted. What guarantees are now in place.

### Negative
What becomes harder. What is explicitly ruled out. What technical debt is accepted.

### Neutral
What is unchanged. What requires ongoing coordination. What future decisions are
left open.

## Engineering Principle

The transferable lesson. What can an engineer working on a different system learn
from this decision?

Write this in one paragraph, as if the codebase did not exist and you were
explaining the principle to a peer at a different company. The principle must
stand alone.

## Related

- [ADR-NNN](./ADR-NNN-title.md) — relationship description
- [Architecture: filename.md](../architecture/filename.md) — if applicable
- [Governance: filename.md](../governance/filename.md) — if applicable
- [Case Study: ...](../case-studies/filename.md) — if applicable

## Source Code Reference

*Populated when source code is present (v0.3.0+).*

- `package/ClassName.java` — description of its role in this decision
- `package/ClassName.java` — description
```

---

## Mandatory Sections

Every published ADR must contain all of these:

- **Engineering Question Answered** — one sentence in the metadata block
- **Problem** — one paragraph maximum
- **Context** — two to five paragraphs
- **Decision** — the decision in the first sentence, followed by rationale
- **Alternatives Considered** — minimum two alternatives (including status quo if applicable)
- **Consequences** — all three sub-headings (Positive, Negative, Neutral)
- **Engineering Principle** — the transferable lesson, one paragraph

The following sections are optional but expected for mature ADRs:

- **Related** — always include when related ADRs exist
- **Source Code Reference** — populate once source code is published

---

## Sanitization Checklist

Before publishing any ADR migrated from the private repository, verify:

- [ ] No references to internal documents (AGENTS.md, AUDIT_REPORT.md, DECISION_LOG.md, IMPLEMENTATION_ROADMAP.md, etc.)
- [ ] No internal bug identifiers (CRIT-N, HIGH-N, B-NN, D-NNN)
- [ ] No production data (appointment IDs, customer IDs, row counts, specific timestamps)
- [ ] No private git commit hashes
- [ ] No internal package paths that differ from the public repository package name
- [ ] No references to internal legal documents (DPA_BETA, TOS_BETA, PRIVACIDAD_BETA)
- [ ] No n8n workflow node names
- [ ] No sprint or implementation phase planning references
- [ ] Written entirely in English (translate if necessary)
- [ ] Status is accurate (verify implementation reality, not just private repo status)

---

## Writing Standards

### Language

English only. The repository is international. ADRs in Spanish remain in the private repository.

### Voice

Active voice. Present tense for the decision ("The system uses a single money field"). Past tense for the context and history ("Before this decision, the system had...").

### Audience

Write for a senior engineer who is encountering this codebase for the first time and wants to understand not just what was built but why. Do not write for an engineer who was present when the decision was made.

### Length

- **Problem**: 1 paragraph (2–4 sentences)
- **Context**: 2–5 paragraphs
- **Decision**: as long as needed to explain the rationale clearly; typically 3–8 paragraphs
- **Alternatives**: 2–5 options; each rejection reason in 1–3 sentences
- **Consequences**: 3–6 bullets per sub-heading
- **Engineering Principle**: 1 paragraph

Most ADRs should be 400–800 words. An ADR longer than 1200 words is probably carrying operational detail that belongs elsewhere.

### Internal references

When an internal document informed the decision (AUDIT_REPORT, AGENTS.md, a legal brief), do not reference it by name. Instead, describe the finding: "An audit of the existing implementation found that..." — not "AUDIT_REPORT.md CRIT-3 found that..."

Production incidents that triggered a decision should be described abstractly: "A production incident in beta..." — not "Bug B-10 on 2026-06-11 at 15:44...".

### Numbering

ADRs are numbered sequentially from ADR-001. No gaps. Deprecated or superseded ADRs keep their numbers — mark them with the appropriate status, never delete them.

Zero-padded to three digits: ADR-001 through ADR-999.

---

## Status Values

| Status | Meaning | When to use |
|---|---|---|
| **Accepted** | Decision is final, implemented, and in force | Default for published ADRs |
| **Proposed** | Decision is documented but not yet implemented | Use if publishing a decision that is still pending |
| **Superseded by ADR-NNN** | A later decision changed or replaced this one | Never delete a superseded ADR — keep it with this status |
| **Deprecated** | Decision was abandoned before full implementation | Use for decisions that were designed but not built |

---

## ADR Domain Tags

Use exactly one domain tag per ADR. This enables filtering and cross-referencing.

| Tag | Scope |
|---|---|
| `appointment` | Appointment lifecycle, FSM, assignment |
| `auth` | JWT, sessions, token rotation |
| `bot` | WhatsApp bot, conversational design |
| `customer` | Customer entity, lifecycle, GDPR erasure |
| `gdpr` | GDPR compliance architecture |
| `identity` | User identity, roles, multi-tenancy |
| `infrastructure` | Database, timezone, migrations |
| `notification` | Outbound notifications, channels |
| `security` | Webhook validation, secrets, audit |
| `schedule` | BlockedSlot, employee schedules |

---

## Editorial Classification

Every published ADR carries an editorial value classification:

| Value | Meaning | Typical characteristics |
|---|---|---|
| **FOUNDATIONAL** | Every visitor should read this | Defines a concept the rest of the repository builds on; the single best entry point into the engineering |
| **CORE** | Essential to understand the system | Explains a major architectural constraint, design pattern, or boundary decision |
| **ADVANCED** | Deep engineering knowledge | Extends a core concept into specific complexity; presupposes familiarity with foundational ADRs |
| **REFERENCE** | Useful for a specific engineering topic | Narrower scope; not required for general understanding; valuable when the relevant topic arises |

Use exactly one value per ADR. This classification drives navigation, reading order, and repository structure.

---

## Review Gate

Before an ADR is committed to the public repository, one reviewer must confirm:

1. The sanitization checklist passes completely
2. The `Engineering Question Answered` field is present and answerable in one sentence
3. The Engineering Principle section exists and is transferable
4. The decision is stated in the first sentence of the Decision section
5. All alternatives include a rejection reason
6. The document is entirely in English
7. The status accurately reflects the current implementation state
8. The editorial classification is assigned
