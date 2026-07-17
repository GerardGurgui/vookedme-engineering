# Figure C — Technical Blueprint

**Status:** Frozen semantic specification — Phase 2 of the Architecture Figure Pipeline.
**Scope:** AX-4, Authorization Architecture.
**Not covered by this document:** visual design, layout, HTML, CSS, colour, coordinates, rendering.

---

## 1. Purpose

Figure C exists to correct an impression the rest of AX-4 could otherwise leave: that authorization is one uniform mechanism applied consistently everywhere. Figure A shows *where* enforcement sits in the request pipeline; Figure B shows *how* a decision is conceptually assembled; AX-3 shows *which actor* may trigger *which transition*. None of the three shows that, within a single real aggregate, authorization is actually a composition of several distinct mechanism types working side by side — some resolved by the platform's dedicated authorization component, some by domain logic that needs context the generic component doesn't have, some by rules that exist independently of role, some by nothing but the clock, and some by a per-business setting that decides whether a state is reachable at all. Figure C teaches that composition, using Appointment only as the concrete surface on which it can be observed.

---

## 2. Engineering Question

How do multiple distinct authorization mechanisms coexist within a single aggregate's lifecycle without altering its state machine? *(Frozen per Phase 1.5.)*

---

## 3. Architectural Message

Authorization inside Appointment is not implemented by one mechanism. Five distinct, complementary mechanism types protect different decisions across the same lifecycle, and they can all be present at once without the FSM itself changing shape. The state machine is a constant; the enforcement mechanism behind any given transition is not.

---

## 4. Core Concepts

Six elements: five that relate to authorization, plus one non-mechanism framing device. The five are not a single flat category — they belong to three distinct conceptual families, based on *how* each one relates to an authorization decision, not merely that each is somehow "involved" in one. This grouping is a classification refinement (Phase 2.5) over the same six concepts frozen in Phase 2; no concept's own description below has changed.

> **Resolution Mechanisms** — the point where an actual permit/deny verdict is produced for a specific actor performing a specific action.

**Dedicated Authorization Component.** Represents the single platform-wide component already introduced in Figure A and enumerated in AUTHORIZATION.md's "Authorization Service Responsibilities." Necessary as an anchor point: it is the mechanism the reader already knows, and the one every other mechanism in this figure is implicitly contrasted against.

**Domain-Service Authorization.** Represents authorization logic evaluated inside the domain service itself, for decisions that need domain context the generic component cannot express in a reusable way — most clearly, the narrower authority an EMPLOYEE holds over their own assigned work, already described in AUTHORIZATION.md's "Appointment Authorization" section. It is necessary because it shows the dedicated component is not the *only* legitimate place a decision can be resolved. **Scope caveat (see §9):** this node must represent only the deliberate, already-published instances of this pattern — not the broader inconsistency the Phase 1 discovery also found, where structurally equivalent decisions are gated differently for no principled reason. AUTHORIZATION.md's own "Authorization Architecture" section already names that broader inconsistency as legacy debt slated for consolidation, not as intentional design. Figure C must not launder that debt into an architectural virtue.

> **Governing Constraints** — do not themselves resolve anything. They restrict or veto what a resolution mechanism would otherwise allow, each for a reason unrelated to resolving *who* the actor is.

**Governance Constraint.** Represents a rule that restricts an otherwise-permitted actor for reasons outside the role model entirely — the self-approval bar is the published example. Necessary because it is categorically different from a role check: it can override an actor who has already passed every prior check.

**Temporal Boundary.** Represents the plane-based restriction already published as ADR-011 and referenced in "Appointment Authorization": certain transitions are authorized or barred purely by whether a timestamp has passed, independent of who the actor is. Necessary because it is the one mechanism in this figure that never asks "who," only "when."

> **Structural Context** — defines the environment in which resolution and constraint operate at all, decided before any specific decision is evaluated.

**Configuration-Dependent Reachability.** Represents a per-business setting that changes which states, and which creating actor, are even reachable — already partially visible in AX-3's mode table, but not previously framed as an authorization mechanism in its own right. Necessary because it operates at a different level than the other four: it doesn't decide whether a given actor may perform a given transition, it decides whether the transition exists to be authorized at all for that business.

> **Outside all three families** — the frame is not classified alongside the five; it never resolves, constrains, or scopes anything itself.

**Case Study Frame — Appointment.** Not a mechanism, and must never be drawn or weighted as a peer of the five above. It is the labelled boundary within which all five are observed simultaneously. Necessary only so the reader understands the mechanisms are being shown in a real setting rather than in the abstract — the same role Figure B avoided giving to any single node, applied here to keep Appointment from becoming the subject.

---

## 5. Relationships

- **The five mechanisms coexist, they do not sequence.** There is no "first this, then that" — the relationship among them is composition within one aggregate, not a pipeline. This distinguishes Figure C from Figure A, which *is* a pipeline.
- **Dedicated Authorization Component and Domain-Service Authorization are alternative resolution points**, not stages. For any given decision, one or the other resolves it — never both — but across the aggregate's full lifecycle, both are in active use. The relationship exists to show the reader that "resolved by the dedicated component" is not universal.
- **Governance Constraint sits above whichever resolution point applies.** A role check (from either resolution point) can permit an action that a governance constraint still vetoes. The relationship is override, not addition — it exists because the self-approval bar is not a role distinction, it is a veto layered on top of one.
- **Temporal Boundary is orthogonal to actor identity.** It applies regardless of which resolution point or which role passed judgment first. The relationship is independence — it exists to show the reader that not every constraint in this system is actor-shaped.
- **Configuration-Dependent Reachability is structurally prior to all four others.** It doesn't compete with them; it determines whether there is anything for them to authorize in the first place, for a given business. The relationship is scoping, not enforcement — it exists to show the reader that some authorization-relevant facts are decided before any decision-time mechanism runs at all.
- **The Case Study Frame contains, but does not act on, all five.** The relationship is illustrative containment — it exists purely so the five mechanisms are seen operating together rather than described in isolation.

