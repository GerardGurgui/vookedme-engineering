# Architecture Decision Records

This directory contains Architecture Decision Records (ADRs) for the VookedMe appointment scheduling platform.

An ADR documents a significant architectural decision: the context that made it necessary, the decision itself, the alternatives considered and rejected, and the consequences accepted. ADRs are the primary artifact of this repository — they answer *why* the system is designed the way it is.

---

## Status

> **v0.3.0 — Batch 2 published.** ADR-003, ADR-002, and ADR-004 are live. Further batches follow the [publication pipeline](../meta/publication-pipeline.md).

---

## Reading Order

Start with the editorial value classification. Read FOUNDATIONAL ADRs before CORE, CORE before ADVANCED.

**If you have 10 minutes:** Read ADR-011 → ADR-001 → ADR-007 in that order. These three ADRs communicate the system's design philosophy more completely than any other entry point.

**If you are reviewing the security model:** ADR-018 (JWT rotation), ADR-016 (tenant isolation), ADR-003 (audit architecture).

**If you are reviewing the domain model:** ADR-017 (appointment FSM), ADR-011 (temporal boundary), ADR-004 (customer lifecycle).

**If you are reviewing the AI/bot architecture:** ADR-007 (derive architecture), ADR-012 (conversational coherence), ADR-015 (GDPR data minimisation in conversation).

---

## Complete Index

### Published

| ADR | Title | Editorial | Status |
|---|---|---|---|
| [ADR-011](./ADR-011-appointment-temporal-boundary.md) | Appointment Temporal Boundary (PFT) | FOUNDATIONAL | Accepted |
| [ADR-001](./ADR-001-single-money-field.md) | Single Money Field Invariant | CORE | Accepted |
| [ADR-003](./ADR-003-hybrid-audit-strategy.md) | Three-Layer Audit Architecture | CORE | Accepted |
| [ADR-007](./ADR-007-bot-panel-derive-architecture.md) | Derive Bot State from Source of Truth | CORE | Accepted |
| [ADR-002](./ADR-002-blocked-slot-state-machine.md) | BlockedSlot State Machine | ADVANCED | Accepted |
| [ADR-004](./ADR-004-customer-lifecycle-states.md) | Customer Lifecycle States | ADVANCED | Proposed |

### Planned — Batch 3 (Identity, 