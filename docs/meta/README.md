# docs/meta/

Repository meta-documentation — documents that describe the repository itself rather than the system it contains.

---

## Documents

| Document | Description |
|---|---|
| [engineering-foundation.md](./engineering-foundation.md) | Vision, purpose, philosophy, and identity of this engineering product |
| [repository-structure.md](./repository-structure.md) | Canonical directory tree, folder responsibilities, package architecture |
| [repository-standards.md](./repository-standards.md) | Markdown style, naming conventions, ADR format, commit conventions |
| [release-strategy.md](./release-strategy.md) | Version lifecycle — what each release milestone represents |
| [github-configuration.md](./github-configuration.md) | GitHub repository settings specification (labels, branch protection, topics) |

---

## Who Should Read This

These documents are written for the repository maintainer, not for external visitors. A first-time visitor to the repository does not need to read any of them — the `README.md` at the root provides the appropriate entry point.

**Read `engineering-foundation.md`** if you want to understand why this repository exists and the principles governing every decision in it.

**Read `repository-structure.md`** if you want to understand the purpose of each folder and the reasoning behind the package architecture.

**Read `repository-standards.md`** before writing documentation, submitting a PR, or adding an ADR — it defines the naming conventions, markdown style, and commit format used throughout.

**Read `release-strategy.md`** to understand what each version milestone means and what the repository must contain before each release.

**Read `github-configuration.md`** when setting up or updating the GitHub repository settings (labels, topics, branch protection, Dependabot).
