# Architecture Style Guide

**Journey:** Architecture Experience Journey  
**Document version:** 1.0  
**Date:** 2026-07-11  
**Status:** Canonical

> This document is the visual language specification for the Architecture Experience Journey. Every diagram published in this Journey must conform to the rules defined here. If a visual decision is not covered by this guide, the guide must be updated before the diagram is published — not after.

---

## 1. Editorial Principles

**One diagram, one engineering question.**
Every diagram answers exactly one engineering question. That question is stated in the diagram's caption, not inferred from the title. A diagram that could answer two questions must be split into two diagrams.

**Diagrams follow the ADR; they never lead it.**
A diagram represents a decision that has already been recorded in an accepted ADR. It cannot introduce new constraints, new states, or new actors that are not present in the corresponding ADR. The ADR is the authority.

**Accuracy over completeness.**
A diagram that is accurate about a smaller scope is better than a diagram that approximates a larger scope. If showing all entities would require simplification, show fewer entities accurately.

**Minimum surface area.**
Every element in a diagram must serve the engineering question. An element that does not contribute to answering the question is a candidate for removal. Adding elements to make a diagram look thorough is the same error as adding words to make documentation look complete.

**No decorative diagrams.**
A diagram that exists to make the repository look impressive rather than to answer an engineering question will not be published.

**Stability as a publication gate.**
A diagram that represents a concept that is still evolving should be held until the concept is stable, or scoped to the stable portion only. A diagram requiring frequent revision is worse than no diagram.

---

## 2. Diagram Format

**Mermaid is the default format for all diagrams.**

Rationale:
- GitHub renders Mermaid natively in Markdown — no image upload, no external dependency
- Diagrams are version-controlled as text — diffs are readable, reviews are meaningful
- A Mermaid diagram can be updated in the same commit as the ADR it illustrates
- Mermaid source is inspectable — readers can see exactly what the diagram encodes

**When Mermaid is insufficient:**
If Mermaid's syntax cannot represent the required structure accurately (for example, a complex ERD requiring precise notation), an SVG or PNG may be used, subject to two conditions:
1. The Mermaid source or equivalent text specification must be stored alongside the image in `assets/diagrams/` — not just the rendered output
2. The image must use a transparent or neutral background compatible with both GitHub's light and dark rendering modes

**Diagram types and their Mermaid syntax:**
- State machines: `stateDiagram-v2`
- Flow diagrams and runtime flows: `graph LR` (left to right) or `graph TD` (top to bottom)
- Sequence diagrams: `sequenceDiagram`
- Entity relationship diagrams: `erDiagram`
- Architecture overviews where C4 notation is needed: `graph TD` with explicit subgraph boundaries for system/component boundaries

---

## 3. Information Hierarchy

Every diagram has three levels of information. These levels should be visually distinguishable.

**Level 1 — Primary subject.**
The entity, the flow, the boundary. This is what the diagram is about. It occupies the visual centre or the primary path. It should be immediately identifiable without reading labels.

**Level 2 — Supporting context.**
What the primary subject connects to, what it produces, what it receives. This is the frame around the primary subject. It exists to give the primary subject meaning.

**Level 3 — Annotations.**
Invariants, constraints, rule references, actor limitations. Annotations clarify what the structure cannot show — they do not repeat what the structure already shows. If an annotation restates what the node label already says, it is noise and must be removed.

**Rule:** Level 3 annotations should not outnumber Level 1 elements. If the annotation count exceeds the node count, the diagram is over-annotated and should be simplified.

---

## 4. Annotation Philosophy

**Annotations serve one purpose: label an invariant not visible from structure alone.**

Correct annotations:
- `OWNER only` — on a transition, indicating actor restriction
- `ADR-011 PFT-1` — on a boundary-crossing operation, referencing the governing rule
- `null for non-panel actors` — on an entity field visible in a diagram context
- `UTC enforced here` — on a persistence step in a sequence diagram
- `APPROVED only` — on a calendar query edge, indicating the filter condition

Incorrect annotations:
- `This is the booking creation step` — restates what the node label already communicates
- `See ADR-017 for details` — a general reference that should be in the caption, not embedded
- Full sentences — annotations must be concise; if a sentence is required, the concept belongs in the ADR, not in the diagram

**ADR rule references in annotations:**
When an annotation references a specific ADR rule, use the form: `ADR-NNN PFT-N` or `ADR-NNN §Section`. Do not use internal identifiers (task IDs, bug IDs, version identifiers).

---

## 5. Naming Conventions

**Document filenames:** UPPERCASE with underscores, `.md` extension. Examples: `ARCHITECTURE.md`, `DATA_MODEL.md`, `SECURITY.md`, `state-machines.md` (governance documents use lowercase kebab-case to match the existing governance directory convention).

