# Engineering Deep-Dives

This directory contains engineering writeups — deep dives on specific problems encountered during the design and implementation of the VookedMe platform.

Engineering writeups are more specific than architecture documents and more transferable than source code comments. They follow a consistent structure: problem statement → constraints → solution → consequences → what we would do differently.

---

## Status

> **Pending population.** Engineering writeups will be added in v0.5.0 per the [release strategy](../meta/release-strategy.md).

---

## Documents (Planned)

| Document | Description |
|---|---|
| [CUSTOMER_LEGITIMATION.md](./CUSTOMER_LEGITIMATION.md) | GDPR-compliant WhatsApp communication: the OutboundLegitimacyGate architecture |

---

## Intended Audience

Engineering writeups are written for senior engineers who have encountered similar problems. They assume familiarity with Spring Boot, PostgreSQL, and general backend architecture. They do not assume familiarity with the VookedMe domain — context is provided inline.

The goal of each writeup: a reader who finishes it should be able to apply the same approach to a different codebase facing the same problem.
