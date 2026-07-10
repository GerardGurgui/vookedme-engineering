# Engineering Product Foundation

**Repository:** `vookedme-engineering`  
**Document Version:** 1.0  
**Date:** 2026-07-09  
**Status:** Canonical — do not modify without deliberate review

---

## §1. Vision

This repository is the long-form public statement of how I think about software.

Not a portfolio in the traditional sense — a collection of projects polished for visual effect. Not a "showcase" in the recruiter-facing sense — a set of toy examples designed to check boxes. This is a living engineering artefact: a production-grade multi-tenant SaaS backend that was designed, built, iterated, and documented as if it would outlive its author's involvement.

The five-year vision: this repository becomes a reference for how to design a real product — one that handles concurrency, multi-tenancy, domain complexity, GDPR compliance, and long-term schema evolution without accumulating irreversible debt. Engineers stumbling onto it should find something they can learn from, argue with, and build on.

The repository is named for VookedMe, the commercial product it derives from. But the engineering decisions documented here transcend the product. The appointment state machine, the multi-tenant authorization pattern, the GDPR communication consent gate — these are solutions to problems that appear in every booking system, every multi-tenant platform, every consumer-facing product. The value is not in the product domain. The value is in the decisions.

---

## §2. Purpose

Four purposes, in decreasing order of priority:

**2.1 Engineering Demonstration**

The primary purpose is to demonstrate engineering capability at the level of Principal/Staff engineer: domain modelling, state machine design, architectural trade-off analysis, testing strategy, security thinking, and long-term maintainability. Every design decision should be legible to a senior engineer who has never seen the codebase before.

**2.2 Architecture Documentation**

The ADR suite, governance documents, and architecture writeups are first-class citizens — not supplementary artifacts. An engineer should be able to understand *why* every significant decision was made, not just *what* was decided. This is rare. It is the repository's differentiating feature.

**2.3 Educational Resource**

Any engineer facing a multi-tenant SaaS problem, a complex appointment state machine, GDPR-compliant communication design, or a JWT security model should find something actionable here. The code is readable; the documentation makes it comprehensible; the case studies make it transferable.

**2.4 Professional Identity**

This repository is the most honest possible answer to "show me how you think." It is meant to be shared with recruiters, CTOs, and engineering peers as a direct window into engineering judgement, not as a curated highlight reel.

---

## §3. Engineering Philosophy

These principles govern every decision in the repository — what is included, how it is documented, what is refactored, and what is intentionally left imperfect.

### P1 — Derive over Materialise

Derived state is always preferred over materialised state when the source of truth already exists. A computed predicate that is always correct beats a database column that can be stale. Applied throughout: `Appointment.isPast()` is a pure function on existing fields, not a state column; `BotEventResolver` is a pure function of appointment state, not a separate event table.

### P2 — Explicit over Implicit

Security is explicit. Multi-tenancy is enforced by `AuthorizationService`, not assumed by convention. Every permission check names the principal, the resource, and the operation. No "you're the only tenant, so we don't need to check" in a codebase that is designed to scale to N tenants from day one.

### P3 — State Machines over Conditionals

Domain transitions that have business meaning are modelled as finite state machines with named states, named transitions, and explicit guards. `AppointmentStatus`, `BlockedSlotStatus`, and `CustomerLifecycleStatus` are not booleans or free-form strings. The state machine is the documentation.

### P4 — Tests as First-Class Documentation

The test suite is not a safety net. It is a specification. A new engineer reading `OptimisticLockingIntegrationTest` understands what happens when two users race to book the same slot. A new engineer reading `BotConsentEnforcementIT` understands the consent gate contract. Tests that could be "just an integration test" are written to be comprehensible to a reader who has not seen the source code.

### P5 — The Schema Is the Contract

Flyway migrations are append-only archaeology. V1 through V78 tell the story of how the domain evolved. No migration is deleted. No migration is rewritten. Migrations that are superseded are documented as superseded, not erased. The schema history is as important as the current schema.

### P6 — Privacy by Design, Not Privacy by Compliance

GDPR is not a checkbox. E.164 phone normalisation exists so that the right-to-erasure delete operation is unambiguous. The `OutboundLegitimacyGate` exists because sending unsolicited WhatsApp messages is a harm to the user, not just a legal risk to the business. Data minimisation in bot notes exists because the system collects only what it needs, not everything it can.

### P7 — Document Decisions, Not Just Outcomes

An ADR that explains why a decision was made is worth ten times more than source code that embeds the outcome. When a decision will confuse a future engineer, write the ADR before writing the code. The repository contains ADRs that were written *after* the code because they deserved to exist — the code alone was not sufficient documentation.

### P8 — Own the Long Term

