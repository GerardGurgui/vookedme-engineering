# Repository Structure

**Repository:** `vookedme-engineering`  
**Document Version:** 1.0  
**Date:** 2026-07-09  
**Status:** Canonical

---

## Directory Tree

```
vookedme-engineering/
│
├── .github/                          # GitHub platform configuration
│   ├── workflows/
│   │   └── ci.yml                    # CI: Maven compile + test + Gitleaks
│   ├── ISSUE_TEMPLATE/
│   │   ├── bug_report.yml
│   │   └── feature_request.yml
│   ├── PULL_REQUEST_TEMPLATE.md
│   └── CODEOWNERS
│
├── docs/                             # All documentation
│   ├── meta/                         # Repository meta-documentation
│   │   ├── README.md
│   │   ├── engineering-foundation.md # Vision, philosophy, identity
│   │   ├── repository-structure.md   # ← this document
│   │   ├── repository-standards.md   # Style and naming conventions
│   │   ├── release-strategy.md       # Version lifecycle
│   │   └── github-configuration.md   # GitHub settings specification
│   ├── adr/                          # Architecture Decision Records
│   │   ├── README.md                 # ADR index + writing guide
│   │   ├── ADR-001-*.md
│   │   └── ...
│   ├── architecture/                 # System architecture documents
│   │   ├── README.md
│   │   ├── ARCHITECTURE.md           # System overview (primary)
│   │   ├── DATA_MODEL.md             # Entity model
│   │   └── SECURITY.md               # Security architecture
│   ├── governance/                   # Domain governance rules
│   │   ├── README.md
│   │   ├── state-machines.md         # Appointment + BlockedSlot FSMs
│   │   ├── permissions.md            # RBAC matrix
│   │   ├── destructive-actions.md    # "No destruir" principle
│   │   ├── audit-requirements.md     # Three-layer audit strategy
│   │   └── cancellation-request-workflow.md
│   ├── engineering/                  # Engineering deep-dives and writeups
│   │   ├── README.md
│   │   ├── CUSTOMER_LEGITIMATION.md  # GDPR consent gate architecture
│   │   └── ...
│   └── case-studies/                 # Cross-cutting case studies
│       └── README.md
│
├── src/                              # Java source code
│   └── main/
│       └── java/
│           └── com/vookedme/botmanager/
│               ├── analytics/
│               ├── appointment/
│               ├── auth/
│               ├── bot/
│               ├── business/
│               ├── common/
│               ├── config/
│               ├── customer/
│               ├── employee/
│               ├── notification/
│               ├── offering/
│               ├── schedule/
│               ├── security/
│               ├── user/
│               └── webhook/
│   └── test/
│       └── java/                     # Unit + integration tests (Testcontainers)
│
├── assets/                           # Visual and media assets
│   ├── cover/
│   │   └── social-preview-1280x640.png
│   ├── diagrams/
│   │   ├── system-context-c4.svg
│   │   ├── tenant-isolation.svg
│   │   └── appointment-fsm.svg
│   └── screenshots/
│       ├── ci-green.png
│       └── test-suite-output.png
│
├── dev/                              # Local development utilities
│   └── seed_example.sql              # Synthetic seed data (no real tenant data)
│
├── .mvn/                             # Maven wrapper config
│   └── wrapper/
│       └── maven-wrapper.properties
│
├── README.md                         # Primary landing page (3-depth)
├── CONTRIBUTING.md                   # Contribution guidelines
├── SECURITY.md                       # Vulnerability disclosure
├── LICENSE                           # MIT
│
├── Dockerfile                        # Multi-stage production image
├── pom.xml                           # Maven project definition
├── mvnw / mvnw.cmd                   # Maven wrapper scripts
├── .env.example                      # Environment variable template
├── .gitignore
├── .gitleaks.toml                    # Secret scanner configuration
└── .githooks/
    └── pre-commit                    # Client-side secret scanner
```

---

## Folder Responsibilities

### `.github/`

Platform configuration for GitHub. Everything in this folder affects GitHub's behaviour — CI workflows, issue routing, PR review assignment, and security policy. No application code lives here.

