# AX-4 — Figure B — Architectural Blueprint (Redesigned)

**Journey:** Architecture Experience Journey — AX-4 (Authorization Architecture)
**Artefact type:** Engineering specification for a renderer. Not Mermaid, not SVG, not PNG, not HTML. This document is the single source of truth from which `Figure-B.html` will later be produced.
**Note on this revision:** This replaces the first Figure B blueprint outright, not as an alternate version. The first draft correctly discovered the implementation's internal decision shapes but wrongly promoted that discovery to the figure's primary visual language, teaching *how the dedicated authorization component categorizes its own guards* rather than *how VookedMe's authorization model works*. No new source auditing was performed for this revision — every fact below is reused from the verified discovery in the prior draft (`AuthorizationService`'s guard behaviour, the self-approval and lifecycle-state governance rules confirmed directly against `AppointmentService`/`BlockedSlotService`, and the per-business capability toggle confirmed against its policy gate). What changed is which of those facts becomes a primary node, and which becomes supporting detail folded inside a node.
**Source of truth verified against:** `AUTHORIZATION.md` (frozen draft), ADR-016, ADR-011, published source artefacts, and the private-repo implementation verified in the prior discovery pass.

---

## 1. Purpose

A Staff Engineer reading this figure should finish it understanding **how VookedMe constructs an authorization decision** — the conceptual steps every decision passes through, from knowing who is asking to knowing what they may do — not how one internal component happens to categorize the checks it performs. Figure A already showed *where* enforcement lives. This figure shows *how a decision is built*, as a software architect would explain it on a whiteboard, without reference to any class or method.

---

## 2. Engineering Question

**How does VookedMe construct an authorization decision — from knowing who is asking, to knowing what they are allowed to do?**

This is deliberately not "how does the dedicated authorization component classify its guards" (that is implementation detail, valuable in prose, wrong as a figure's spine) and not "where in the request lifecycle is the decision made" (that is Figure A's question). It is the conceptual model itself: the small number of questions every authorization decision answers, in the order a human would reason through them, regardless of which operation, aggregate, or code path is involved.

---

## 3. Architectural Message

*(one paragraph)*

VookedMe builds every authorization decision through the same conceptual sequence, no matter the operation: it starts from the role model — which kind of actor is asking — narrows through business membership — which tenant, if any, they belong to — narrows again through authorization scope — how broad their standing is, whether they act on their own behalf, hold ownership/leadership standing, or hold platform-wide authority independent of any business — and finally passes through a small set of governance rules that can override or restrict what scope alone would otherwise grant: no one may resolve a request they themselves raised, certain actions belong to the business owner alone, a record's own lifecycle state can narrow who may act on it, and a business may switch a capability off entirely for its staff. A decision that clears every step is granted; failing any step denies it immediately, without waiting for the steps that would have followed. Non-panel actors — customers, the conversational channel, and automated system processes — never enter this sequence at all: they are authorized through an entirely separate mechanism built on identity, channel, and trust rather than role.

---

## 4. Core Concepts

This section reuses, reorganizes, and re-scopes the discovery from the prior blueprint. No code was re-examined to produce it.

**Primary conceptual spine** (the sequence a decision is built through):

1. **Role Model** — which kind of actor is making the request.
2. **Business Membership** — which tenant, if any, the actor belongs to.
3. **Authorization Scope** — how broad the actor's standing is, once membership is settled: acting on their own behalf, holding ownership/leadership standing, or holding platform-wide authority independent of any business. *(This folds together what the prior blueprint separated into three implementation-level "shapes" — they remain true and verified, but here they are three named breadths of one concept, not three competing primary nodes.)*
4. **Governance Rules** — a small set of rules that can override or restrict what scope alone would grant. *(This folds together the four governance exceptions discovered previously — self-approval separation, owner-exclusive attribution, the lifecycle-state gate, and the per-business capability gate — as four named items inside one concept, not four separate primary nodes.)*

**Outcomes** (shared vocabulary with Figure A, deliberately reused rather than reinvented):

5. **Business Operation** — the granted, positive outcome.
6. **Access Denied** — the shared negative outcome, reachable from three different points in the spine.

**Parallel track** (outside the spine entirely):

