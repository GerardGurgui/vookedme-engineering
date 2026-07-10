# docs/meta/

Repository meta-documentation — documents that describe the repository itself rather than the system it contains.

---

## Documents

| Document | Description |
|---|---|
| [engineering-foundation.md](./engineering-foundation.md) | Vision, purpose, philosophy, and identity of this engineering product |
| [repository-structure.md](./repository-structure.md) | Canonical directory tree, folder responsibilities, package architecture |
| [repository-standards.md](./repository-standards.md) | Markdown style, naming conventions, ADR format, commit conventions |
| [adr-publication-standard.md](./adr-publication-standard.md) | ADR template, mandatory sections, sanitization checklist, editorial classification |
| [publication-pipeline.md](./publication-pipeline.md) | The 8-stage pipeline for publishing Engineering Journeys — reusable for all Journey types |
| [release-strategy.md](./release-strategy.md) | Version lifecycle — what each release milestone represents |
| [github-configuration.md](./github-configuration.md) | GitHub repository settings specification (labels, branch protection, topics) |

---

## Who Should Read This

These documents are written for the repository maintainer, not for external visitors. A first-time visitor to the repository does not need to read any of them — the `README.md` at the root provides the appropriate entry point.

**Read `engineering-foundation.md`** if you want to understand why this repository exists and the principles governing every decision in it.

**Read `repository-structure.md`** if you want to understand the purpose of each folder and the reasoning behind the package architecture.

**Read `repository-standards.md`** before writing documentation, submitting a PR, or adding an ADR — it defines the naming conventions, markdown style, and commit forma