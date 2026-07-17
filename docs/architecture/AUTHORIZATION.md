# Authorization Architecture

> **AX-4 of the Architecture Experience Journey.** This document answers one engineering question: *who is allowed to perform each business operation, under what conditions, and where is that decision made?*

**Status:** Draft — Figures A, B, and C frozen; pending Figure D and final editorial review.

---

## Engineering Question

Who is allowed to perform each business operation, under what conditions, and where in the system is that decision made?

This document is scoped to business authorization — the rules governing what an actor may do once its identity is established. It does not cover authentication, credential issuance, or the request-security pipeline; those belong to the Security Architecture document.

---

## Introduction

VookedMe serves three structurally different kinds of actor against the same domain: platform-authenticated staff (ADMIN, OWNER, EMPLOYEE), a conversational customer channel with no session of its own, and automated system processes. Each has a distinct authorization model, and the platform-authenticated roles are further constrained by governance rules that go beyond a simple role check — business membership, self-versus-others, and lifecycle state all narrow what an otherwise-permitted actor may do in a given moment.

This document describes that model: the roles, the mechanism that enforces them, the resulting permission outcomes for the two most heavily-governed aggregates — Appointment and BlockedSlot — and the governance rules that shape authorization beyond a flat role check.

---

## Authorization Philosophy

Authorization is enforced in layers of increasing precision. A coarse layer establishes whether a request is authenticated at all, and gates a small number of platform-wide operations by role alone. A dedicated layer resolves the finer question a role cannot answer by itself: does this specific caller have standing over this specific business, this specific record, or this specific action, right now?

This reflects a deliberate trade-off. A flat role check — "is this caller an OWNER" — is simple to write and simple to audit, but cannot express business boundaries or self-versus-colleague distinctions on its own. Those require identity and business context that a role alone cannot supply. The system resolves this by keeping role checks coarse and delegating every business-, ownership-, and self-scoped decision to a single dedicated authorization component.

Non-panel actors do not participate in this role model at all. A conversational customer request is authorized by matching the requester's identity against the record it targets, not by any role. Automated system processes carry no authorization check — they are trusted infrastructure operating outside the request boundary that role-based authorization governs.

---

## Role Model

Three platform roles exist, in a flat structure with no implied hierarchy:

- **ADMIN** — a platform-level operator, not scoped to any single business. Holds full authority across every business in the system, including operations no business-scoped role can perform, such as creating or deactivating a business itself.
- **OWNER** — the principal account of a business. Holds full authority within that business, and is the only role permitted to accept certain legally-attributable obligations on the business's behalf (see Governance Rules).
- **EMPLOYEE** — a staff member of a business. Holds broad read access within the business and narrower write access, generally limited to work assigned to them. Several actions available to an OWNER or ADMIN are unavailable to an EMPLOYEE even for their own assigned work, by governance rule rather than by a blanket role restriction.

Beyond these three, the system recognises three further actor categories that never hold a platform role and are authorized through entirely different mechanisms:

- **Customer** — the WhatsApp end user. A customer never authenticates into the platform directly; every action attributed to a customer is relayed through the conversational channel and authorized by matching the requester's identity against the record being acted on.
- **Bot** — the conversational channel itself, acting on a customer's behalf, or on its own initiative for read operations. Authorized as a channel, not as an individual identity.
- **System** — scheduled, unattended processes that enforce time-based lifecycle rules, such as expiring a stale approval window. Authorized implicitly, as trusted infrastructure, since these processes never originate from an external request.

Every domain event in the system is attributed to one of seven actor values captured at the moment of the action — the three platform roles, the customer and bot channels, and two variants of automated processing (immediate system action and scheduled processing). This attribution is independent of authorization itself: it exists so that every change to a governed record carries a permanent record of who, or what, made it.

---

## Authorization Architecture

Although the dedicated authorization component is the primary mechanism for business-, ownership-, and self-scoped decisions, a small number of legacy authorization checks remain distributed across older parts of the codebase. Future iterations aim to consolidate these responsibilities into the same component.

---

## Figure A — Authorization Enforcement Architecture

![Figure A — Authorization Enforcement Architecture](assets/Figure%20A%20Authorization%20Enforcement%20Architecture.png)

> **Figure A** — *Authorization Enforcement Architecture*: Where in the request lifecycle is a business-authorization decision actually made, and by what mechanism? See [ADR-016 — Tenant Isolation Pattern](../adr/ADR-016-tenant-isolation-pattern.md).

---

## Authorization Service Responsibilities

The dedicated authorization component answers seven distinct questions, each corresponding to a recurring shape of authorization decision in the domain:

| Question answered | Applies to |
|---|---|
| Does the caller belong to the requested business? | Any operation scoped to a single business |
| Is the caller the business's OWNER, or an ADMIN? | Operations restricted to business leadership |
| Is the caller acting on their own record, or does the caller hold OWNER/ADMIN authority? | Operations where a colleague's own data is otherwise off-limits |
| Is the caller claiming an operation for themselves, or does the caller hold OWNER/ADMIN authority? | Self-service claim operations available to more than one role |
| May the caller create or manage an availability block for a given staff member, including business-wide blocks? | Capacity-management operations with an asymmetric staff/leadership split |
| May the caller remove a specific availability block, given who created it? | Deletion operations where authorship narrows authority |
| Does the caller hold platform ADMIN authority? | Platform-wide operations outside any single business's scope |

Every operation described in this document that is scoped to a business, an individual record, or a specific actor resolves to one of these seven questions.

---

## RBAC Overview

Read access across most business-scoped aggregates follows a simple default: any platform role with membership in the business may read that business's operational data. A small number of read operations — administrative listings, exported reports, and audit-adjacent views — are restricted to OWNER and ADMIN, reflecting a distinction between day-to-day operational visibility and business-level oversight.

---

## Figure B — Authorization Decision Model

![Figure B — Authorization Decision Model](assets/Figure%20B%20-%20Authorization%20Decision%20Model-selection.png)

> **Figure B** — *Authorization Decision Model*: How is an authorization decision progressively built, from identifying the actor to allowing a protected business operation? This figure describes the conceptual dimensions of a decision, not the request-execution order shown in Figure A. See [ADR-016 — Tenant Isolation Pattern](../adr/ADR-016-tenant-isolation-pattern.md).

---

## Appointment Authorization

An EMPLOYEE's authority over an appointment is narrower than an OWNER's or ADMIN's, and the asymmetry is deliberate rather than a blanket restriction:

- An EMPLOYEE may not directly cancel an appointment. Requesting cancellation is a distinct, two-step operation: the EMPLOYEE raises a cancellation request, and only an OWNER or ADMIN may approve or reject it. An OWNER or ADMIN cancels directly, without the request step.
- Neither an OWNER nor an ADMIN may approve or reject a cancellation request they themselves raised. Self-approval and self-rejection of one's own request is barred for every actor, regardless of role.
- An EMPLOYEE may only bring an appointment to COMPLETED or NO_SHOW for an appointment assigned to them, and only after the appointment's scheduled time has passed, a rule shared with every other actor and established by Appointment Temporal Boundary (ADR-011).
- Assignment of an appointment to a staff member follows the same asymmetry seen elsewhere: an OWNER or ADMIN may assign any staff member, while an EMPLOYEE may only claim an appointment for themselves.

Where a staff member's request touches an appointment assigned to a colleague rather than to themselves, the system does not distinguish, from the requester's perspective, between the appointment not existing and the appointment existing but belonging to someone else. This is a deliberate choice: it avoids confirming the existence of another staff member's assignment to a caller who has no standing over it.

---

## Figure C — Authorization Mechanism Composition

![Figure C — Authorization Mechanism Composition](assets/Figure%20C%20-%20Authorization%20Mechanism%20Composition%20FINAL-selection.png)

> **Figure C** — *Authorization Mechanism Composition*: How do multiple distinct authorization mechanisms coexist within a single aggregate's lifecycle without altering its state machine? Using Appointment as a case study rather than as the subject, this figure introduces no new architectural fact beyond what this document and the Governance Journey already establish — it does not modify, replace, or redraw the Appointment FSM published in AX-3. See [ADR-011 — Appointment Temporal Boundary](../adr/ADR-011-appointment-temporal-boundary.md) and [ADR-016 — Tenant Isolation Pattern](../adr/ADR-016-tenant-isolation-pattern.md).

---

## BlockedSlot Authorization

An availability block created directly by an OWNER or ADMIN takes effect immediately. A block requested by an EMPLOYEE enters a pending state and requires OWNER or ADMIN approval before it affects calendar availability — and, as with appointment cancellation requests, an OWNER or ADMIN may not approve or reject a request they themselves created. Once a block has been approved, only an OWNER or ADMIN may cancel it; a staff member loses cancellation authority over their own request the moment it is approved, a governance rule already recorded in the published state-machine document.

The ability for a staff member to initiate a block request at all is a configurable business-level setting rather than a fixed platform-wide rule — a business may choose to restrict availability-block requests to its leadership only.

This document reflects the system's current governance rule. Any difference from ADR-002's original text is already addressed in the published Governance Journey's state-machine document, which this document defers to for historical context; AX-4 intentionally describes the architecture as it stands today rather than its history.

---

## Figure D — BlockedSlot Transition Authorization