7. **Non-Panel Actor Authorization** — Customer, Bot, and System, authorized by identity, channel, and trust rather than by anything in the spine above.

### Concepts deliberately excluded from primary visual language (kept in prose only)

- The four internal decision "shapes" from the prior blueprint (pure membership, pure leadership, self-or-leadership, platform-only) are real and verified, but they are now sub-content of Authorization Scope's own description, not primary nodes. A reader does not need to memorize four shape names to understand the model; they need to understand that standing comes in three breadths.
- The four governance exceptions are, likewise, sub-content of Governance Rules rather than four more primary nodes.
- No aggregate (Appointment, BlockedSlot, User, etc.), endpoint, or method name appears anywhere in this figure, exactly as before.

---

## 5. Node Inventory

Seven nodes.

**Node 1 — Role Model**
*Purpose:* Establishes which kind of actor is making the request, before anything else can be asked.
*Meaning:* Three platform roles exist — ADMIN, OWNER, EMPLOYEE — in a flat structure with no implied hierarchy between OWNER and EMPLOYEE beyond what later steps establish. This node is also where the model forks: platform roles proceed down the spine; three further actor categories (Customer, Bot, System) never enter the spine at all and are handled by the parallel track (Node 7).
*Importance:* Primary — the spine's starting point.
*Relationship:* Feeds Business Membership for the ordinary case (Connector C1). Feeds Authorization Scope directly for platform-wide operations, bypassing membership entirely, because ADMIN's platform authority is not scoped to any business (Connector C2). Branches to Non-Panel Actor Authorization for Customer/Bot/System (Connector C9).

**Node 2 — Business Membership**
*Purpose:* Establishes which tenant, if any, the actor belongs to — the first narrowing question asked of every business-scoped operation.
*Meaning:* A platform-wide actor (ADMIN) is exempt from this question for platform-wide operations. Every other panel actor must belong to the specific business the operation targets; there is no cross-tenant standing of any kind.
*Importance:* Primary.
*Relationship:* Feeds Authorization Scope once membership holds (Connector C3). Feeds Access Denied directly if membership fails (Connector C6) — a decision can be denied here without ever reaching scope or governance.

**Node 3 — Authorization Scope**
*Purpose:* Once an actor's business is settled, this asks how broad their standing is.
*Meaning:* Three named breadths exist. *Leadership* — OWNER or ADMIN standing, broad within the business. *Self* — standing limited to the actor's own record, assignment, or authored item; the most frequently applied breadth in the system. *Platform-wide* — ADMIN authority independent of any single business. A decision satisfies this node the moment any one of the three breadths applies.
*Importance:* Primary, and the richest single concept in the figure — this is where "self versus leadership versus platform" is decided.
*Relationship:* Fed by Business Membership (Connector C3) and, for the platform-wide breadth only, directly by Role Model (Connector C2). Feeds Governance Rules once scope is satisfied (Connector C4). Feeds Access Denied directly if no breadth applies (Connector C7).

**Node 4 — Governance Rules**
*Purpose:* A small set of rules that can still override or restrict a decision that has already satisfied scope.
*Meaning:* Four named rules exist. A caller may never resolve a request they themselves raised, regardless of standing. A small set of legally-attributable actions belongs to OWNER alone, excluding even ADMIN. A record's own current lifecycle state can narrow who may act on it, independent of role. A business may switch a given capability off for its staff entirely, as a configuration choice rather than a judgement about any specific caller.
*Importance:* Primary — this is the step that turns "the caller could plausibly do this" into "the caller may do this right now, for this specific record, in this specific business."
*Relationship:* Fed by Authorization Scope (Connector C4). Feeds Business Operation once every rule clears (Connector C5). Feeds Access Denied if any rule fires against the decision (Connector C8).

**Node 5 — Business Operation**
*Purpose:* The granted outcome — the protected business action the whole sequence exists to guard.
*Meaning:* Reached only once Role Model, Business Membership, Authorization Scope, and Governance Rules have all been satisfied in sequence.
*Importance:* Outcome, not a decision-making step.
*Relationship:* Reached from Governance Rules only (Connector C5).