**Key decisions:**
- CI is a single `ci.yml` that runs Maven tests and the Gitleaks scan in parallel jobs
- Issue templates use `.yml` format (structured fields) rather than freeform `.md` templates
- CODEOWNERS designates the single-author pattern for a solo engineering repository

### `docs/`

The documentation hierarchy is the heart of the engineering product. It is designed so that any of the three audience depths (D0/D1/D2) can navigate to the content they need without reading the full tree.

The hierarchy has five layers:

```
docs/
├── meta/         → What this repository is and how it is maintained
├── adr/          → Why decisions were made
├── architecture/ → What the system is and how it works
├── governance/   → What rules the domain follows
└── engineering/  → How specific problems were solved
```

#### `docs/adr/`

Architecture Decision Records. Each ADR documents one significant decision: the context that made it necessary, the decision itself, the alternatives that were rejected and why, and the consequences of the choice.

ADRs are append-only. Superseded ADRs are marked superseded, not deleted. The full history of decision-making is preserved.

ADR numbering: sequential from ADR-001. No gaps. See `docs/adr/README.md` for the writing guide.

#### `docs/architecture/`

System-level architectural documents. These describe **what exists** at the level of architecture, not implementation. The intended reader is a senior engineer who has not read any source code yet.

- `ARCHITECTURE.md` — System context, component model, tenancy model, main request flows
- `DATA_MODEL.md` — Entity model, key relationships, schema decisions
- `SECURITY.md` — JWT architecture, HMAC webhook validation, rate limiting, secret management

#### `docs/governance/`

Domain governance documents. These describe **the rules** the domain operates under — what transitions are legal, who can do what, what constitutes a destructive action, what audit trails are required.

The governance documents are intended to be readable by a product manager as well as an engineer. They are the bridge between the domain model and the business rules.

#### `docs/engineering/`

Deep-dives on specific engineering problems. More specific than architecture documents, more transferable than source code comments. Format: problem statement → constraints → solution → consequences → what we would do differently.

#### `docs/case-studies/`

Cross-cutting case studies that tell the story of a design decision from inception to production. The intended audience is other engineers facing similar problems. Case studies may reference the ADRs and architecture documents as background.

### `src/`

The Java source code of the VookedMe multi-tenant appointment scheduling backend. The source is the sanitized-but-production-grade implementation — not simplified for readability, not rewritten for demonstration.

See `REPOSITORY_STRUCTURE.md §Package Architecture` below for the package breakdown.

### `assets/`

Version-controlled visual assets. All assets that appear in documentation must be in this directory — no hotlinked external images.

**Three sub-directories:**
- `cover/` — The GitHub social preview image
- `diagrams/` — Architecture diagrams as SVG (generated from Mermaid or vector tools)
- `screenshots/` — Annotated screenshots of CI, test output, etc.

### `dev/`

Local development utilities. Does not contain production code, production configuration, or real data.

**What lives here:** the `seed_example.sql` with synthetic demo data for local database population.

**What never lives here:** n8n workflow exports, production environment scripts, real tenant data.

### Root Level

Root-level files are either:
1. Build tooling (`pom.xml`, `mvnw`, `Dockerfile`, `.mvn/`)
2. Repository configuration (`.gitignore`, `.gitleaks.toml`, `.githooks/`)
3. GitHub-convention files (`README.md`, `LICENSE`, `CONTRIBUTING.md`, `SECURITY.md`)

Repository meta-documentation (`engineering-foundation.md`, `repository-structure.md`, `repository-standards.md`, `release-strategy.md`, `github-configuration.md`) lives in `docs/meta/`. The root contains only what GitHub tooling expects there and what a first-time visitor needs immediately.

---

## Package Architecture

The Java source is organised by business domain, not technical layer. There are no `controller/`, `service/`, `repository/` top-level packages. Every domain owns its full vertical slice.