> Figure placeholder

**Engineering question:** For each transition in the BlockedSlot lifecycle already published in the Governance Journey's state-machine document, which actor is authorized to trigger it, and how has that authority changed over time?

**What this figure will show:** The five-state BlockedSlot lifecycle, annotated with the authorized actor for every transition, foregrounding the governance change — already recorded in that publication — under which a staff member lost the authority to cancel a block once it has been approved, even a block they created themselves. This figure is a new authorization artefact that complements the Governance Journey; it does not modify or replace the frozen AX-3 state-machine diagrams.

*(Diagram or table will be produced after this document is frozen.)*

---

## Governance Rules

Several authorization outcomes in this system follow from an explicit governance decision rather than from the role model alone:

**Self-approval is structurally barred.** No actor — including an OWNER or ADMIN — may approve, reject, or otherwise resolve a request they themselves originated, whether that request is an appointment cancellation or an availability block. Authority to raise a request and authority to resolve it are always held by different parties.

**Certain legally-attributable actions are restricted to the business OWNER specifically, excluding even the platform ADMIN.** Accepting a data-processing obligation on the business's behalf, and attesting to a customer's communication-consent status, both require the OWNER's own action. This reflects a distinction between platform-level operational authority, which an ADMIN holds broadly, and business-level legal accountability, which rests with the OWNER alone as the party who bears it.

**Business OWNER accounts cannot be removed through routine staff-management operations, and account deletion is not supported.** An OWNER account may be deactivated only through a separate administrative process, and no mechanism exists to permanently delete any platform account — consistent with the system's broader append-only approach to accountability (see the planned Audit & Compliance architecture document).

**EMPLOYEE authority over an appointment or availability block never exceeds OWNER/ADMIN authority, and in specific lifecycle states is narrower still than a simple role comparison would suggest.** The cancellation-request pattern (Appointment) and the approval-then-lock pattern (BlockedSlot) both express the same underlying principle: an EMPLOYEE may initiate a change to a governed record, but resolving that change — approving, rejecting, or reversing it — is reserved to business leadership.

---

## Related ADRs

| ADR | Relevance to this document |
|---|---|
| [ADR-016 — Tenant Isolation Pattern](../adr/ADR-016-tenant-isolation-pattern.md) | The dedicated authorization component and the single-enforcement-point principle this document builds on |
| [ADR-006 — User Identity Model](../adr/ADR-006-user-identity-model.md) | The global user identity that business membership and role are evaluated against |
| [ADR-011 — Appointment Temporal Boundary](../adr/ADR-011-appointment-temporal-boundary.md) | The temporal restriction on closure-state transitions referenced in Appointment Authorization |
| [ADR-002 — BlockedSlot State Machine](../adr/ADR-002-blocked-slot-state-machine.md) | The approval lifecycle referenced in BlockedSlot Authorization |

A dedicated ADR formally documenting the role model and its governance rules as a standalone architectural decision does not yet exist. This document draws its content from ADR-016 and ADR-006 together with the governance rules already recorded in the published state-machine document; a future ADR consolidating these into a single decision record is a natural next addition to the ADR Journey.

---

## Related Source Artefacts

| Artefact | What it shows |
|---|---|
| [AuthorizationService.java](../../src/main/java/com/vookedme/botmanager/auth/service/AuthorizationService.java) | The dedicated authorization component described throughout this document |
| [SourceActor.java](../../src/main/java/com/vookedme/botmanager/common/event/SourceActor.java) | The seven-value actor attribution model referenced in the Role Model section |
| [Appointment.java](../../src/main/java/com/vookedme/botmanager/appointment/entity/Appointment.java) | The aggregate governed by Appointment Authorization |
| [AppointmentStatus.java](../../src/main/java/com/vookedme/botmanager/appointment/entity/AppointmentStatus.java) | The lifecycle states of the aggregate used as Figure C's case study — not enumerated within the figure itself |
| [BlockedSlot.java](../../src/main/java/com/vookedme/botmanager/schedule/entity/BlockedSlot.java) | The aggregate governed by BlockedSlot Authorization |
| [BlockedSlotStatus.java](../../src/main/java/com/vookedme/botmanager/schedule/entity/BlockedSlotStatus.java) | The five lifecycle states referenced in Figure D |

---

## Reading Notes

This document is scoped to authorization outcomes — who may act, under what condition. Authentication and request-security architecture are described in AX-5 — Security Architecture.

The role-operation outcomes described here reflect the system's current, accepted governance rules. Where a rule has changed over time — most notably the BlockedSlot approval-then-lock restriction — this document states only the rule as it stands today; the change itself is recorded in the governance state-machine document's own revision history.
