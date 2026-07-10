# Architecture Decision Records

This directory contains Architecture Decision Records (ADRs) for the VookedMe appointment scheduling platform.

An ADR documents a significant architectural decision: the context that made it necessary, the decision, the alternatives rejected, and the consequences accepted.

---

## Status

> **Pending population.** ADR files will be added in v0.2.0 per the [release strategy](../meta/release-strategy.md).

---

## Index

| ADR | Title | Status |
|---|---|---|
| ADR-001 | Single Money Field — Payment Boundary | Accepted |
| ADR-002 | BlockedSlot State Machine | Accepted |
| ADR-003 | Hybrid Audit Strategy (L1/L2/L3) | Accepted |
| ADR-004 | Customer Lifecycle States | Accepted |
| ADR-006 | User Identity Model | Accepted |
| ADR-007 | Bot/Panel Derive Architecture | Accepted |
| ADR-010 | Owner Active Notifications | Accepted |
| ADR-011 | Appointment Temporal Boundary (PFT) | Accepted |
| ADR-013 | Customer Communication Consent Policy | Accepted |

---

## Writing Guide

### When to Write an ADR

Write an ADR when:
- The decision is not obvious to a future engineer reading the code alone
- The decision has significant architectural consequences
- Multiple alternatives were seriously considered
- A future engineer might be tempted to reverse the decision without knowing why it was made

Do not write an ADR for implementation details, style choices, or self-evidently correct decisions.

### Format

```markdown
# ADR-NNN — Title

**Status:** Accepted
**Date:** YYYY-MM-DD
**Authors:** [name]

---

## Context

## Decision

## Alternatives Considered

| Option | Why rejected |
|---|---|

## Consequences

## Related
```

See [repository standards §3](../meta/repository-standards.md) for the full ADR convention specification.

### Status Values

| Status | Meaning |
|---|---|
| Proposed | Under discussion, not yet implemented |
| Accepted | Decision is final and implemented |
| Superseded by ADR-NNN | A later decision changed this one |
| Deprecated | Abandoned before implementation |

ADRs are never deleted. Superseded ADRs remain readable as historical context.
