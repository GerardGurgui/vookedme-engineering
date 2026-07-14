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

[![Figure 1.b — Appointment lifecycle: Modo Autónomo (AUTO_CONFIRM)](./Figure%201b%20-%20Appointment%20FSM%20-%20Autonomo-selection.png)](./Figure%201b%20-%20Appointment%20FSM