Shortcuts that create irreversible technical debt are explicitly rejected. The `payment_amount` integer field is not an accident — it is ADR-001. The temporal boundary enforcement is not an over-engineering choice — it is ADR-011, motivated by a production incident. Decisions that look conservative at day 1 look principled at year 2.

---

## §4. Long-Term Goals

### 3-Year Goals

- The repository serves as the primary engineering credential for employment, freelance, and consulting engagements
- The ADR suite is cited by at least one external engineer as a model for how ADRs should be written
- The test suite passes on the first clone without manual configuration steps
- The Flyway migration history is used in at least one internal engineering workshop as a case study

### 5-Year Goals

- The repository evolves into a reference implementation for multi-tenant SaaS in the Java/Spring ecosystem — the kind that gets linked in conference talks and Stack Overflow threads
- The engineering philosophy stated in §3 is recognisable in the architecture of subsequent projects in the ecosystem (Mission Control, future open-source contributions)
- At least one ADR has been updated to reflect a decision revision — demonstrating that the documentation is maintained, not just written once

### Anti-Goals (What We Are Not Building For)

- GitHub star count — stars are a lagging indicator of promotion, not quality
- npm-style downloads — this is not a library
- Interview prep — the code is not simplified for legibility at the cost of accuracy

---

## §5. Repository Identity

### Name

`vookedme-engineering`

The `-engineering` suffix signals intent: this is not the product itself. It is the engineering artefact derived from the product. This is a deliberate choice — it separates the commercial identity from the engineering identity and acknowledges that the repository is a curated view of the work, not the work itself.

### Tagline

> **Production-grade multi-tenant SaaS appointment platform — designed and engineered from first principles.**

### One-Sentence Description

A multi-tenant appointment scheduling backend built with Spring Boot 4.0.3 and Java 21, featuring a WhatsApp bot integration, GDPR-compliant communication design, a six-state appointment FSM, 78 Flyway migrations, and a Testcontainers integration suite.

### Keywords

`multi-tenant`, `saas`, `appointment-scheduling`, `domain-driven-design`, `spring-boot`, `java-21`, `flyway`, `testcontainers`, `whatsapp-bot`, `gdpr`, `state-machine`, `jwt-security`, `postgresql`, `clean-architecture`

### What Makes This Different

Most portfolio repositories demonstrate *what* was built. This repository demonstrates *how* and *why*. The ADR suite is the differentiator. Anyone can write a Spring Boot application; very few can articulate every significant decision as a structured argument with context, alternatives considered, and consequences owned.

---

## §6. Target Audience

The repository serves three audience depths simultaneously, without compromising any of them:

### Depth 0 — Recruiter (60 seconds)

**Question:** Is this real? Is this person serious?

**What they see:** The README headline, the CI badge, the tech stack summary, the migration count (78), and the test suite mention. The visual impression of a professional repository with a populated documentation structure.

**What they need:** A clear answer to "what is this?" within 10 seconds. Confidence that the author is not a tutorial-follower within 60 seconds.

**Repository response:** The README headline names the tech stack, the migration count, the test count, and the domain in the first paragraph. The CI badge is green. The repository has a description, topics, and a cover image.

### Depth 1 — CTO / Technical Lead (5 minutes)

**Question:** Is this person's judgement trustworthy at scale?

**What they see:** The ADR suite (especially ADR-001 and ADR-011), the architecture document, the state machine governance document, the permission matrix.

**What they need:** Evidence of architectural thinking beyond implementation. Evidence that the author understands trade-offs, not just solutions.

**Repository response:** ADR-001 articulates a payment boundary decision that prevented scope creep into fiscal compliance territory. ADR-011 formalises a temporal boundary principle that emerged from a production incident and was applied retroactively across the entire domain. These are the decisions that CTOs make and regret not documenting.

### Depth 2 — Senior Engineer (30 minutes)

**Question:** Can this person write production-grade code? Would I want them on my team?

**What they see:** `AuthorizationService.java` (the tenant isolation gate), `AppointmentService.java` (the FSM + assignment engine + temporal boundary), the Testcontainers integration suite, the `BlockedSlotService.java` state machine, the `RefreshTokenService.java` rotation logic.

**What they need:** Code they can read, critique, and learn from. Tests they can run. Migrations they can understand.

**Repository response:** The source code is the real production implementation. The tests run. The comments explain the why, not the what. The code does not condescend.

---

## §7. First Commit Philosophy

**The first commit should make a promise, not a claim.**

It should promise: this repository will be worth reading. It should not claim: this repository is complete.

### What Commit #1 Contains

```
chore: engineering product foundation — Phase -1 complete
```

Files present in Commit #1:

