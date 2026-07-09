# GitHub Configuration

**Repository:** `vookedme-engineering`  
**Document Version:** 1.0  
**Date:** 2026-07-09  
**Status:** Specification — implement when repository goes public

---

## §1. Repository Settings

### Identity

| Field | Value |
|---|---|
| **Repository name** | `vookedme-engineering` |
| **Visibility** | Public |
| **Description** | Multi-tenant SaaS appointment scheduling backend — Spring Boot 4.0.3 / Java 21 · WhatsApp bot · GDPR-compliant · 78 Flyway migrations · Testcontainers |
| **Website** | *(leave blank until portfolio site exists)* |

### Topics (max 20, ordered by priority)

```
java
spring-boot
multi-tenant
saas
appointment-scheduling
domain-driven-design
postgresql
flyway
testcontainers
whatsapp-bot
gdpr
state-machine
jwt-security
clean-architecture
rest-api
java-21
resilience4j
hmac
openapi
portfolio
```

### Social Preview

Upload `assets/cover/social-preview-1280x640.png` in Settings → General → Social preview.

---

## §2. Features

| Feature | Setting | Rationale |
|---|---|---|
| Wikis | **Disabled** | All documentation lives in `docs/` as version-controlled Markdown |
| Issues | **Enabled** | Use for tracking improvements and questions from external readers |
| Projects | **Disabled** | Sprint management stays in private workspace |
| Discussions | **Enabled** (Q&A + General categories) | Enables technical conversations without cluttering Issues |
| Preserve this repository | **Disabled** | Not an archive |
| Sponsorships | **Disabled** | Not a commercial OSS project |

---

## §3. Branch Strategy

### Branches

| Branch | Purpose | Protected? |
|---|---|---|
| `main` | Production-ready at all times | Yes |
| `feat/*` | New features or documentation | No |
| `fix/*` | Bug fixes | No |
| `docs/*` | Documentation-only changes | No |
| `chore/*` | Build, CI, configuration | No |

### Branch Protection Rules (for `main`)

Enable in Settings → Branches → Branch protection rules:

```
Branch name pattern: main

☑ Require a pull request before merging
  ☐ Require approvals: 0  (solo maintainer — PR exists for CI gate, not review)
  ☑ Dismiss stale pull request approvals when new commits are pushed

☑ Require status checks to pass before merging
  Required checks:
  - build-and-test
  - gitleaks-scan

☑ Require branches to be up to date before merging
☐ Require conversation resolution before merging  (optional for solo work)
☑ Require signed commits  (recommended)
☐ Include administrators  (maintainer may bypass in emergencies)
☑ Restrict force pushes
☑ Restrict deletions
```

---

## §4. Labels

Delete all default GitHub labels and replace with this set. Colors are hex values (set in Settings → Labels).

### Type Labels

| Label | Color | Description |
|---|---|---|
| `type: feat` | `#0075ca` | New feature or capability |
| `type: fix` | `#d73a4a` | Bug fix |
| `type: docs` | `#0052cc` | Documentation improvement |
| `type: chore` | `#e4e669` | Build, CI, configuration |
| `type: refactor` | `#5319e7` | Code refactoring |
| `type: test` | `#c2e0c6` | Test additions or changes |

### Scope Labels

| Label | Color | Description |
|---|---|---|
| `scope: appointment` | `#f9d0c4` | Appointment domain |
| `scope: auth` | `#fef2c0` | Authentication and authorization |
| `scope: bot` | `#c5def5` | Bot integration |
| `scope: security` | `#b60205` | Security-related |
| `scope: db` | `#0e8a16` | Database / Flyway migrations |
| `scope: docs` | `#1d76db` | Documentation structure |
| `scope: ci` | `#e4e669` | CI/CD pipeline |

### Priority Labels

| Label | Color | Description |
|---|---|---|
| `priority: high` | `#d73a4a` | Blocks a release milestone |
| `priority: medium` | `#e4e669` | Should be done this cycle |
| `priority: low` | `#c2e0c6` | Nice to have |

### Status Labels

| Label | Color | Description |
|---|---|---|
| `status: needs-discussion` | `#d876e3` | Requires design decision before implementation |
| `status: good-first-issue` | `#7057ff` | Well-scoped for a first-time contributor |
| `status: wontfix` | `#ffffff` | Intentional — not a bug, not to be changed |

---

## §5. Issue Templates

Issue templates live in `.github/ISSUE_TEMPLATE/`. Two templates are provided.

### Bug Report (`bug_report.yml`)

Structured fields:
- **Describe the bug** — What happened? What did you expect?
- **Steps to reproduce** — Numbered steps leading to the issue
- **Environment** — Java version, Maven version, OS
- **Relevant log output** — Stack trace or error message (code block)
- **Additional context** — Links to relevant ADRs or architecture docs

Auto-labels: `type: fix`, `status: needs-discussion`

### Feature Request (`feature_request.yml`)

Structured fields:
- **Is this related to a problem?** — Pain point description
- **Describe the solution you'd like** — Clear proposal
- **Describe alternatives you've considered** — Encourages ADR thinking
- **Additional context** — References to relevant engineering decisions

