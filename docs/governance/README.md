# Governance Documentation

This directory contains domain governance documents — the rules that the implementation must follow.

Governance documents occupy a specific layer in the knowledge hierarchy: more stable than implementation decisions, more concrete than architectural principles. While an ADR answers *why* a decision was made, a governance document answers *what the rule is* today. Together they give a complete picture: the rule and its rationale.

Each document in this directory targets a specific engineering question: which FSM transitions are legal, who is authorised to perform which action, what requires an extra safety gate, what audit trail a feature must leave behind.

---

## Published

| Document | Covers | Figures | Version |
|---|---|---|---|
| [state-machines.md](./state-machines.md) | Appointment FSM (6 states, 2 bot modes) · BlockedSlot FSM (5 states) · Temporal boundary invariant | [Fig 1.a](#figure-1a--appointment-fsm--modo-recepcionista) · [Fig 1.b](#figure-1b--appointment-fsm--modo-autónomo) · [Fig 2](#figure-2--blockedslot-fsm) | AX-3 · v1.8.0 |

---

## Diagrams

### Figure 1.a — Appointment FSM · Modo Recepcionista

> `APPROVAL_REQUIRED` — Bot creates `PENDING`, owner holds the approval gate.

[![Figure 1.a — Appointment lifecycle: Modo Recepcionista (APPROVAL_REQUIRED)](./Figure%201%20-%20Appointment%20FSM-selection.png)](./Figure%201%20-%20Appointment%20FSM-selection.png)

---

### Figure 1.b — Appointment FSM · Modo Autónomo

> `AUTO_CONFIRM` — Bot creates `CONFIRMED` directly, employee auto-assigned (V66). No approval gate.

[![Figure 1.b — Appointment lifecycle: Modo Autónomo (AUTO_CONFIRM)](./Figure%201b%20-%20Appointment%20FSM%20-%20Autonomo-selection.png)](./Figure%201b%20-%20Appointment%20FSM%20-%20Autonomo-selection.png)

---

### Figure 2 — BlockedSlot FSM

> Five states · `APPROVED` is the sole state that blocks calendar availability · Bot creates `APPROVED` directly (ADR-002 discrepancy documented).

[![Figure 2 — BlockedSlot approval lifecycle](./Figure%202%20-%20BlockedSlot%20FSM-selection.png)](./Figure%202%20-%20BlockedSlot%20FSM-selection.png)

---

## Planned

| Document | Engineering question it answers |
|---|---|
| [permissions.md](./permissions.md) | RBAC matrix: which role can perform which action on which resource (`ADMIN / OWNER / EMPLOYEE × resource × verb`) |
| [destructive-actions.md](./destructive-actions.md) | Which operations are irreversible, which require a confirmation gate, and what the "no destruir" invariant means in practice |
| [audit-requirements.md](./audit-requirements.md) | Three-layer audit strategy: L1 universal `updated_by`, L2 named event columns, L3 `audit_logs` table — when each layer applies |
| [cancellation-request-workflow.md](./cancellation-request-workflow.md) | Full lifecycle of `CANCELLATION_REQUESTED`: creation actors, approval/rejection paths, PFT-1 timeout behaviour, ADR-011 constraints |

---

## Relationship to ADRs

Governance documents tell an engineer **what the rule is**. The corresponding ADR tells them **why it exists and what alternatives were rejected**. Neither is sufficient without the other.

| Governance document | Grounding ADRs |
|---|---|
| `state-machines.md` — Appointment FSM | [ADR-011](../adr/ADR-011-cancellation-policy.md) · [ADR-013](../adr/ADR-013-customer-appointment-change-communication.md) |
| `state-machines.md` — BlockedSlot FSM | [ADR-002](../adr/ADR-002-blocked-slot-state-machine.md) |
| `permissions.md` *(planned)* | [ADR-016](../adr/ADR-016-tenant-isolation-pattern.md) · [ADR-006](../adr/ADR-006-user-identity-model.md) |
| `audit-requirements.md` *(planned)* | [ADR-003](../adr/ADR-003-hybrid-audit-strategy.md) · [ADR-014](../adr/ADR-014-bot-data-minimisation.md) |
| `cancellation-request-workflow.md` *(planned)* | [ADR-011](../adr/ADR-011-cancellation-policy.md) |