**Diagram asset filenames** (when stored in `assets/diagrams/`): lowercase kebab-case. Examples: `appointment-fsm-temporal-boundary.md`, `system-context.md`, `security-filter-chain.md`.

**Diagram titles:** Sentence case, starting with the primary subject. Examples:
- "Appointment lifecycle — six states and temporal boundary"
- "System context — VookedMe appointment scheduling platform"
- "Security filter chain — registration order and exit conditions"

**Caption format:** See Section 9.

**Node labels:** Use the canonical Java class name or enum value name where the node represents a published source artefact. Examples: `AuthorizationService`, `CANCELLATION_REQUESTED`, `AppointmentAuditLog`. Do not invent display names that differ from the source.

**State names:** UPPERCASE, matching Java enum convention.

**Actor names in transitions:** Use the role names as defined in the system — OWNER, ADMIN, EMPLOYEE for authenticated panel roles; Customer, System, Bot, Scheduler for other actor types.

---

## 6. Colour Philosophy

GitHub renders Mermaid using its own theme. Colour control is partial — the default Mermaid theme is used for all diagrams unless a theme is explicitly specified.

**Default theme:** Use `%%{init: {'theme': 'default'}}%%` if explicit theme specification is needed for consistency. Do not use themes that produce dark backgrounds (e.g., `dark`) as these may not render correctly in GitHub's light mode.

**Semantic colour use:**
Colour may be used to carry semantic meaning in one situation: distinguishing **terminal states** from **non-terminal states** in state machine diagrams. Terminal state nodes should use a visually distinct fill or border — but the distinction must also be carried by shape or annotation, not by colour alone (accessibility requirement).

**Colour must never be the sole distinguishing factor** between elements. Engineers who are colour-blind must be able to read every diagram without relying on colour. Shape, label, annotation, or position must carry the same information.

**What must not be colour-coded:**
- ADR editorial tier (FOUNDATIONAL / CORE / ADVANCED)
- Security level or trust level
- Priority or importance

---

## 7. Typography Guidance

**Node labels:**
- As short as possible. "APPROVED" not "Status: APPROVED". "AuthorizationService" not "The Authorization Service Class".
- Use the canonical source name where one exists.
- Break at 30 characters if a label is unavoidably long — most Mermaid renderers handle this cleanly.

**Abbreviations:**
Established acronyms are permitted without expansion: FSM, JWT, PFT, GDPR, UTC, HMAC, FK, ERD, RBAC, LLM.
All other abbreviations must be expanded. Do not create new abbreviations inside diagrams.

**Casing rules:**
- State names: UPPERCASE (matching Java enum)
- Class names: PascalCase (matching Java convention)
- Method names: camelCase with parentheses — `generateRefreshToken()`
- HTTP methods and paths: UPPERCASE for method, lowercase for path — `POST /auth/refresh`
- Database tables: lowercase snake_case — `appointment_audit_log`

---

## 8. Layout Consistency

**State machines** (`stateDiagram-v2`):
- Primary direction: left to right where the state progression is linear
- Creation states (entry points) on the left; terminal states on the right
- If the diagram is wide, top-to-bottom is acceptable
- The temporal boundary line in the Appointment FSM + Temporal Boundary diagram is vertical, dividing operational states (left) from closure states (right)

**Flow and architecture diagrams** (`graph LR` or `graph TD`):
- Top-to-bottom for single-path flows (filter chains, security gates)
- Left-to-right for end-to-end flows spanning multiple systems (bot-to-backend, token rotation)
- The central system (Spring Boot backend) positioned at the structural centre of context diagrams

**Sequence diagrams** (`sequenceDiagram`):
- Participants ordered left to right, from the initiating actor to the terminal system
- Time flows downward
- The boundary where control crosses from the orchestration layer to the backend is visually marked (activation box width change or note)

**Entity relationship diagrams** (`erDiagram`):
- The most connected entity (`Appointment`) anchored at the centre or top-left
- `Business` positioned as the root of the tenant hierarchy (all FK chains trace back to it)
- Audit entities positioned at the periphery — they record events, they do not drive relationships

**Subgraph grouping:**
Use subgraphs to group related nodes when a diagram has more than 7 nodes. Subgraph labels use Title Case. Example: "Operational Plane", "Closure Plane", "Spring Boot Backend".

---

## 9. Caption Style

Every published diagram has exactly one caption. The caption is placed immediately below the diagram, inside a Markdown blockquote.

**Format:**

```
> **Figure N** — *[Diagram title in sentence case]*: [The engineering question this diagram answers, stated as a declarative sentence.] See [ADR-NNN — Title](path/to/adr).
```

