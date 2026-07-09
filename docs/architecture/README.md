# Architecture Documentation

This directory contains system-level architectural documentation for the VookedMe appointment scheduling platform.

Architecture documents describe **what the system is and how it works** — at the level of abstraction above individual classes and methods. They are the entry point for an engineer who has not read any source code yet.

---

## Status

> **Pending population.** Architecture documents will be added in v0.2.0 per the [RELEASE_STRATEGY.md](../../RELEASE_STRATEGY.md).

---

## Documents (Planned)

| Document | Description |
|---|---|
| [ARCHITECTURE.md](./ARCHITECTURE.md) | System context, component model, tenancy model, main request flows |
| [DATA_MODEL.md](./DATA_MODEL.md) | Entity model, key relationships, schema decisions |
| [SECURITY.md](./SECURITY.md) | JWT architecture, HMAC webhook validation, rate limiting, secret management |

---

## Reading Order

For a D1 reader (CTO / Technical Lead):

1. **[ARCHITECTURE.md](./ARCHITECTURE.md)** — Start here. Understand the system context and component structure.
2. **[DATA_MODEL.md](./DATA_MODEL.md)** — The entity model and how the domain is represented in the database.
3. **[SECURITY.md](./SECURITY.md)** — How authentication, authorization, and webhook security work.
4. **[../adr/](../adr/)** — Why the key decisions were made.

For a D2 reader (Engineer):

Start with the architecture documents, then move directly to the source code. The governance documents in `../governance/` explain the domain rules that the source code enforces.
