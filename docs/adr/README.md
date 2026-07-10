# Architecture Decision Records

This directory contains Architecture Decision Records (ADRs) for the VookedMe appointment scheduling platform.

An ADR documents a significant architectural decision: the context that made it necessary, the decision itself, the alternatives considered and rejected, and the consequences accepted. ADRs are the primary artefact of this repository — they answer *why* the system is designed the way it is.

---

## Status

> **v0.6.0 — Batch 5 published.** ADR-014, ADR-015, and ADR-012 are live. Fifteen ADRs published. Further batches follow the [publication pipeline](../meta/publication-pipeline.md).

---

## Reading Order

Start with the editorial value classification. Read FOUNDATIONAL ADRs before CORE, CORE before ADVANCED.

**If you have 10 minutes:** Read ADR-016 → ADR-017 → ADR-011 in that order. These three establish the system's core invariants: how tenant data is structurally isolated, how the appointment domain entity moves through its lifecycle, and how the temporal boundary divides that lifecycle into two distinct operational planes.

**If you are reviewing the security model:** ADR-016 (tenant isolation), ADR-018 (JWT rotation), ADR-005 (email identity), ADR-003 (audit architecture).

**If you are reviewing the domain model:** ADR-017 (appointment FSM), ADR-011 (temporal boundary), ADR-004 (customer lifecycle), ADR-006 (user identity model), ADR-009 (customer name policy).

**If you are reviewing the AI/bot architecture:** [ADR-007](./ADR-007-bot-panel-derive-architecture.md) (derive architecture), [ADR-012](./ADR-012-conversational-coherence.md) (conversational coherence), [ADR-014](./ADR-014-bot-data-minimisation-and-audit-log.md) (data minimisation), [ADR-015](./ADR-015-art9-gdpr-minimisation-conversational-flow.md) (GDPR minimisation in conversation).

---

## Complete Index

### Published

| ADR | Title | Editorial | Status |
|---|---|---|---|
| [ADR-016](./ADR-016-tenant-isolation-pattern.md) | Tenant Isolation Pattern | FOUNDATIONAL | Accepted |
| [ADR-017](./ADR-017-appointment-fsm-design.md) | Six-State Appointment FSM | FOUNDATIONAL | A