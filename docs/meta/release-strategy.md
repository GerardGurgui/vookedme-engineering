# Release Strategy

**Repository:** `vookedme-engineering`  
**Document Version:** 1.0  
**Date:** 2026-07-09  
**Status:** Canonical

---

## Philosophy

Releases in an engineering portfolio repository serve a different purpose than releases in a library or product.

In a library: releases signal compatibility guarantees to downstream consumers.  
In this repository: releases signal **maturity milestones** to readers. Each release represents a meaningful increase in the repository's value as an engineering artefact.

**Three release principles:**

1. **Never release a lie.** Every release is honest about what exists and what doesn't. A v0.1.0 that only contains foundation documents says so plainly. It does not imply that the source code is coming tomorrow.

2. **Each release stands on its own.** A reader who finds the repository at any release should understand what it is, navigate to the content that exists, and find that content complete and well-formed. Broken links, empty placeholders without context, and half-finished sections are release blockers.

3. **Versions communicate intent.** The version numbering communicates the nature of the release: 0.x.x means "engineering artefact in progress," 1.0.0 means "complete public engineering showcase," 2.x.x means "community-ready."

---

## Version Lifecycle

### v0.1.0 — Foundation

**Milestone:** Phase -1 complete  
**Tag:** `v0.1.0`  
**Entry condition:** This release

**What exists at v0.1.0:**
- Git repository initialized
- Complete directory structure with placeholder READMEs in every folder
- All five foundation documents:
  - `ENGINEERING_PRODUCT_FOUNDATION.md`
  - `REPOSITORY_STRUCTURE.md`
  - `REPOSITORY_STANDARDS.md`
  - `GITHUB_CONFIGURATION.md`
  - `RELEASE_STRATEGY.md`
- First-commit `README.md` (structure defined, content pending)
- `LICENSE` (MIT)
- `CONTRIBUTING.md` (skeleton)
- `SECURITY.md` (skeleton)
- `.gitignore` (complete)
- `.gitleaks.toml` (complete)
- `.githooks/pre-commit` (complete)
- `.github/workflows/ci.yml` (scaffold — CI will not yet pass without source code)
- `.github/ISSUE_TEMPLATE/` (both templates)
- `.github/PULL_REQUEST_TEMPLATE.md`
- `.github/CODEOWNERS`

**What does NOT exist at v0.1.0:**
- Source code
- ADRs
- Architecture documents
- Governance documents
- Passing CI

**What v0.1.0 demonstrates to a reader:**
- Intentional structure from the very first commit
- Engineering philosophy articulated before a single line of application code is written
- Repository conventions defined and enforced from day one

---

### v0.2.0 — Documentation Layer

**Milestone:** Roadmap Phase 3 (ADRs + Governance + Architecture docs) complete  
**Tag:** `v0.2.0`  
**Entry condition:** All documentation content present; no passing CI required yet

**What is added in v0.2.0:**
- `docs/adr/` — full ADR suite (ADR-001 through ADR-013, with rewrites and sanitization per Content Matrix)
- `docs/architecture/ARCHITECTURE.md` — public-facing architecture document
- `docs/architecture/DATA_MODEL.md` — entity model documentation
- `docs/architecture/SECURITY.md` — security architecture documentation
- `docs/governance/state-machines.md` — Appointment + BlockedSlot FSMs
- `docs/governance/permissions.md` — RBAC permission matrix
- `docs/governance/destructive-actions.md`
- `docs/governance/audit-requirements.md`
- `docs/governance/cancellation-request-workflow.md`
- `docs/engineering/CUSTOMER_LEGITIMATION.md` — consent gate architecture
- `assets/diagrams/` — core architecture diagrams (SVG)

**What does NOT exist at v0.2.0:**
- Source code (still)
- Passing CI (still — no source to test)

**What v0.2.0 demonstrates to a reader:**
- Architectural thinking documented before (or without requiring) the source code
- The ADR suite — the differentiating feature of the repository
- The governance model — state machines, permissions, audit strategy
- D1 audience (CTO) is fully served at this release

---

### v0.3.0 — Source Code

**Milestone:** Roadmap Phase 2 (Java source code) complete  
**Tag:** `v0.3.0`  
**Entry condition:** Source code present; CI compile passes; test execution may have failures

**What is added in v0.3.0:**
- All Java source packages (16 packages, 377+ files per Content Matrix)
- `src/main/resources/` — application.yml, Flyway migrations V1–V78
- `.env.example` — complete environment variable template
- `Dockerfile` — production multi-stage build
- `pom.xml` — full dependency set
- Maven wrapper scripts

**What does NOT exist at v0.3.0:**
- Test suite (following release)
- Fully passing CI (test job not yet green)

**What v0.3.0 demonstrates to a reader:**
- The real production-grade source code is present
- 78 Flyway migrations — the schema evolution story is complete
- The architecture documentation now has corresponding source code to point to

---

### v0.4.0 — Test Suite

**Milestone:** Roadmap Phase 2 test copy complete  
**Tag:** `v0.4.0`  
**Entry condition:** All test files present; CI test job must be green