---

## 6. Narrative

**Beginning:** The reader enters through the Case Study Frame — this is Appointment, an aggregate they may already know from AX-3's FSM. The frame's only job is to say: everything you're about to see coexists inside this one lifecycle.

**Middle:** The reader encounters the two resolution points side by side — the dedicated component they already know from Figure A, and domain-service authorization as a legitimate second point of resolution, not a lesser one. Layered above both, the reader sees a governance constraint that can override either. Beside that, independent of all of it, the temporal boundary — a mechanism that never asks who, only when.

**End:** The reader arrives at configuration-dependent reachability last, and understands it sits a level above everything else: it is the mechanism that can make a state, and everything that would have authorized it, simply not apply for a given business. The reader leaves understanding that five different *kinds* of mechanism protect one aggregate, that this composition is deliberate, and that AX-3's state machine never had to change to accommodate any of it.

---

## 7. What Must NOT Appear

- The Appointment FSM, in any redrawn or partial form.
- Any transition list, transition table, or per-transition annotation.
- Any role matrix (ADMIN/OWNER/EMPLOYEE grid) or CRUD matrix.
- Any controller name, service name, or method name (including the specific inline-branching methods discovered in Phase 1).
- Any field name, enum name, or configuration-flag name — including the specific per-business mode setting; even though AX-3 already publishes its name, Figure C should stay at AUTHORIZATION.md's more abstract register rather than re-import AX-3's implementation-grounded vocabulary.
- Any migration or version identifier.
- Any timeout constant or numeric threshold.
- Any staff-assignment fairness/strategy detail (no authorization relevance; out of scope entirely).
- Any framing of the self-claim/assignment side channel discovered in Phase 1 as a "bypass," "gap," or "inconsistency" — it is out of scope for this figure, not a mechanism type.
- Any framing of the broader controller-vs-inline inconsistency (the true legacy-debt finding) as an intentional architectural pattern.
- Any file:line citation or other private-repository detail.

---

## 8. Relationship with the Architecture Experience

**Figure A** answers *where*, generically and system-wide, authorization is enforced in the request pipeline. Figure C does not compete with this — it shows that even within "where," a single aggregate can legitimately use more than one resolution point, a fact Figure A's pipeline view has no way to express.

**Figure B** answers *how* a decision is conceptually built, in the abstract, for any actor and any operation. Its "Governance Rules" node already names governance constraints and configuration gates abstractly. Figure C does not redefine or duplicate that vocabulary — it shows those abstract categories, plus two mechanism types Figure B does not individually distinguish (dedicated-component resolution vs. domain-service resolution, and temporal boundary as its own category), all present together on one real aggregate.

**AX-3** answers *which actor* triggers *which transition*, for both Appointment and BlockedSlot. Figure C never restates that table and never redraws that FSM. It answers a question AX-3's transition-level view structurally cannot: not who acts, but what kind of mechanism decided whether they could.

No responsibility is shifted from Figure D. Figure D remains the equivalent transition-authority publication for BlockedSlot; Figure C says nothing about BlockedSlot at all.

---

## 9. Publication Checklist

| Element | Family | Maps to | Status |
|---|---|---|---|
| Dedicated Authorization Component | Resolution Mechanism | AUTHORIZATION.md — "Authorization Service Responsibilities"; ADR-016; already the anchor of Figure A | Clean — reuses an already-approved public concept |
| Domain-Service Authorization | Resolution Mechanism | AUTHORIZATION.md — "Appointment Authorization" (EMPLOYEE narrower authority, self-claim, closure-eligibility restrictions) | Clean, **conditional on scope narrowing** — must represent only these published instances, not the unpublished inconsistency finding from Phase 1 |
| Governance Constraint | Governing Constraint | AUTHORIZATION.md — "Governance Rules" (self-approval bar) | Clean — already public |
| Temporal Boundary | Governing Constraint | AUTHORIZATION.md — "Appointment Authorization"; ADR-011 | Clean — already public |
| Configuration-Dependent Reachability | Structural Context | AX-3 state-machines.md mode table (fact already public); reframed here as an authorization mechanism, which AX-3 does not do | Clean — fact is public, framing is new and additive, not contradictory |
| Case Study Frame (Appointment) | Outside all families | AUTHORIZATION.md — "Appointment Authorization" section header; AX-3's Appointment FSM (referenced, not redrawn) | Clean |
| Any transition-level detail | — | — | Excluded by design |
| Any implementation identifier | — | — | Excluded by design |

**Taxonomy note (Phase 2.5):** the Family column above is a classification refinement only. It groups the same six elements frozen in Phase 2 by *how* each relates to an authorization decision — resolving it, constraining it, or scoping the environment it operates in — so a future renderer cannot default to five visually identical boxes. It introduces no new concept, changes no relationship, and does not alter the Engineering Question, Architectural Message, or Narrative frozen earlier in this document.

**Open item requiring resolution before Phase 3:** the Domain-Service Authorization node must be scoped narrowly (see §4, §9 row above) so that it teaches an intentional, already-published pattern rather than dignifying the unrelated legacy-debt inconsistency surfaced in Phase 1 discovery as if it were deliberate architecture. This is a framing decision, not a visual one, and belongs in the frozen blueprint rather than left for the HTML specification phase to improvise.

---

## 10. Recommendation

**READY FOR PHASE 3**, on the condition that the Domain-Service Authorization node's narrowed scope (§4, §9) carries forward unchanged into the HTML specification — it is now part of the frozen semantic definition, not an open question.