**Rules:**
- Captions are in sentence case
- The figure number increments per document, not globally
- The caption states the engineering question — it does not describe what the diagram shows
- The ADR link is mandatory for all diagrams that have a corresponding ADR; omitted only for REFERENCE artefacts with no single governing ADR
- Captions do not include implementation detail, code snippets, or annotation explanations

**Example:**

```
> **Figure 1** — *Appointment lifecycle — six states and temporal boundary*: How does `appointment.datetime` divide the appointment lifecycle into two distinct operational planes, and what transitions are legal in each plane? See [ADR-017](../adr/ADR-017-appointment-fsm-design.md) and [ADR-011](../adr/ADR-011-appointment-temporal-boundary.md).
```

---

## 10. Legend Rules

A legend is included only when the diagram uses visual conventions that cannot be inferred from the Mermaid syntax alone, and where the conventions are not already explained by node labels or annotations.

**When to include a legend:**
- When a diagram uses both dashed and solid edges to distinguish flow types (control flow vs. data flow), and both are present
- When terminal state notation differs from the Mermaid default in a way that might not be obvious

**When NOT to include a legend:**
- When colour is already explained by annotations
- When the meaning of every shape can be inferred from labels
- When the legend would duplicate information already in the caption

Legends are placed below the caption, inside a collapsible `<details>` block if they are longer than two entries. Short legends (one or two entries) may be inline with the caption.

---

## 11. Cross-Referencing Rules

**Diagrams link to ADRs.** Every diagram caption links to the ADR that governs the decision it represents. If multiple ADRs are relevant, all are linked.

**ADRs link to standalone diagrams.** When a standalone diagram is published for a concept that has an embedded Mermaid in an ADR, the ADR's Source Code Reference section is updated to add a link to the standalone diagram's location. The embedded Mermaid in the ADR is retained — it is contextually valid there — but the standalone version becomes the primary reference.

**Governance documents link to ADRs and source.** Documents in `docs/governance/` link to the defining ADR for every rule they express, and to the published source artefact that enforces the rule.

**Forward references are resolved at publication.** A batch commit that publishes a diagram must also resolve all ADR forward references (`*(planned)*`) that point to the document that diagram populates.

---

## 12. Spacing and Complexity Limits

**Node count limit:** A single diagram should not exceed 15 nodes without subgraph grouping. If 15 nodes are required, at least 3 subgraphs must be used to impose visual structure.

**Annotation count limit:** The number of annotations on a single diagram should not exceed the number of primary nodes. Exceeding this ratio is a signal that the diagram is over-specified and should be split.

**Mermaid label length:** Labels should not exceed 40 characters. Longer labels degrade rendering on standard viewport widths and indicate that the label is trying to carry information that should be in the caption.

---

## 13. What Must Never Appear in Diagrams

The following must never appear in any diagram published in this Journey, regardless of context or apparent relevance:

**Private repository references:**
- Internal task identifiers (bug IDs, task IDs, experiment IDs, feature flags)
- Internal document names, internal planning documents, internal ADR identifiers not present in this repository
- Branch names, internal PR references

**Production system information:**
- Production domain names or URLs
- Production endpoint paths
- Credential values, API keys, secrets, token values
- Database connection strings

**Personal or business data:**
- Customer names, phone numbers, email addresses
- Business names from the production system
- Any value that is or resembles a real data record

**Implementation detail beyond the level of the diagram's category:**
- Method bodies or logic inside a method call step
- SQL statements or query text
- Configuration file values
- Stack traces, exception messages, log output

**Future state or speculation:**
- "Will be added in a future version"
- "Planned" or "Coming soon"
- Hypothetical states or transitions not present in an accepted ADR
- Diagrams represent only the current, accepted state of the system

**Marketing or evaluative language:**
- "Best-in-class", "robust", "seamless", "revolutionary", "industry-leading"
- Performance claims without measurement evidence
- Comparative claims against other systems

**Attribution:**
- Developer names, team names, implementor credits
- Version numbers of external libraries (they change; the diagram would immediately become stale)

---

## 14. Pre-Publication Checklist

Before any diagram is committed as part of a batch:

- [ ] Caption is present and follows the format in Section 9
- [ ] Caption states the engineering question (not a description of the diagram)
- [ ] Caption links to the relevant ADR(s)
- [ ] Every node label uses canonical source names where applicable (Section 5)
- [ ] No element from the "What Must Never Appear" list (Section 13) is present
- [ ] Colour is not the sole distinguishing factor between any two elements (Section 6)
- [ ] Annotation count does not exceed node count (Section 12)
- [ ] The diagram renders correctly in GitHub's Markdown preview
- [ ] The relevant ADR's Source Code Reference section has been updated to link to the standalone diagram
- [ ] All ADR forward references to the document this diagram populates have been resolved
- [ ] The quality gate has been run on the batch before committing