```
vookedme-engineering/
├── .github/
│   ├── workflows/ci.yml          # workflow scaffold, not yet running
│   ├── ISSUE_TEMPLATE/
│   │   ├── bug_report.yml
│   │   └── feature_request.yml
│   ├── PULL_REQUEST_TEMPLATE.md
│   └── CODEOWNERS
├── docs/
│   ├── adr/README.md             # ADR index — populated in Phase 1
│   ├── architecture/README.md    # architecture docs — populated in Phase 1
│   ├── governance/README.md      # governance docs — populated in Phase 1
│   ├── engineering/README.md     # engineering writeups — populated in Phase 2
│   └── case-studies/README.md   # case studies — populated in Phase 2
├── assets/
│   ├── diagrams/README.md
│   ├── screenshots/README.md
│   └── cover/README.md
├── dev/README.md
├── src/README.md                 # source code — populated in Roadmap Phase 2
├── ENGINEERING_PRODUCT_FOUNDATION.md  ← this document
├── REPOSITORY_STRUCTURE.md
├── REPOSITORY_STANDARDS.md
├── GITHUB_CONFIGURATION.md
├── RELEASE_STRATEGY.md
├── README.md                     # first-commit version — complete structure, pending content
├── LICENSE                       # MIT
├── CONTRIBUTING.md
├── SECURITY.md
└── .gitignore
```

### What Commit #1 Does NOT Contain

- Source code (populated in Roadmap Phase 2)
- ADR files (populated in Roadmap Phase 3)
- Architecture documents (populated in Roadmap Phase 3)
- Passing CI (CI workflow exists but is scaffolded — green on first pass after source is present)
- Any content from the private repository

### The Right Tone for Commit #1

The README at Commit #1 does not hide that the repository is in its early state. It says so plainly. The foundation documents show the quality of thinking that has gone into the design. The structure shows where everything will live. An engineer reading Commit #1 can already form an accurate opinion of how this repository will look when it is complete.

---

## §8. Visual Identity

*Note: This section defines the visual design. Assets are not yet created. They will be produced in Roadmap Phase 3.*

### Design Language

The visual identity follows the same constraint as the engineering philosophy: honest, precise, minimal. No decorative noise. No dark backgrounds with glowing neon. No "impressive-looking" diagrams that sacrifice accuracy for aesthetics.

**Primary palette:**
- Background: `#0d1117` (GitHub dark) or `#ffffff` (GitHub light) — no custom background
- Accent: `#2563eb` (blue-600) — used sparingly, for callouts and highlights only
- Text: System font stack — no custom typography in diagrams

**Tone:** Technical precision over marketing warmth. The images look like they were made by an engineer, not a designer — deliberately.

### Cover Image

The repository cover image (social preview) shows:
- The repository name in clean monospace type
- One architectural callout: "78 Flyway migrations · 6-state FSM · multi-tenant isolation · GDPR-compliant"
- The tech stack as small badges: `Java 21` · `Spring Boot 4.0.3` · `PostgreSQL` · `WhatsApp`
- A subtle architectural diagram (the tenant isolation model or the FSM)
- **Size:** 1280×640px (GitHub recommended)

### Architecture Diagrams

All architecture diagrams use **Mermaid** rendered inline in Markdown. No external diagram services (Lucidchart, Figma, etc.). No PNG exports of tools that cannot be version-controlled.

Exception: system-level C4 context diagrams may be produced as SVG and committed to `assets/diagrams/`.

**Diagram style:**
- Mermaid `flowchart LR` for component flows
- Mermaid `sequenceDiagram` for request flows
- Mermaid `stateDiagram-v2` for FSMs
- Consistent node naming: `ServiceName`, `EntityName`, `[ExternalSystem]`

### Screenshots

All screenshots:
- Dark terminal theme (consistent)
- No personal data visible (anonymised test output)
- Annotated with callouts (red arrow or box) when pointing to something specific
- Stored in `assets/screenshots/` with descriptive names: `ci-green-2026.png`, `appointment-fsm-test-output.png`

### GIF Strategy

Animated GIFs are used sparingly — only when a static image cannot convey the point. Acceptable uses:
- Test suite running in CI
- The bot conversation flow (if the WhatsApp UI can be shown without exposing real data)

Never: animated intros, loading spinners, decorative motion.

### Asset Organisation

```
assets/
├── cover/
│   └── social-preview-1280x640.png
├── diagrams/
│   ├── system-context-c4.svg
│   ├── tenant-isolation.svg
│   └── appointment-fsm.svg
└── screenshots/
    ├── ci-green.png
    └── test-suite-output.png
```

---

## §9. Success Criteria

Success is not measured by vanity metrics. It is measured by the quality of interactions the repository generates.

### Tier 1 — Engineering Credibility

- A senior engineer who clones the repository, runs `mvn test`, and reads `AuthorizationService.java` concludes: "This person understands multi-tenancy."
- A technical reviewer reading ADR-011 says: "I've seen this problem. I didn't know to call it the temporal boundary problem until now."
- The test suite runs clean on first clone with only `Docker` and `Java 21` as prerequisites.

