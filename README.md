# vookedme-engineering

> Multi-tenant appointment scheduling platform — built in production, documented from the first design decision.

[![CI](https://github.com/GerardGurgui/vookedme-engineering/actions/workflows/ci.yml/badge.svg)](https://github.com/GerardGurgui/vookedme-engineering/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.3-green)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue)](./LICENSE)

---

> **Status:** v0.1.0 — Foundation phase. Source code and documentation are being extracted from the production repository. Structure and engineering foundations are in place. See [release strategy](./docs/meta/release-strategy.md) for the full timeline.

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

The ADR suite is the primary artifact. An engineer who reads the ADRs will understand more about this system than one who only reads the source code — because decisions documented at the time they are made reveal more than code reconstructed from memory.

---

## What Is Inside

```
docs/
├── adr/           Architecture Decision Records — the reasoning behind each design choice
├── architecture/  System context, data model, security architecture
├── governance/    Domain rules: state machines, permission matrix, audit requirements
└── engineering/   Deep-dives on specific engineering problems
src/               Java source — Spring Boot 4.0.3 / Java 21 (arriving in v0.3.0)
assets/            Architecture diagrams, cover image, screenshots
dev/               Local development seed data
```

Full structure and folder responsibilities: [docs/meta/repository-structure.md](./docs/meta/repository-structure.md)

---

## How to Read This Repository

**If you have 60 seconds —** you are here. The product is above. The engineering philosophy is in [engineering-foundation.md](./docs/meta/engineering-foundation.md).

**If you have 5 minutes —** read [docs/architecture/ARCHITECTURE.md](./docs/architecture/ARCHITECTURE.md) for the system overview. Then open [ADR-011](./docs/adr/ADR-011-appointment-temporal-boundary.md): it is the most complete example in this repository of how a production incident became a formal architectural principle. [ADR-001](./docs/adr/ADR-001-single-money-field.md) shows how a legal constraint shaped a domain model decision.

**If you have 30 minutes —** clone the repository. Run `mvn test`. Read `AuthorizationService.java` — the tenant isolation gate — then `AppointmentService.java` — the state machine, assignment engine, and temporal boundary enforcement in a single class. Read [docs/governance/state-machines.md](./docs/governance/state-machines.md) to see the rules the code enforces, and the integration tests to see them specified.

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
| Resilience | Resilience4j (circuit breaker on outbound calls) |
| Observability | Sentry (PII-scrubbed) |
| Bot channel | Evolution API (WhatsApp, self-hosted) |
| Workflow orchestration | n8n (self-hosted) |
| Containerisation | Docker (multi-stage build) |
| CI | GitHub Actions |

---

## Running Locally

*Full setup instructions arrive with the source code in v0.3.0. Requirements: Java 21, Maven 3.9+, Docker.*

```bash
git clone https://github.com/GerardGurgui/vookedme-engineering.git
cd vookedme-engineering
cp .env.example .env
mvn test
```

---

MIT — see [LICENSE](./LICENSE).

*Private production repository remains separate. This repository contains the engineering documentation and a sanitized version of the source.*
