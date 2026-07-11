# vookedme-engineering

> Multi-tenant appointment scheduling platform — built in production, documented from the first design decision.

[![CI](https://github.com/GerardGurgui/vookedme-engineering/actions/workflows/ci.yml/badge.svg)](https://github.com/GerardGurgui/vookedme-engineering/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.3-green)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue)](./LICENSE)

---

> **Status:** v1.3.0 — ADR Journey complete (17 ADRs). Source Code Journey SC-1 through SC-5 published: 41 production artefacts and 18 tests live. Security infrastructure — rate limiter, JWT filter, consent enforcement filter, consent service, consent audit entity, JVM timezone guard, and Sentry PII scrubber — now readable alongside the structural backbone, event system, bot domain, and privacy infrastructure layers. See the [Source Code Journey](./docs/source/README.md) for the full artefact list and reading path.

---

## The Product

VookedMe is an appointment scheduling platform for small service businesses — clinics, salons, barbershops, studios. Business owners configure their services, employees, and availability through an administration panel. Their customers book through WhatsApp: a message is enough, no app to download, no account to create.

The backend manages everything the conversation does not: appointment state, business rules, employee assignment, reminders, edge cases. What happens when a customer requests a cancellation and the owner does not respond for 24 hours. What happens when two customers attempt to book the same slot simultaneously. What happens when an employee's schedule changes mid-week and there are confirmed appointments to redistribute.

I built the backend as a solo engineer. This repository is the engineering record of that work.

---

## Why the Engineering Is Interesting

Building a booking system over WhatsApp surfaces design problems that do not appear in simpler architectures.

**Multi-tenancy enforced at the application layer.** A single deployment serves N independent businesses. Their data — customers, appointments, employees, offerings — must be completely isolated, not by separate databases, but by application logic enforced on every operation. One missing check and the wrong business sees the wrong data. The isolation must be consistent, verifiable, and structurally impossible to bypass by accident.

**The appointment is a state machine, not a record.** An appointment has six named states and a defined set of legal transitions. Confirming a past appointment is illegal. Approving a cancellation request after the appointment time has passed is illegal. Sending a reminder for an appointment that already happened is illegal. These are not validation rules scattered through service methods — they form a formal state machine with explicit transition guards and a temporal boundary that divides the lifecycle into two planes: operational (before the appointment) and closure (after).

**GDPR and consumer messaging.** Sending a message to a customer's personal phone number requires legal basis. The system models consent explicitly and enforces it architecturally: every outbound communication path passes through a consent gate that evaluates legitimacy before any message is dispatched. The constraint is structural, not a policy check that can be forgotten.

**A schema that evolved 78 times.** The domain started simple and grew complex as real usage exposed missing concepts — audit trails, blocked slots for employee leave, customer consent records, temporal boundary enforcement. The Flyway migration history is a complete record of every structural decision, including the ones that turned out to be wrong and required correction.

**The LLM interprets. The backend decides.** The WhatsApp interface uses a language model to understand what a customer means. But understanding is not authority. Every booking, cancellation, and rescheduling is validated by the backend against schedule constraints, FSM transition guards, and business rules before anything is committed. The model participates in the conversation. The backend owns the outcome.

---

## Why This Repository Is Public

The source code matters, but it is not the primary reason this repository exists.

Most repositories show *what* was built. This one documents *why*. Every significant design decision — from why the payment amount is stored as a single integer field to why the bot derives its state from appointments rather than maintaining its own event table — is recorded as an Architecture Decision Record: the context that made the decision necessary, the decision itself, the alternatives that were considered and rejected, the consequences that were accepted.

The ADR suite is the primary artefact. An engineer who reads the ADRs will understand more about this system than one who only reads the source code — because decisions documented at the time they are made reveal more than code reconstructed from memory.

---

## What Is Inside

```
docs/
├── architecture/  System context, data model, security architecture
├── adr/           Architecture Decision Records — the reasoning behind each design choice
├── governance/    Domain rules: state machines, permission matrix, audit requirements
├── engineering/   Deep-dives on specific engineering problems
└── case-studies/  Cross-cutting engineering narratives
src/               Java source — Spring Boot 4.0.3 / Java 21 (arriving with source code milestone)
assets/            Architecture diagrams, cover image, screenshots
dev/               Local development seed data
```

Documentation index: [docs/README.md](./docs/README.md) · Full structure: [docs/meta/repository-structure.md](./docs/meta/repository-structure.md)

---

## How to Read This Repository

**If you have 60 seconds —** you are here. The product is above. The engineering philosophy is in [engineering-foundation.md](./docs/meta/engineering-foundation.md).

**If you have 5 minutes —** read [docs/architecture/README.md](./docs/architecture/README.md) for the system overview. Then open [ADR-011](./docs/adr/ADR-011-appointment-temporal-boundary.md): it is the most complete example in this repository of how a production incident became a formal architectural principle. [ADR-001](./docs/adr/ADR-001-single-money-field.md) shows how a legal constraint shaped a domain model decision.

**If you have 30 minutes —** start with [docs/source/README.md](./docs/source/README.md) for the source publication roadmap and recommended reading path. Then follow the path: `AuthorizationService` (the tenant isolation gate) → `Appointment` entity → `AppointmentEvent` → `AppointmentAuditListener` → `TurnContext`. Read [docs/governance/README.md](./docs/governance/README.md) to see the domain governance model, and the integration tests to see the rules specified in executable form.

---

## Engineering Case Studies

Deep-dives into specific problems this system forced me to solve. Published continuously as the repository grows.

- **Temporal Boundary** — why appointment time divides the FSM into two operational planes · *Coming soon*
- **Tenant Isolation** — enforcing multi-tenancy at the application layer without row-level security · *Coming soon*
- **Reality vs Conversation** — when the AI asserts something the database contradicts · *Planned*
- **Prompt vs Architecture** — what belongs in the model and what belongs in code · *Planned*
- **GDPR Consent Enforcement** — consent as architecture, not validation · *Planned*
- **The Rescheduling Problem** — the edge case graph of a seemingly simple operation · *Planned*
- **78 Migrations** — the evolution of the domain model and what each change reveals · *Planned*

---

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21 (Temurin LTS) |
| Framework | Spring Boot 4.0.3 |
| Database | PostgreSQL 16+ |
| Migrations | Flyway — 78 migrations |
| Testing | JUnit 5 + Testcontainers |
| Security | Spring Security · JWT · HMAC-SHA256 |
| Resilience | Resilience4j (circuit breaker on                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 