**Node 6 — Access Denied**
*Purpose:* The shared negative outcome — reused deliberately from Figure A rather than reinvented, because it is the same real-world outcome in both figures.
*Meaning:* Reachable from three different points in the spine — failing membership, failing scope, or failing a governance rule — and each is denied immediately, without waiting to evaluate the steps that would have followed.
*Importance:* Outcome, not a decision-making step, and deliberately positioned off the main spine so it is never mistaken for a fourth sequential stage.
*Relationship:* Fed by Business Membership (C6), Authorization Scope (C7), and Governance Rules (C8) — three distinct entry points into one shared node.

**Node 7 — Non-Panel Actor Authorization**
*Purpose:* Represents Customer, Bot, and System together, as a single parallel concept rather than three more spine steps.
*Meaning:* None of the three participates in the role model at all. A Customer request is authorized by matching the requester's identity against the record it targets. The Bot is authorized as a channel credential. System processes are authorized implicitly, as trusted infrastructure that never originates from an external request.
*Importance:* Supporting — necessary for a complete model, architecturally peripheral to the spine that is this figure's main subject.
*Relationship:* Reached only from Role Model (Connector C9). No connection to any other node — this absence is itself the point: these actors are resolved by a mechanism the rest of the figure never touches.

---

## 6. Connection Inventory

Nine connectors: five along the spine, three feeding the shared denial outcome, one branching to the parallel track.

| ID | From | To | Meaning | Line |
|---|---|---|---|---|
| C1 | Role Model | Business Membership | The ordinary path: a panel actor's business must be established next. | Solid |
| C2 | Role Model | Authorization Scope | Bypass: platform-wide standing (ADMIN) skips the membership question entirely, since it is not scoped to a business. | Dashed |
| C3 | Business Membership | Authorization Scope | Membership settles the business context the scope question is then asked within. | Solid |
| C4 | Authorization Scope | Governance Rules | A scope-satisfying decision still passes through the governance layer before being granted. | Solid |
| C5 | Governance Rules | Business Operation | A decision clearing every governance rule is granted. | Solid |
| C6 | Business Membership | Access Denied | Failing membership denies immediately. | Dashed |
| C7 | Authorization Scope | Access Denied | Failing every breadth of scope denies immediately. | Dashed |
| C8 | Governance Rules | Access Denied | Failing a governance rule denies, even after scope was satisfied. | Dashed |
| C9 | Role Model | Non-Panel Actor Authorization | Non-panel actors branch off the spine entirely and never return to it. | Dashed |

Styling rule: C1, C3, C4, C5 (the spine itself) are solid — this is the sequence a decision is actually built through. Every other connector (C2, C6, C7, C8, C9) is dashed — bypasses, denials, and the parallel branch are all secondary to the spine and must never be drawn with equal visual weight to it.

---

## 7. Visual Hierarchy

**Primary** — the four spine concepts: Role Model, Business Membership, Authorization Scope, Governance Rules. All four are equally necessary and none dominates the others — a decision that skips any one of them is not a real VookedMe authorization decision. This is a deliberate departure from Figure A (which rewards one dominant component) and from the prior Figure B draft (which wrongly crowned one internal shape): this figure's spine is a sequence of equally necessary steps, not a hierarchy of importance.

**Secondary** — the two outcomes, Business Operation and Access Denied. Visually lighter than the spine; they are destinations, not decisions.

**Supporting** — Non-Panel Actor Authorization. The quietest node on the canvas, positioned apart from the spine with a single dashed line reaching it and nothing further.

---

## 8. Layout Philosophy

**A spine of equals, not a hierarchy of one.** Figure A earned a single dominant anchor because its question was "where does responsibility concentrate," and one component genuinely held almost all of it. This figure's question is different — "how is a decision built" — and the honest answer is that four steps are equally necessary in sequence. The layout must therefore give Role Model, Business Membership, Authorization Scope, and Governance Rules the same size, the same weight, and the same border treatment as one another. No accent colour singles out one of them; instead, all four share one restrained accent as a family marker, signalling "these four together are the spine" rather than "this one matters more than the rest."

**Vertical order encodes conceptual narrowing, not runtime execution order.** The spine reads top to bottom, but this is the order a reader would ask the questions on a whiteboard, not necessarily the literal order any particular code path evaluates them in. Figure A already owns the request-lifecycle/execution story; this figure must not be read as a second copy of it. A caption note should make this explicit so the two figures are never confused for depicting the same thing from two angles.