**What is added in v0.4.0:**
- Complete test suite (~130 test files)
- Unit tests for all 16 packages
- Integration tests (Testcontainers): BotBookingModeIT, AuthFlowIT, OptimisticLockingIT, PhoneRaceIT, WebhookSignatureFilterIT, and others
- `dev/seed_example.sql` — synthetic seed data

**Gate:** CI must be fully green (Maven test + Gitleaks scan) before this release. This is a hard gate.

**What v0.4.0 demonstrates to a reader:**
- `mvn test` runs clean on first clone
- The test suite is the specification: reading the integration tests reveals the contract of every domain boundary
- D2 audience (engineer) is served: they can clone, test, read, and have an accurate picture of the system

---

### v0.5.0 — Engineering Showcase

**Milestone:** Roadmap Phase 3 complete (documentation + source + tests all present)  
**Tag:** `v0.5.0`  
**Entry condition:** v0.4.0 gates passed; README updated with full content; cover image present

**What is added in v0.5.0:**
- `README.md` — complete three-depth landing page with all sections
- `assets/cover/social-preview-1280x640.png` — GitHub social preview
- `assets/diagrams/` — architecture SVGs (if not already in v0.2.0)
- Any remaining engineering writeups in `docs/engineering/`
- Updated Mermaid diagrams in architecture docs (cross-referenced to source)
- Optional: `CHANGELOG.md`

**What v0.5.0 is:**
The repository is functionally complete. Everything promised in the README exists. CI is green. Documentation is present. Source code is published. The three-audience model is fully satisfied.

---

### v1.0.0 — Public Launch

**Milestone:** Public announcement and distribution  
**Tag:** `v1.0.0`  
**Entry condition:** All three-audience walk-through gates from the Extraction Plan pass; cover image live on GitHub; social preview shows correctly

**What distinguishes v1.0.0 from v0.5.0:**
- The repository has been deliberately shared (LinkedIn post, personal site link, etc.)
- The GitHub social preview is configured
- Repository topics are set correctly
- Branch protection is active
- All labels are configured
- GitHub Discussions are enabled

**What v1.0.0 signals:**
"This is ready for the world to read." Not "this is feature-complete" in a product sense — the repository will continue to evolve. But the quality bar has been met, the promise is kept, and the author is ready to point people at it.

---

### v1.x.x — Maintenance Releases

**Period:** Ongoing after v1.0.0  
**Tag pattern:** `v1.MINOR.PATCH`

Minor releases (`v1.1.0`, `v1.2.0`) for:
- New ADRs documenting decisions made after v1.0.0
- OpenAPI/Swagger documentation addition
- Docker Compose for local setup
- New case studies in `docs/case-studies/`
- Publishing the customer/legitimation module (if approved)

Patch releases (`v1.0.1`, `v1.0.2`) for:
- Documentation corrections
- README improvements
- Dependency updates (Dependabot PRs)
- CI/tooling fixes

---

### v2.0.0 — Community Edition

**Milestone:** Community-ready  
**Tag:** `v2.0.0`  
**Entry condition:** At least 3 external Issues or Discussions have been opened; a CONTRIBUTING.md with contribution guidelines exists; good-first-issue labels are present

**What changes in v2.0.0:**
- `CONTRIBUTING.md` updated with detailed contribution workflow
- `docs/case-studies/` has at least one complete case study
- `good-first-issue` labels are actively used
- The repository is presented as a reference implementation, not just a portfolio piece
- Optional: a companion blog post or engineering writeup published externally

**What v2.0.0 is not:**
- A production multi-tenant platform ready for external deployments
- A library with a published API
- A commercial project

**What v2.0.0 signals:**
This repository has crossed the line from "one engineer's portfolio" to "a resource for other engineers." The shift is more about stance than content. The maintainer is actively welcoming engagement, not just showcasing work.

---

## Release Calendar (Approximate)

This is an indicative sequence, not a deadline-driven roadmap:

| Version | Phase | Estimated Readiness |
|---|---|---|
| v0.1.0 | Phase -1 (Foundation) | Immediately after Phase -1 commit |
| v0.2.0 | Roadmap Phase 3 (Docs) | After documentation extraction complete |
| v0.3.0 | Roadmap Phase 2 (Source) | After source code extraction complete |
| v0.4.0 | Phase 2 tests + CI green | After test suite extraction + CI verified |
| v0.5.0 | Full README + assets | After Phase 4 polish complete |
| v1.0.0 | Public launch | After deliberate announcement decision |
| v1.1.0+ | Enhancements | Ongoing |
| v2.0.0 | Community | When engagement justifies it |

---

## Semantic Versioning Interpretation

This repository adapts SemVer for an engineering artefact context:

| Component | Meaning in this context |
|---|---|
| MAJOR | Significant paradigm shift (new stack, complete architectural rethink) |
| MINOR | New content layer or capability (new doc section, new ADR suite, new source package) |
| PATCH | Corrections, improvements, dependency updates |

The `0.x.x` prefix signals that the repository is not yet making the full public promise. `1.0.0` is the version that the author stands behind completely.

---

*This strategy document is reviewed at each major milestone. The release sequence may be reordered if the extraction plan sequence changes. What does not change: the principle that every release is honest about what exists.*