Auto-labels: `type: feat`

---

## §6. Pull Request Template

Location: `.github/PULL_REQUEST_TEMPLATE.md`

Content:

```markdown
## Summary

Brief description of what this PR does.

## Motivation

Why is this change needed? Reference the Issue or ADR that motivates it.

## Changes

- List of key changes

## Testing

How was this change tested?
- [ ] Unit tests added/updated
- [ ] Integration tests added/updated
- [ ] Manual testing (describe)

## Documentation

- [ ] ADR written (if this is an architectural decision)
- [ ] Architecture docs updated (if applicable)
- [ ] Governance docs updated (if applicable)
- [ ] README updated (if applicable)

## Security

- [ ] No secrets committed (gitleaks pre-commit ran)
- [ ] No real tenant data in tests or seed files
- [ ] Authorization checks are explicit (AuthorizationService called)
```

---

## §7. CODEOWNERS

Location: `.github/CODEOWNERS`

```
# Global owner — all files
* @GerardGurgui

# Foundation documents
/ENGINEERING_PRODUCT_FOUNDATION.md @GerardGurgui
/REPOSITORY_STANDARDS.md @GerardGurgui

# Security-sensitive paths
/.gitleaks.toml @GerardGurgui
/.githooks/ @GerardGurgui
/src/main/java/**/security/ @GerardGurgui
/src/main/java/**/auth/ @GerardGurgui
/src/main/java/**/webhook/security/ @GerardGurgui
```

---

## §8. GitHub Actions

### CI Workflow (`.github/workflows/ci.yml`)

Two parallel jobs:

**Job 1: `build-and-test`**
```yaml
runs-on: ubuntu-latest
steps:
  - Checkout (fetch-depth: 0)
  - Setup JDK 21 (temurin)
  - Cache Maven dependencies
  - mvn -B verify  (compile + test + package)
```

**Job 2: `gitleaks-scan`**
```yaml
runs-on: ubuntu-latest
steps:
  - Checkout (fetch-depth: 0)  # full history required
  - gitleaks detect --source . --config .gitleaks.toml
```

Both jobs are required for branch protection (see §3).

### Dependabot (`.github/dependabot.yml`)

```yaml
version: 2
updates:
  - package-ecosystem: "maven"
    directory: "/"
    schedule:
      interval: "weekly"
      day: "monday"
    open-pull-requests-limit: 5
    labels:
      - "type: chore"
      - "scope: ci"
  - package-ecosystem: "github-actions"
    directory: "/"
    schedule:
      interval: "monthly"
    labels:
      - "type: chore"
      - "scope: ci"
```

---

## §9. Security Policy

Location: `SECURITY.md`

Key contents:
- Scope: this is a portfolio/educational repository; no production secrets are present
- Vulnerability reporting: email `gerardgurgui1991@gmail.com` directly
- Response time: best effort for a solo maintainer
- Credentials: any credential found in this repository is either already rotated or was never real

---

## §10. Releases

Releases are created at each version milestone defined in `RELEASE_STRATEGY.md`. Each release:
- Has a tag (`vMAJOR.MINOR.PATCH`)
- Has a GitHub Release with a human-readable changelog entry
- Links to the milestone's completion PR or commit

Release notes template:
```markdown
## What's in v0.x.0

### Added
- [specific additions]

### Changed
- [specific changes]

### Architecture Decisions
- [ADRs accepted in this release]

### Notes
- [any notes for readers or contributors]
```

---

## §11. Discussions

Enable two categories:

**Q&A**
For questions from readers: "How does the multi-tenant isolation work?", "Why did you choose Flyway over Liquibase?"

**General**
For open-ended engineering discussions inspired by the repository: design trade-offs, alternative approaches, improvement proposals.

**Do NOT enable:** Ideas, Polls, Show and tell (too much noise for a solo engineering repository)

---

## §12. LICENSE

**Recommended licence:** MIT

Rationale: The repository is an engineering portfolio. MIT maximises reusability — engineers who want to use patterns from this repository (the state machine approach, the AuthorizationService pattern, the Flyway migration strategy) can do so without restriction. The commercial product is not in this repository, so there is no commercial IP to protect.

```
MIT License

Copyright (c) 2026 Gerard Gurgui

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## §13. Implementation Checklist

When the repository is created on GitHub:

- [ ] Repository created with correct name and description
- [ ] Visibility set to Public
- [ ] Topics added (all 20)
- [ ] Social preview image uploaded
- [ ] Wiki disabled
- [ ] Discussions enabled (Q&A + General)
- [ ] Projects disabled
- [ ] Default labels deleted
- [ ] Custom labels created (per §4)
- [ ] Branch protection rules applied to `main`
- [ ] CODEOWNERS file committed
- [ ] Dependabot config committed
- [ ] CI workflow running and green
- [ ] `LICENSE` file present
- [ ] `SECURITY.md` present
- [ ] First release (`v0.1.0`) created

---

*This document is a specification. No GitHub features are implemented by reading it. Implement each section when the repository reaches the corresponding milestone in `RELEASE_STRATEGY.md`.*