```
com.vookedme.botmanager/
│
├── analytics/       # Revenue and appointment metrics (read-only reports)
├── appointment/     # Core domain: 6-state FSM, assignment engine, temporal boundary
├── auth/            # JWT issuance, refresh token rotation, reuse detection
├── bot/             # Bot audit trail, event resolution, narrative rendering, phone masking
├── business/        # Tenant (Business) entity and management
├── common/          # Shared: DTOs, exceptions, validation, utilities
├── config/          # Spring configuration: security chain, Resilience4j, Sentry, Jackson
├── customer/        # Customer lifecycle states, E.164 normalisation, GDPR fields
│   └── legitimation/ # Consent gate (OutboundLegitimacyGate) — documented separately
├── employee/        # Employee entity and schedule management
├── notification/    # Outbound notification orchestration (n8n / Resilience4j)
├── offering/        # Service offering catalogue
├── schedule/        # Business schedules, BlockedSlot state machine
├── security/        # JWT filter, rate limiting, UserDetailsService
├── user/            # Employee user management (OWNER/EMPLOYEE roles)
└── webhook/         # Inbound webhook: HMAC validation, turn correlation, idempotency
```

### Why Domain Packages?

A `service/AppointmentService.java` and a `service/BusinessService.java` in the same directory tells you nothing about their relationship. `appointment/AppointmentService.java` and `business/BusinessService.java` in sibling directories tells you they are peers in the domain hierarchy.

The package structure is documentation. A new engineer browsing the tree immediately understands the domain decomposition.

---

## Documentation Hierarchy

The documentation is structured for progressive disclosure:

```
README.md                     → Entry point: what is this?
│
├── docs/architecture/        → Depth 1: what does it look like?
│   └── ARCHITECTURE.md
│
├── docs/adr/                 → Depth 2: why does it look like this?
│   └── ADR-00n-*.md
│
├── docs/governance/          → Depth 2: what rules does it follow?
│   └── state-machines.md, permissions.md, ...
│
└── docs/engineering/         → Depth 3: how were specific problems solved?
    └── CUSTOMER_LEGITIMATION.md, ...
```

A reader who only reads the `README.md` leaves with an accurate overview. A reader who also reads `docs/architecture/ARCHITECTURE.md` understands the system at a technical level. A reader who also reads the ADRs understands why it was designed the way it was. A reader who reads the governance documents understands the domain rules that constrain the implementation.

Each layer adds depth without requiring the previous layer to be complete.

---

## Architecture Hierarchy

The architectural documentation is structured in three levels of abstraction:

**Level 1 — System Context**

`docs/architecture/ARCHITECTURE.md` — What are the boundaries of the system? What is inside vs. outside? What external systems does it interact with?

**Level 2 — Component Model**

Also in `docs/architecture/ARCHITECTURE.md` — What are the major components? How do they communicate? What are the key interfaces?

**Level 3 — Domain Model**

`docs/architecture/DATA_MODEL.md` — What are the entities? What are the relationships? What are the key invariants?

---

## Governance Hierarchy

Governance documents describe the **rules** that the implementation must follow. They are more stable than implementation decisions (which may change) and more specific than architectural principles (which are abstract).

The governance hierarchy:

```
docs/meta/engineering-foundation.md §3 → Engineering Philosophy (most abstract)
│
├── docs/governance/permissions.md     → Who can do what (role × resource × action)
├── docs/governance/state-machines.md  → What transitions are legal
├── docs/governance/destructive-actions.md → What requires extra care
└── docs/governance/audit-requirements.md  → What must be recorded
│
└── docs/adr/ADR-00n-*.md              → Why specific rules were chosen (most specific)
```

A governance document tells an engineer what the rule is. The corresponding ADR tells them why it exists. Neither is sufficient without the other.

---

## What Belongs Where: Quick Reference

| Content | Location |
|---|---|
| Why a decision was made | `docs/adr/` |
| What the system looks like | `docs/architecture/` |
| What rules the domain follows | `docs/governance/` |
| How a specific problem was solved | `docs/engineering/` |
| Case study (full story) | `docs/case-studies/` |
| Visual asset (diagram, screenshot) | `assets/` |
| Local dev utility | `dev/` |
| GitHub platform config | `.github/` |
| Build tooling | Root level |
| Repository meta-documentation | `docs/meta/` |

---

*This document reflects the intended structure of the repository at comp