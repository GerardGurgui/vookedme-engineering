# Case Studies

This directory contains cross-cutting case studies that tell the full story of a design decision — from the problem that motivated it, through the implementation, to the consequences in production.

Case studies are longer than ADRs and more narrative than engineering writeups. They are intended for engineers who want to understand not just what was decided, but how the decision process worked.

---

## Status

> **Pending population.** Case studies will be added in v2.0.0 per the [release strategy](../meta/release-strategy.md).

---

## Planned Case Studies

| Case Study | Topic |
|---|---|
| The Appointment Temporal Boundary Problem | How a production incident (P0: bot sending absurd messages about past appointments) led to ADR-011 and the PFT architecture |
| 78 Migrations Later: Schema Evolution Without Regrets | The Flyway migration strategy, how the domain model evolved, and what we would have done differently at V1 |
| GDPR-Compliant WhatsApp Messaging | The OutboundLegitimacyGate: consent-gated communication at the architecture level |
| Multi-Tenant Isolation: One Table, N Tenants | The AuthorizationService pattern and why shared-schema isolation is the right trade-off for this domain size |

---

## Format

Each case study follows this structure:

1. **The Problem** — What was happening, what was wrong, why it mattered
2. **Constraints** — What we could not change, what we were not willing to change
3. **The Solution** — What we did and why
4. **The Implementation** — Key code or schema changes, with explanation
5. **Consequences** — What became better, what became harder, what we accepted
6. **What We Would Do Differently** — Honest retrospective

Case studies reference the relevant ADRs and source code but are self-contained — a reader should not need to read the source code to follow the case study.
