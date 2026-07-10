# ADR-001 — Single Money Field Invariant

**Status:** Accepted (retroactive — invariant in force since project inception; documented April 2026)  
**Date:** 2026-04-28  
**Domain:** appointment  
**Editorial:** CORE

> **Engineering Question Answered:** When does adding a single field to a database schema cross a legal threshold, and how do you enforce that line architecturally so it holds under sustained product pressure?

---

## Problem

An appointment scheduling platform inevitably attracts requests for payment-adjacent features: tip tracking, discount recording, partial payment support, refund history. Each request appears harmless in isolation. But a system that tracks the financial relationship between a customer and a business crosses a regulatory boundary that transforms it from scheduling software into a fiscal record-keeping system — with legal obligations for audit trail integrity, cryptographic event chaining, and regulatory authority integration. The question is not whether this boundary exists, but whether it can be encoded as an architectural constraint that holds under the pressure of incremental feature requests.

## Context

Spanish fiscal regulation (RD 1007/2023) classifies any system that issues invoices, processes payments, or maintains a fiscal-grade monetary record as a *Sistema Informático de Facturación* (SIF). SIF systems require hash-chained audit logs, QR codes on fiscal documents, and integration with the tax authority (AEAT) endpoint. The penalty for non-compliance is substantial; the compliance burden is the work of a specialist fiscal system, not a scheduling application.

EU PSD2 applies separately to any system that custodies funds or processes payment transactions. The combination of SIF and PSD2 obligations would require the platform to become something entirely different from what it is designed to be.

The platform's payment model is intentional: a price (what was agreed), and a flag (was it paid). These two fields answer the operational question — did this appointment get paid? — without creating a financial relationship the platform must track, reconcile, or audit. Together they cannot constitute a fiscal ledger.

The risk is incremental. A tip field for the holiday season. An `amount_paid` field for partial payments. A `discount` field for a loyal customer. Each addition is individually reasonable. Collectively they constitute a payments table that triggers obligations the platform is not equipped to fulfil. The danger is not a single decision to build a payments system — it is the accumulation of individually sensible decisions, none of which anyone recognises as the moment the line was crossed.

## Decision

The `Appointment` entity has exactly two money-bearing fields, and this count is a constitutional invariant:

1. **`price`** (`BigDecimal`) — a snapshot of the offering price captured at appointment creation. Set once. Never modified after the appointment exists.
2. **`paid`** (`Boolean`) — an operational flag. Can be toggled bidirectionally on completed appointments.

A third field records a label only and carries no monetary value:

3. **`payment_method`** (enum: `CASH / CARD / TRANSFER / BIZUM / LINK / OTHER`) — nullable; records how the customer paid, not how much. Not an input to any computation.

No additional money-bearing fields will be added. No exceptions. No "just this once for a premium tier."

### Why two fields cannot be a ledger

A fiscal ledger requires: amount owed, amount paid, and their reconcilable difference. Two fields cannot simultaneously express all three without one field serving double duty as an implicit computation. The moment `amount_paid != price` is a valid persistent state, the system is tracking partial payments. Partial payment tracking is a payment operation. Payment operations trigger PSD2 and SIF compliance requirements.

The invariant holds at a precise semantic boundary: **one price, one paid flag**. Any addition that requires these two fields to be in agreement with a third field is the moment the platform becomes a ledger.

### Why a schema invariant rather than a policy

Conventions drift. A coding convention ("do not add money fields") does not survive team growth, product pressure, or time. The engineer who adds the tip field two years from now may never have read this document. A schema invariant enforced by migration review, a test assertion that fails if a new money column appears in the `appointments` table, and an ADR that explicitly documents the legal consequence of violating it — that survives. The invariant is the architecture; the ADR is the reason it exists.

## Alternatives Considered

| Option | Description | Why Rejected |
|---|---|---|
| `amount_paid` separate from `price` | Track the actual amount paid versus the agreed price | The moment `amount_paid != price` is a valid state, the system is tracking partial payments — a payment operation covered by PSD2 |
| `tip`, `discount`, `surcharge` fields | Track additional monetary modifiers on the appointment | Individually reasonable; collectively they form a payments table. No safe stopping point exists between two fields and full SIF certification |
| `refund_amount` field | Record amounts returned to customers | A refund field with the semantics "the customer is owed money back" places the platform in a fiduciary position, regardless of how the field is labelled |
| Configurable extra field via business settings | Allow individual businesses to opt into additional money tracking | Settings refine policy; they cannot bypass load-bearing invariants. A toggle that opens a fiscal door is the door, not a refinement of the door |

## Consequences

### Positive
- The platform remains scheduling software. It does not cross the SIF threshold regardless of the volume it processes or the size of the businesses it serves.
- No PSD2 or e-money compliance obligations are incurred.
- Fiscal record-keeping responsibility stays with the customer's certified accounting software — where it is handled by a system purpose-built for it.
- Every future money-adjacent feature request is evaluated against a clear, pre-decided invariant. The decision is already made.

### Negative
- Partial payments, tip tracking, and refund recording cannot be offered, even when customers request them.
- Customers who need fiscal-grade records must export to certified external software. This is a documented platform limitation.
- Premium-tier feature growth is constrained to the operational axis — automation, analytics, multi-location, workflow — not the financial axis.

### Neutral
- Payment behaviours that happen out of band (split bills, informal discounts) are not tracked. The `paid` boolean records the final operational state only.
- Amending this invariant requires documented legal review, a new ADR explicitly superseding this one, and both ADRs remaining permanently in the repository. A migration alone is not sufficient.

## Engineering Principle

Legal constraints are architectural constraints. When a regulatory boundary — the precise point at which a system crosses from one legal classification to another — can be encoded as a schema invariant, encoding it that way is more durable than any policy or convention. The invariant cannot be forgotten; the policy can. A team that learns to treat a load-bearing schema constraint as an architectural decision, not a style preference, develops the discipline of asking "what does adding this field mean for the system's legal classification?" before asking "how do we implement it?" That question, asked early, is almost always cheaper to answer than the compliance work that follows from not asking it.

## Related

- [ADR-011](./ADR-011-appointment-temporal-boundary.md) — temporal boundary enforcement on the appointment lifecycle; the `paid` flag is only toggleable in the closure plane
- [ADR-017](./ADR-017-appointment-fsm-design.md) — the six-state FSM that determines when `togglePaid()` is legal *(planned)*
- [Governance: permissions.md](../governance/permissions.md) — which roles can toggle the `paid` flag and under what conditions *(planned)*

## Source Code Reference

*Populated when source code is present (v0.3.0+).*

- `Appointment.java` — entity definition; `price` and `paid` are the only money-bearing fields
- `AppointmentService.togglePaid()` — the single write path for the `paid` flag; validates role and appointment state before toggling
- Flyway migrations — the monthly grep target: zero new money-bearing columns in the `appointments` table outside this ADR's