# src/

Java source code for the VookedMe multi-tenant appointment scheduling backend.

---

## Status

> **Pending population.** Source code will be added in v0.3.0 per the [RELEASE_STRATEGY.md](../RELEASE_STRATEGY.md).

---

## Package Overview (Planned)

```
src/main/java/com/vookedme/botmanager/
├── analytics/       # Revenue and appointment metrics
├── appointment/     # 6-state FSM, assignment engine, temporal boundary
├── auth/            # JWT issuance, refresh token rotation + reuse detection
├── bot/             # Bot audit trail, event resolver, narrative renderer
├── business/        # Tenant (Business) entity and management
├── common/          # Shared DTOs, exceptions, validators, utilities
├── config/          # Spring config: security, Resilience4j, Sentry, Jackson
├── customer/        # Customer lifecycle, E.164 normalization, GDPR fields
├── employee/        # Employee entity and schedule management
├── notification/    # Outbound notification via n8n (Resilience4j circuit breaker)
├── offering/        # Service offering catalogue
├── schedule/        # Business schedules, BlockedSlot state machine
├── security/        # JWT filter, rate limiting, UserDetailsService
├── user/            # Employee user management (OWNER/EMPLOYEE roles)
└── webhook/         # HMAC-SHA256 validation, turn correlation, idempotency

src/main/resources/
├── application.yml              # Main configuration (all secrets via ${ENV_VAR})
├── application-example.yml      # Annotated configuration reference
└── db/migration/
    └── V1__*.sql ... V78__*.sql  # Complete Flyway migration history

src/test/java/
└── com/vookedme/botmanager/     # Unit + Testcontainers integration tests
```

---

## Key Files to Read First

Once source code is present, start here:

1. **`auth/AuthorizationService.java`** — The single mandatory tenant isolation gate. Every resource access goes through here.
2. **`appointment/AppointmentService.java`** — The FSM + assignment engine + temporal boundary enforcement. The largest and most complex file.
3. **`schedule/BlockedSlotService.java`** — The BlockedSlot state machine (employee leave requests).
4. **`auth/RefreshTokenService.java`** — Refresh token rotation with reuse detection.
5. **`webhook/security/WebhookSignatureFilter.java`** — HMAC-SHA256 webhook validation.

---

## Architecture

See [docs/architecture/ARCHITECTURE.md](../docs/architecture/ARCHITECTURE.md) for the full system context.  
See [REPOSITORY_STRUCTURE.md §Package Architecture](../REPOSITORY_STRUCTURE.md) for the package design rationale.
