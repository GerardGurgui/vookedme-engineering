# vookedme-engineering

> **Production-grade multi-tenant SaaS appointment platform — designed and engineered from first principles.**

[![CI](https://github.com/GerardGurgui/vookedme-engineering/actions/workflows/ci.yml/badge.svg)](https://github.com/GerardGurgui/vookedme-engineering/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.3-green)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue)](./LICENSE)

---

> **Repository Status:** v0.1.0 — Foundation phase. Source code and documentation are being extracted from the production repository. Structure and engineering foundations are established. [See RELEASE_STRATEGY.md](./RELEASE_STRATEGY.md) for what is coming and when.

---

## What This Is

A multi-tenant appointment scheduling backend for service businesses (clinics, salons, studios), with a WhatsApp bot as the primary customer-facing booking interface. Built in production, documented to engineering portfolio standards.

**Stack:** Spring Boot 4.0.3 · Java 21 · PostgreSQL · Flyway (78 migrations) · Testcontainers · Resilience4j · JWT + HMAC-SHA256 · n8n · Evolution API (WhatsApp)

**What makes this repository interesting is not the stack — it is the decisions:**

- A six-state appointment FSM (PENDING / CONFIRMED / COMPLETED / CANCELLED / CANCELLATION\_REQUESTED / NO\_SHOW) with explicit transition guards and temporal boundary enforcement
- Multi-tenant isolation enforced at the `AuthorizationService` layer — never assumed, always verified, never leaked
- 78 Flyway migrations documenting every schema decision from day one to production
- GDPR-compliant WhatsApp communication: an `OutboundLegitimacyGate` that checks customer consent before any outbound message
- A refresh token rotation system with reuse detection
- A `BlockedSlot` state machine (REQUESTED → APPROVED workflow) for employee leave management
- An assignment engine that distributes appointments across employees by schedule and offering availability

---

## Repository Structure

```
vookedme-engineering/
├── docs/
│   ├── adr/           # Architecture Decision Records — why every significant decision was made
│   ├── architecture/  # System context, data model, security architecture
│   ├── governance/    # FSMs, RBAC matrix, audit requirements
│   └── engineering/   # Deep-dives on specific problems
├── src/               # Java source (populated in v0.3.0)
├── assets/            # Diagrams, screenshots, cover image
└── dev/               # Local development utilities
```

Full structure: [REPOSITORY_STRUCTURE.md](./REPOSITORY_STRUCTURE.md)

---

## Documentation

The documentation is the primary value of this repository, and it is structured for three audience depths:

**60 seconds (Recruiter):** This README — what is it, what's the stack, what's the test count.

**5 minutes (Technical Lead):** Start with [docs/architecture/ARCHITECTURE.md](./docs/architecture/ARCHITECTURE.md), then read [ADR-001](./docs/adr/ADR-001-single-money-field.md) and [ADR-011](./docs/adr/ADR-011-appointment-temporal-boundary.md) for a direct view into architectural thinking.

**30 minutes (Engineer):** Clone the repository, run `mvn test`, read `AuthorizationService.java` (the tenant isolation pattern), `AppointmentService.java` (the FSM + assignment engine), and `RefreshTokenService.java` (rotation + reuse detection). Then read [docs/governance/state-machines.md](./docs/governance/state-machines.md).

---

## Engineering Highlights

*(Populated in v0.5.0 — full content after source code is present)*

- **Tenant Isolation Pattern** — `AuthorizationService` as the single, mandatory security gate for every resource access
- **Appointment Temporal Boundary (PFT)** — ADR-011: `appointment.datetime` divides the appointment lifecycle into operational and closure planes
- **Derive-from-Appointments Architecture** — ADR-007: `BotEventResolver` as a pure function of appointment state, eliminating a separate event table
- **GDPR Communication Consent Gate** — `OutboundLegitimacyGate` as a pre-condition to every WhatsApp outbound message
- **78 Flyway Migrations** — the complete schema evolution story from day one

---

## Local Setup

*(Populated in v0.3.0 — after source code is extracted)*

**Prerequisites:** Java 21, Maven 3.9+, Docker (for Testcontainers)

```bash
git clone https://github.com/GerardGurgui/vookedme-engineering.git
cd vookedme-engineering
cp .env.example .env
# Fill in .env values
mvn test
```

---

## Tech Stack

| Component | Technology | Version |
|---|---|---|
| Runtime | Java | 21 (Temurin LTS) |
| Framework | Spring Boot | 4.0.3 |
| Database | PostgreSQL | 16+ |
| Migrations | Flyway | latest |
| Testing | JUnit 5 + Testcontainers | — |
| Security | Spring Security + JWT | — |
| Resilience | Resilience4j | — |
| Observability | Sentry | — |
| WhatsApp | Evolution API | self-hosted |
| Orchestration | n8n | self-hosted |
| Container | Docker (multi-stage) | — |
| CI | GitHub Actions | — |

---

## Repository Philosophy

This repository is a living engineering artefact — not a curated highlight reel, not a tutorial, and not a toy example. The source code is the real production implementation. The ADRs document real decisions made under real constraints. The test suite specifies real domain contracts.

The engineering philosophy governing every decision in this repository is documented in [ENGINEERING_PRODUCT_FOUNDATION.md](./ENGINEERING_PRODUCT_FOUNDATION.md).

---

## License

MIT — see [LICENSE](./LICENSE).

---

*Part of the VookedMe Engineering ecosystem. Private production repository remains separate and undisclosed.*
