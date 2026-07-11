# System Architecture

> Two C4 diagrams. Two engineering questions. Both derived from decisions already recorded in the ADR suite and source artefacts.

Published as **AX-1** of the [Architecture Experience Journey](./README.md).

---

## System Context

```mermaid
%%{init: {'theme': 'default'}}%%
graph TD
    Customer["Customer\n(WhatsApp user)"]
    BusinessOwner["Business Owner\n(administration panel)"]

    subgraph "WhatsApp Channel"
        WA["WhatsApp\n(messaging platform)"]
        EVO["Evolution API\n(self-hosted gateway)"]
    end

    N8N["n8n Orchestration\n(self-hosted)"]
    Backend["VookedMe\n(Spring Boot · Java 21)"]
    DB[("PostgreSQL")]

    Customer -->|"sends message"| WA
    WA -->|"webhook"| EVO
    EVO -->|"message event"| N8N
    N8N -->|"booking request"| Backend
    BusinessOwner -->|"manages operations"| Backend
    Backend -->|"read / write"| DB
```

> **Figure 1** — *System context — VookedMe appointment scheduling platform*: What is VookedMe and what systems interact with it? See [ADR-007 — Derive Bot State from Source of Truth](../adr/ADR-007-bot-panel-derive-architecture.md).

---

## Backend Organisation

```mermaid
%%{init: {'theme': 'default'}}%%
graph TD
    N8N["n8n Orchestration"]
    BO["Business Owner"]
    DB[("PostgreSQL")]

    subgraph "VookedMe Backend"
        SEC["Security\n(authentication · authorisation · rate limiting)"]
        WHL["Webhook Layer\n(webhook validation · request processing)"]
        DEC["Decision Layer\n(business rules · appointment logic · consent enforcement)"]
        DOM["Domain\n(scheduling · customer management · appointments)"]
        EVT["Events\n(domain event bus)"]
        AUD["Audit\n(audit trail · compliance logging)"]
        PER["Persistence\n(data access · schema migrations)"]
    end

    N8N -->|"webhook"| SEC
    BO -->|"REST"| SEC
    SEC --> WHL
    SEC --> DOM
    WHL --> DEC
    DEC --> DOM
    DOM --> EVT
    DOM --> PER
    EVT --> AUD
    AUD --> PER
    PER --> DB
```

> **Figure 2** — *Container diagram — VookedMe backend*: How is the backend organised? See [ADR-016 — Tenant Isolation Pattern](../adr/ADR-016-tenant-isolation-pattern.md).