**Rich nodes carry rich meaning; the canvas doesn't multiply.** Authorization Scope and Governance Rules each contain more real detail than a single sentence can hold — three named breadths, four named rules — but that detail is set as a short internal list inside each node's own boundary, in smaller type, clearly subordinate to the node's title. It is never drawn as separate connected boxes. This is the central correction from the prior draft: richness of discovery does not require richness of node count.

**A denial rail runs beside the spine, not through it.** Access Denied sits to one side, reachable by a short dashed drop from each of three spine nodes (Membership, Scope, Governance). Drawing it as one shared node reachable from three points — rather than three separate "denied" endings — teaches "you can be turned away at any of three points" as a single, memorable visual idea instead of three repeated ones.

**The non-panel branch sits furthest from the spine, reachable only from the very first node.** Because Customer/Bot/System are only ever relevant at the point of asking "which kind of actor is this," their branch leaves the diagram immediately after Role Model and never rejoins it — reinforcing that this is a parallel mechanism, not a variant path through the same funnel.

---

## 9. Visual Language

**Shapes**
- The four spine nodes: rounded rectangles, identical shape and corner radius to one another — sameness of shape is itself part of the message ("these four are peers").
- The two outcome nodes: Business Operation as a rectangle with a double border (protected, not a decision-maker — reused exactly from Figure A's vocabulary); Access Denied as an octagon (reused exactly from Figure A, so a reader who has seen Figure A recognizes it instantly).
- Non-Panel Actor Authorization: a pill shape, distinct from every other shape on the canvas, signalling "not part of the spine's shape family at all."

**Borders**
- Spine nodes: identical solid border weight across all four — no node in the spine may be given a heavier border than its neighbours.
- Outcome nodes: lighter border than the spine.
- Non-Panel Actor Authorization: the lightest border on the canvas.

**Typography**
- Spine node titles: identical size and weight across all four.
- Internal sub-lists inside Authorization Scope and Governance Rules: smaller type, regular weight, visibly subordinate to the node title above them — never styled to look like independent labels floating on the canvas.
- Outcome node labels: smaller than spine titles.
- Access Denied: upper case, reused from Figure A's convention for the same node.

**Spacing**
- Even, identical spacing between each consecutive pair of spine nodes — reinforcing "equal necessity" through equal rhythm, not through a dramatic pause at any one point (contrast with Figure A, which deliberately broke its rhythm at its one dominant anchor).
- The denial rail sits in a consistent, narrow lane beside the spine, close enough that each dashed drop-in reads instantly, far enough that it is never mistaken for a fifth spine node.

**Accent colours**
- The one reserved accent colour already established for this Journey is applied identically and lightly to all four spine nodes as a family marker — not to single one out, but to mark "the spine" as a set, distinguishing it at a glance from the outcomes and the parallel track.
- No node is given a heavier or more saturated use of the accent than any other spine node. This is the figure's clearest departure from both Figure A and the prior Figure B draft, and it is deliberate: this model has no single most-important step.

**Weight**
- Visual weight ranking: the four spine nodes (equal to one another) → the two outcome nodes (equal to one another, lighter than the spine) → Non-Panel Actor Authorization (lightest on the canvas).

**Negative space**
- A clear gap separates the spine from the denial rail, and a larger gap separates the spine from the non-panel branch — proportioned so a reader senses, before reading anything, that there are three tiers of relevance to this model: the spine, its outcomes, and the parallel mechanism beside it.

---

## 10. Legend

- Rounded rectangle, accent border, identical across four instances — a spine concept; all four are equally necessary steps in constructing a decision.
- Rectangle, double border — the granted outcome, reused from Figure A.
- Octagon — the shared denied outcome, reused from Figure A.
- Pill shape — the non-panel actor track; authorized outside the role model entirely.
- Solid connector — the spine itself, the sequence a decision is actually built through.
- Dashed connector — a bypass, a denial, or the branch to the non-panel track; always secondary to the spine.
- Internal sub-list inside a node — supporting detail belonging to that concept; not a separate node in its own right.

---

## 11. Reading Order

1. The reader takes in the four spine nodes as one continuous idea — same shape, same size, same accent — in under five seconds, before reading a single word: "there are four equally necessary steps."
2. The reader follows the solid line down the spine: Role Model, then Business Membership, then Authorization Scope, then Governance Rules, understanding this as the order the questions are conceptually asked in — not the order any specific request happens to execute them.
3. The reader notices the short internal lists inside Authorization Scope (three breadths) and Governance Rules (four rules), understanding these as richness within a step, not additional steps.
4. The reader notices the dashed denial rail beside the spine, with three drop-ins, understanding "a decision can be turned away at any of three points, immediately, without proceeding further."
5. The reader notices the single dashed branch leaving Role Model toward the non-panel track, understanding that Customer, Bot, and System are resolved by an entirely different mechanism that the rest of the figure never touches.
6. Last, the reader reaches Business Operation at the foot of the spine, the sole positive destination.

A reader who can redraw this model from memory — four steps, a denial rail, one side branch — thirty seconds after closing the page has read it correctly. A reader who needs to re-read any node's internal list to reconstruct the model has been given a figure that is still too detailed.

---

## 12. Publication Checklist

- Exactly seven nodes: four spine concepts, two outcomes, one parallel-track concept. No additional node, and no internal sub-list item (the three scope breadths, the four governance rules) may be promoted to its own connected node.
- Exactly nine connectors: four solid (the spine plus its final grant), five dashed (one bypass, three denial drop-ins, one non-panel branch). No connector may be added that isn't accounted for above.
- All four spine nodes share identical size, border weight, and accent treatment. No renderer discretion to make one "pop" more than the others — this is the figure's central, non-negotiable rule, and the one most likely to be reintroduced by habit if not enforced explicitly.
- Access Denied and Business Operation reuse Figure A's exact shape vocabulary (octagon; double-border rectangle) — a renderer must not invent new shapes for outcomes already defined in Figure A.
- No individual endpoint, business operation instance, or aggregate name (Appointment, BlockedSlot, User, etc.) appears as a node anywhere in the figure.
- No method name appears anywhere. `AuthorizationService` may appear at most once, in prose only (e.g. within Authorization Scope's caption note), never as a node label, and the figure must not require it to be understood.
- Every fact in this figure maps to a sentence already present in AUTHORIZATION.md's Authorization Philosophy, Role Model, Authorization Service Responsibilities, Governance Rules, Appointment Authorization, or BlockedSlot Authorization sections. Nothing here is traced to a source not already approved for publication.
- The published caption must follow the Architecture Style Guide's caption format, link to ADR-016, and include one explicit sentence distinguishing this figure's conceptual-narrowing axis from Figure A's execution-order axis, so the two are never mistaken for redundant restatements of each other.
- **Open item, carried over from the prior draft, still unresolved:** AUTHORIZATION.md's own Figure B description (its "What this figure will show" paragraph) still promises a literal Appointment/BlockedSlot operation-by-role conditional matrix. This blueprint — in both its rejected first form and this redesign — builds a concept-level model instead. AUTHORIZATION.md's Figure B placeholder text still needs your decision on how to reconcile this before the figure is considered frozen.

---

## Final Self-Audit

- Does this figure teach the authorization model rather than the component's internal taxonomy? Yes — the four internal decision shapes and four governance exceptions from the discovery pass are now sub-content inside two concept nodes, not primary nodes in their own right.
- Is it understandable in under 30 seconds? Yes — four identically-styled spine nodes convey "four equal steps" before any text is read; the denial rail and side branch are each a single visual idea, not a set to enumerate.
- Are there 6–8 primary concepts, not 11 equally-weighted nodes? Yes — seven nodes total, with a clear internal ranking (four primary, two outcome, one supporting).
- Would a reader remember the model after closing the page? Yes — "role, membership, scope, governance, then granted or denied; non-panel actors are separate" is a five-clause sentence, not an eleven-item list.
- Does it still rest entirely on verified fact, with no re-auditing performed? Yes — every claim traces to the discovery already confirmed against `AuthorizationService`, `AppointmentService`, `BlockedSlotService`, and the per-business policy gate in the prior session, reused here without alteration to the underlying facts.

All five checks pass.
