# Architecture Decision Records

This directory contains Architecture Decision Records (ADRs) for the VookedMe appointment scheduling platform.

An ADR documents a significant architectural decision: the context that made it necessary, the decision itself, the alternatives considered and rejected, and the consequences accepted. ADRs are the primary artefact of this repository — they answer *why* the system is designed the way it is.

---

## Status

> **v0.7.0 — ADR Journey complete.** Seventeen ADRs published across all editorial tiers. The ADR Journey is closed.

---

## Reading Order

Start with the editorial value classification. Read FOUNDATIONAL ADRs before CORE, CORE before ADVANCED.

**If you have 10 minutes:** Read ADR-016 → ADR-017 → ADR-011 in that order. These three establish the system's core invariants: how tenant data is structurally isolated, how the appointment domain entity moves through its lifecycle, and how the temporal boundary divides that lifecycle into two distinct operational planes.

**If you are reviewing the security model:** ADR-016 (tenant isolation), ADR-018 (JWT rotation), ADR-005 (email identity), ADR-003 (audit architecture).

**If you are reviewing the domain model:** ADR-017 (appointment FSM), ADR-011 (temporal boundary), ADR-008 (timestamp timezone), ADR-004 (customer lifecycle), ADR-006 (user identity model), ADR-009 (customer name policy).

**If you are reviewing the AI/bot architecture:** [ADR-007](./ADR-007-bot-panel-derive-architecture.md) (derive architecture), [ADR-012](./ADR-012-conversational-coherence.md) (conversational coherence), [ADR-014](./ADR-014-bot-data-minimisation-and-audit-log.md) (data minimisation), [ADR-015](./ADR-015-art9-gdpr-minimisation-conversational-flow.md) (GDPR minimisation in conversation), [ADR-013](./ADR-013-customer-appointment-change-communication-policy.md) (customer communication policy).

---

## Complete Index

### Published

| ADR | Title | Editorial | Status |
|---|---|---|---|
| [ADR-016](./ADR-016-tenant-isolation-pattern.md) | Tenant Isolation Pattern | FOUNDATIONAL | Accepted |
| [ADR-017](./ADR-017-appointment-fsm-design.md) | Six-State Appointment FSM | FOUNDATIONAL | Accepted |
| [ADR-011](./ADR-011-appointment-temporal-boundary.md) | Appointment Temporal Boundary (PFT) | FOUNDATIONAL | Accepted |
| [ADR-001](./ADR-001-single-money-field.md) | Single Money Field Invariant | CORE | Accepted |
| [ADR-018](./ADR-018-jwt-refresh-token-rotation.md) | JWT Refresh Token Rotation | CORE | Accepted |
| [ADR-003](./ADR-003-hybrid-audit-strategy.md) | Three-Layer Audit Architecture | CORE | Accepted |
| [ADR-006](./ADR-006-user-identity-model.md) | User Identity Model | CORE | Accepted |
| [ADR-007](./ADR-007-bot-panel-derive-architecture.md) | Bot-Panel Derive Architecture | CORE | Accepted |
| [ADR-002](./ADR-002-blocked-slot-state-machine.md) | BlockedSlot State Machine | ADVANCED | Accepted |
| [ADR-004](./ADR-004-customer-lifecycle-states.md) | Customer Lifecycle States | ADVANCED | Proposed |
| [ADR-008](./ADR-008-timestamp-timezone-migration.md) | Timestamp Timezone Migration | ADVANCED | Accepted |
| [ADR-009](./ADR-009-customer-name-policy.md) | Customer Name Policy — Unicode | ADVANCED | Accepted |
| [ADR-012](./ADR-012-conversational-coherence.md) | Conversational Coherence via State Re-anchoring | ADVANCED | Accepted |
| [ADR-013](./ADR-013-customer-appointment-change-communication-policy.md) | Customer Appointment Change Communication Policy | ADVANCED | Accepted |
| [ADR-014](./ADR-014-bot-data-minimisation-and-audit-log.md) | Bot Data Minimisation and Appointment Audit Log | ADVANCED | Accepted |
| [ADR-015](./ADR-015-art9-gdpr-minimisation-conversational-flow.md) | Art.9 GDPR Minimisation in Conversational Flow | ADVANCED | Accepted |
| [ADR-005](./ADR-005-email-identity.md) | Email Identity — Global Uniqueness | REFERENCE | Accepted |