### Tier 2 — CTO-Level Confidence

- A CTO reviewing the ADR suite concludes: "This engineer documents trade-offs, not just outcomes."
- The architecture document can be understood by a non-Java engineer with 5 years of backend experience.
- The permission matrix can be understood by a product manager.

### Tier 3 — Recruiter Clarity

- A non-technical recruiter can copy-paste the README first paragraph into a LinkedIn message and have it make sense to a senior engineer.
- The CI badge is green.
- The repository topics accurately describe what's inside.

### Tier 4 — Long-Term Maintenance

- Documentation is updated when code changes (not as an afterthought).
- ADRs are added for new significant decisions (at least 1 per year of active development).
- The CHANGELOG is kept current.

### Anti-Metrics

These are explicitly *not* success criteria:
- GitHub stars
- Forks
- Google ranking
- Number of contributors
- Lines of code

---

## §10. Future Ecosystem

This repository is the first of a planned engineering ecosystem. Each future repository will follow the same standards defined in `REPOSITORY_STANDARDS.md` and will cross-reference this one as the canonical style reference.

### Planned Repositories

**`mission-control-engineering`** *(future)*  
The administrative panel for VookedMe — React/Next.js frontend with role-based UI, multi-tenant context, and the state machine visualisation. The frontend complement to this repository.

**`ai-experiments`** *(future)*  
Open-ended experiments in applied AI/ML — RAG architectures, agent design, prompt engineering patterns. Lower rigour than the engineering repositories; clearly labelled as experimental.

**`engineering-case-studies`** *(future)*  
Technical deep-dives on problems encountered across projects: "Why we rewrote the appointment state machine," "The GDPR communication consent gate," "How we caught a concurrency bug with a race condition test." Stand-alone articles with code examples.

**`open-source-contributions`** *(future)*  
Patches, issues, and contributions to upstream projects. Documents the author's participation in the broader ecosystem.

### Cross-Repository Conventions

All repositories in the ecosystem share:
- The same `REPOSITORY_STANDARDS.md` (or inherit from it)
- The same Mermaid diagram style
- The same commit convention (Conventional Commits)
- The same ADR format (when applicable)
- The same three-audience README structure (D0/D1/D2)

The `vookedme-engineering` repository is the **reference implementation** of these standards. When conventions evolve, they evolve here first and propagate to downstream repositories.

### The Ecosystem Narrative

Each repository tells a piece of the same story: *how to build software that matters*. `vookedme-engineering` tells the backend story. `mission-control-engineering` will tell the frontend story. `engineering-case-studies` will tell the problem-solving story. Together, they form a coherent picture of an engineer who thinks end-to-end.

---

## Appendix A — Future Opportunities

*Improvements identified during Phase -1 planning that would strengthen the engineering product but are explicitly out of scope for the current phase.*

**A.1 — OpenAPI Specification as a First-Class Artifact**

The API surface (11 controllers, 30+ endpoints) is not yet documented interactively. Adding SpringDoc/OpenAPI 3 would allow visitors to explore the API contract in-browser without running the application. This is a high-impact D2 enhancement.

**A.2 — Docker Compose for Zero-Friction Local Setup**

A `docker-compose.yml` providing PostgreSQL + the application would reduce the local setup requirement from "Java 21 + PostgreSQL configured + env vars set" to "Docker installed." This is the single highest-impact D2 change after source code publication.

**A.3 — Architecture Decision Record for This Repository**

This foundation document is informal. A formal ADR-000 would articulate the decision to create a public engineering repository (why, alternatives considered, consequences) in the same format as the domain ADRs. This would demonstrate that the ADR practice is not domain-specific — it applies to any significant decision.

**A.4 — CHANGELOG**

A `CHANGELOG.md` following [Keep a Changelog](https://keepachangelog.com/) conventions would make the repository's evolution legible to external visitors who find it mid-journey. The first entry would be v0.1.0 (this phase).

**A.5 — The Customer Legitimation Module**

The `customer/legitimation/` package (OutboundLegitimacyGate, LegitimacyDecision) is currently excluded from the public repository scope (DOCUMENT ONLY). After a deliberate review of what can be shared without exposing GDPR enforcement strategy, this module is the strongest candidate for Phase 2 publication. Its architecture is the most original piece of engineering in the codebase.

**A.6 — Mermaid System Context Diagram in README**

A C4-level Mermaid diagram embedded directly in the README would give D1 readers an immediate architectural orientation without navigating to `docs/architecture/`. This requires only a few hours of diagramming work and has high impact.

---

*This document is the canonical foundation statement for the `vookedme-engineering` repository. Modify only with deliberate intent. Version changes require a dated changelog entry at the top of this document.*
