# Documentation

This directory contains all engineering documentation for the VookedMe appointment scheduling platform.

---

## Structure

| Directory | What it contains | Start here if you want to... |
|---|---|---|
| [architecture/](./architecture/README.md) | System context, data model, security architecture | Understand what the system is and how it fits together |
| [adr/](./adr/README.md) | Architecture Decision Records | Understand *why* the system is designed the way it is |
| [source/](./source/README.md) | Published source code — editorial framework and publication roadmap | Understand what source is published and in what order |
| [governance/](./governance/README.md) | State machines, RBAC matrix, audit requirements, domain rules | Understand what the system is allowed to do |
| [engineering/](./engineering/README.md) | Deep-dives on specific engineering problems | Understand how a specific hard problem was solved |
| [case-studies/](./case-studies/README.md) | Cross-cutting investigations from first principles to production | Read a full engineering narrative |
| [meta/](./meta/README.md) | Repository standards, structure, release strategy | Contribute to or maintain this repository |

---

## Recommended Reading Order

**For a first reading:**

```
architecture/   →   adr/   →   governance/   →   engineering/   →   case-studies/
```

Start with `architecture/` to understand what exists. Move to `adr/` to understand why decisions were made. Read `governance/` for the domain rules that constrain the implementation. Go deeper with `engineering/` and `case-studies/` for specific problems and full narratives.

**If you have 5 minutes:** Read [architecture/README.md](./architecture/README.md), then open [ADR-011](./adr/ADR-011-appointment-temporal-boundary.md). Then read [ADR-001](./adr/ADR-001-single-money-field.md) and [ADR-007](./adr/ADR-007-bot-panel-derive-architecture.md). These three together give the clearest view of how this system is designed and why.

**If you are reading source code:** Start at [source/README.md](./source/README.md). It maps the publication roadmap, the editorial categories, and the recommended reading path through the published artefacts.

**If you are contributing:** Read [meta/repository-standards.md](./meta/repository-standards.md) before writing anything. It defines naming conventions, ADR format, markdown style, and commit conventions.

---

## Design Principles

**Governance documents tell you what the rule is. ADRs tell you why it exists.** Neither is sufficient without the other.

**Every document in this directory describes the system, not the code.** The source code implements what is documented here. If they disagree, the documentation is wrong.

**Documentation is append-only by default.** ADRs are never deleted — superseded ADRs remain as historical context. Architecture documents are updated when the architecture changes, with the change reflected in the corresponding ADR.
