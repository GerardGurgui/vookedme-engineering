# Governance Documentation

This directory contains domain governance documents — the rules that the implementation must follow.

Governance documents are more stable than implementation decisions and more specific than architectural principles. They describe what transitions are legal, who can do what, what constitutes a destructive action, and what audit trails are required.

---

## Status

> **Pending population.** Governance documents will be added in v0.2.0 per the [RELEASE_STRATEGY.md](../../RELEASE_STRATEGY.md).

---

## Documents (Planned)

| Document | Description |
|---|---|
| [state-machines.md](./state-machines.md) | Appointment FSM (6 states) + BlockedSlot FSM (5 states) with transition tables and ASCII diagrams |
| [permissions.md](./permissions.md) | RBAC matrix: role × resource × action for ADMIN / OWNER / EMPLOYEE |
| [destructive-actions.md](./destructive-actions.md) | The "no destruir" principle: what requires extra guards, what is reversible, what is permanent |
| [audit-requirements.md](./audit-requirements.md) | Three-layer audit strategy (L1: universal updated_by, L2: named event columns, L3: audit_logs table) |
| [cancellation-request-workflow.md](./cancellation-request-workflow.md) | The CANCELLATION_REQUESTED state lifecycle: creation, approval, rejection, expiry |

---

## Relationship to ADRs

Governance documents tell an engineer **what the rule is**. The corresponding ADR tells them **why it exists**. Neither is sufficient without the other.

Example: `state-machines.md` documents the CANCELLED state and the CANCELLATION_REQUESTED state. [ADR-002](../adr/ADR-002-blocked-slot-state-machine.md) documents why CANCELLATION_REQUESTED exists as a distinct state (rather than auto-cancelling) and what alternatives were rejected.
