# Diagrams

Version-controlled architecture diagrams as SVG files.

All diagrams referenced from documentation must be stored here. No external diagram services, no hotlinked images.

---

## Status

> **Pending population.** Diagrams will be added in v0.2.0 alongside the architecture documentation.

---

## Planned Diagrams

| File | Description |
|---|---|
| `system-context-c4.svg` | C4 context diagram: VookedMe system boundaries and external actors |
| `tenant-isolation.svg` | The multi-tenant isolation model: how businessId gates every resource |
| `appointment-fsm.svg` | The six-state appointment finite state machine |
| `auth-flow.svg` | JWT issuance and refresh token rotation sequence |
| `whatsapp-booking-flow.svg` | End-to-end WhatsApp booking flow (customer → bot → n8n → API) |

---

## Standards

- Format: SVG (text, version-controllable, resolution-independent)
- Style: See [REPOSITORY_STANDARDS.md §4](../../REPOSITORY_STANDARDS.md) — Mermaid for inline diagrams, SVG for standalone assets
- Naming: `kebab-case-descriptive-name.svg`
- No PNG unless SVG is technically impossible (raster screenshots go to `../screenshots/`)
