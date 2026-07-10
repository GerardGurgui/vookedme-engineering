# ADR-008 — Timestamp Timezone Migration

**Status:** Accepted — Execution Deferred  
**Date:** 2026-06-04  
**Domain:** data / schema  
**Editorial:** ADVANCED

> **Engineering Question Answered:** When a production database stores temporal data in timezone-naive columns and the JVM timezone is pinned as an interim mitigation, what is the correct migration strategy — and when is the right moment to execute it?

---

## Problem

The backend stores appointment datetimes and all appointment lifecycle timestamps in PostgreSQL `TIMESTAMP WITHOUT TIME ZONE` columns (`timestamp without time zone`), mapped to `LocalDateTime` in Java. This combination creates a fragile invariant: the data appears correct only because the entire stack is pinned to the same timezone (`Europe/Madrid` — the JVM, the application configuration, and the operating context of the initial deployment).

The fragility becomes concrete under any of the following conditions:

- The JVM is deployed in a cloud environment that defaults to UTC (common in container orchestration)
- The system expands to serve users or operators in a different timezone
- Temporal comparisons are made against database-level `now()` in a session that is not explicitly pinned
- A future refactoring removes or relaxes the timezone pin without realising what it protects

The current state is not incorrect — all data was written under the pinned timezone and is internally consistent. But the correctness is environmental, not structural. The structural fix is to replace the naive type with an explicit one (`TIMESTAMP WITH TIME ZONE` — `timestamptz` in PostgreSQL) and switch the Java mapping to `OffsetDateTime`.

---

## Context

### The interim mitigation

A JVM startup guard aborts the application if the JVM timezone is not `Europe/Madrid`. This guard is an intentional architectural decision: it prevents a class of silent data corruption that would occur if the application wrote naive timestamps under the wrong timezone assumption. The guard is not a solution — it is a formalised acknowledgement that the current state requires environmental enforcement.

The Hibernate `jdbc.time_zone` setting is deliberately not set to UTC. Setting it would cause Hibernate to interpret naive values as UTC when reading them, which would shift all existing appointments by +1 or +2 hours relative to their true wall-clock time. This is a known trap when migrating naive timestamps: the correct approach is to interpret existing data explicitly as the timezone it was actually written in, not to change how the ORM reads it before the data is converted.

### Columns already using `TIMESTAMPTZ`

Several columns in the schema already use the correct type — notably `consent_audit.accepted_at` and `appointment_audit_log.occurred_at` (introduced with the audit log infrastructure), as well as user terms and privacy acceptance timestamps. These columns are the target state. They are excluded from the migration scope because they are already correct.

### Data semantics

All naive timestamps in the affected tables were written by the application while the JVM was pinned to `Europe/Madrid`. This means they represent wall-clock time in that timezone — not UTC. A naive timestamp of `14:30` means 14:30 in Madrid, which is `12:30 UTC` in summer and `13:30 UTC` in winter.

This is the key difference from the simpler case where naive timestamps happen to be UTC. In the UTC-naive case, the migration is a type-cast with no semantic change. In the Madrid-local case, the migration must interpret each value as Madrid time and convert it to UTC explicitly.

---

## Decision

**Accept the migration strategy. Defer execution to a pre-launch hardening phase.**

The migration strategy itself is validated. The decision to defer execution is conditional on completing identified prerequisites and is motivated by the low operational risk of the interim state (the pin guard is in place and effective) combined with the operational risk of running a data migration without a verified backup and with a test data anomaly in the dataset.

The interim state is acceptable at the current scale. The migration must be completed before production launch.

---

## Data Verification

Before finalising the migration strategy, the full dataset was audited to answer a critical question: is all data uniformly Madrid-local, or might some records have been written before the timezone guard was introduced — potentially in UTC — resulting in a mixed dataset?

A mixed dataset would require a different migration approach (conditional conversion by date range, not a uniform `AT TIME ZONE` conversion), and would be significantly more complex.

The verification proceeded by exhaustive manual inspection of the complete appointment dataset and by checking the distribution of timestamps against expected business-hours patterns:

- All appointment datetimes fall within Spanish business hours (09:00–17:00 wall-clock). If data were stored in UTC, summer appointments would cluster at 07:00–15:00 UTC, and some records would show early-morning booking times inconsistent with business operation. No such records exist.
- Created-at timestamps for overnight records are consistent with Spanish late-night hours rather than UTC equivalents.
- No record contradicts the Madrid-local interpretation.

One record was identified as a test data anomaly — an appointment with a datetime set to a time inconsistent with normal business operation, showing characteristics of a programmatically generated test entry. This record does not affect the timezone conclusion but is flagged as cleanup prerequisite before migration to avoid ambiguity in validation.

The audit log infrastructure (`appointment_audit_log.occurred_at`, which stores unambiguous `TIMESTAMPTZ` instants) did not cover the historical dataset, as these records predate the audit log. The conclusion therefore rests on exhaustive manual inspection and convergent evidence rather than on cross-referencing against an independent timestamp source.

**Conclusion:** the historical dataset is uniformly Madrid-local. The migration strategy is validated for this dataset.

---

## Migration Strategy

### Database layer

For each column in scope, the migration applies:

```sql
ALTER TABLE appointments
  ALTER COLUMN datetime TYPE timestamptz
    USING datetime AT TIME ZONE 'Europe/Madrid';
```

The `AT TIME ZONE 'Europe/Madrid'` expression interprets the naive value as Madrid wall-clock time and converts it to a `timestamptz` (UTC-normalised) with the correct offset applied per date. This is DST-correct: PostgreSQL uses the timezone database to resolve the applicable offset for each individual timestamp, applying +01:00 for winter values and +02:00 for summer values.

The conversion is independent of the PostgreSQL session timezone — it is explicit in the SQL expression, not derived from session configuration.

### Java layer

For all migrated columns, the entity field type changes from `LocalDateTime` to `OffsetDateTime`. Write sites that use `LocalDateTime.now()` change to `OffsetDateTime.now()`.

DTOs and events that carry appointment datetimes will serialise with an explicit UTC offset (e.g., `…+02:00`) rather than a bare local time. This is a breaking change in the JSON contract for clients that parse the timestamp as a bare local time — the panel frontend must be updated to handle the explicit offset. This update is deferred to the same migration phase.

### Deployment order

1. Verified database backup (prerequisite — see below)
2. Database migration (Flyway)
3. Java changes (entities, DTOs, write sites)
4. Validation
5. Panel frontend update (parsing with explicit offset)

The database and Java changes are deployed together. The panel update may follow in a separate release once the API contract change is absorbed.

---

## Migration Scope — Phase 1

**Included** — columns that govern business logic and whose correctness affects appointment scheduling, expiry, reminders, and lifecycle transitions:

- **`appointments`:** `datetime`, `cancelled_at`, `cancellation_requested_at`, `cancellation_request_expired_at`, `cancellation_request_decided_at`, `approved_at`, `revoked_at`, `paid_changed_at`
- **`reminders`:** `scheduled_at`, `sent_at`, `delivered_at`, `read_at`
- **`blocked_slots`:** `start_datetime`, `end_datetime`, `requested_at`, `approved_at`, `rejected_at`, `cancelled_at`

**Excluded from Phase 1** — deferred to a subsequent phase:

- `BaseEntity.created_at` / `updated_at` (present on approximately 30 tables — high blast radius for primarily display and generic audit columns)
- Token tables (refresh tokens, password reset tokens — short-lived, ephemeral)
- Columns that are already `TIMESTAMPTZ` (no migration required)

After Phase 1, entities will have mixed field types (`datetime` as `OffsetDateTime`, `created_at` as `LocalDateTime`). This is an accepted transitional state, consistent with the existing pattern where audit columns already use `OffsetDateTime` alongside inherited `LocalDateTime` base fields.

---

## DST Edge Cases

Daylight saving transitions produce two classes of edge cases that must be tested explicitly.

**Spring-forward gap (late March):** In Madrid, clocks advance from 02:00 to 03:00 on the last Sunday in March. The hour 02:00–03:00 does not exist in local time. Any naive timestamp in this range is ambiguous; there is no unambiguous UTC equivalent. PostgreSQL's documented behaviour is to interpret these values as post-transition times (effectively advancing them). This behaviour is correct and expected; the integration tests document it.

**Fall-back overlap (late October):** Clocks retreat from 03:00 to 02:00 on the last Sunday in October. The hour 02:00–03:00 exists twice. A naive timestamp in this range is ambiguous; it could correspond to either UTC-equivalent. PostgreSQL chooses the earlier interpretation (summer offset, UTC+2). This choice is documented and consistent; the integration tests verify it.

In practice, appointments in these windows would be anomalous (the gap hour does not exist and the overlap hour occurs at 02:00–03:00 AM, outside business hours). The tests cover these cases for correctness, not because they are expected in production data.

---

## Validation Plan

### DST integration tests

Using Testcontainers (real PostgreSQL instance):

- Seed a table with naive TIMESTAMP rows representing the test cases below
- Run the migration
- Assert the resulting `TIMESTAMPTZ` values in UTC

Test cases:
- **Summer value:** `2026-07-15 14:30` → must produce `2026-07-15 12:30Z` (UTC+2 applied)
- **Winter value:** `2026-01-15 14:30` → must produce `2026-01-15 13:30Z` (UTC+1 applied)
- **Spring-forward edge:** a time in the non-existent hour — document PostgreSQL's resolution
- **Fall-back edge:** a time in the ambiguous hour — verify PostgreSQL's earlier-offset choice

### Functional regression

The full integration test suite must pass after the migration, including appointment scheduling, cancellation and timeout flows, blocked-slot management, audit log writes, and all reminder scheduling paths. Reminder and deadline calculations that previously compared `LocalDateTime` values must produce the same outcome when comparing `OffsetDateTime` values.

### API round-trip

A datetime sent through the booking webhook or panel and subsequently re-read must represent the same instant (with the offset now explicit in the response). This test verifies that no offset is introduced by the migration.

---

## Rollback Plan

The Flyway migration is forward-only. Rollback is implemented as a subsequent migration:

```sql
ALTER TABLE appointments
  ALTER COLUMN datetime TYPE timestamp
    USING (datetime AT TIME ZONE 'Europe/Madrid');
```

This is the inverse conversion — from the stored UTC instant back to Madrid wall-clock time. The conversion is lossless for all timestamps except the two DST edge cases per year (the spring-forward gap, where the original value was ambiguous, and the fall-back overlap, where one hour's values map to the same naive time after round-trip). These exceptions are documented and accepted.

Rollback trigger: any DST validation failure, any functional regression in reminder or timeout calculations, or any incorrect round-trip result in staging.

---

## Execution Prerequisites

Migration must not proceed until all of the following are confirmed:

1. **Verified database backup** — a complete dump of the production database, verified as restorable. This is a non-negotiable prerequisite for any schema migration on a production system.
2. **Test data cleanup** — the test data anomaly identified during verification must be resolved (removed or reclassified) so that validation can proceed against a dataset that reflects genuine production state.
3. **Explicit approval to proceed** — the migration has been designed and validated but has not been authorised for execution. Explicit sign-off is required before running the migration against production.

---

## Alternatives Considered

**Do nothing (keep `LocalDateTime` with the JVM pin):** The current state is stable at small scale. The risk is that any deployment change, infrastructure migration, or JVM upgrade that relaxes or removes the timezone pin would silently corrupt new data while leaving old data intact — producing a mixed dataset that is harder to fix than a pre-migration uniform dataset. This alternative is rejected as a long-term position; it is acceptable only as an interim state while the migration is prepared.

**Set `hibernate.jdbc.time_zone=UTC`:** This would cause Hibernate to reinterpret all reads as UTC, producing values shifted by +1/+2 hours relative to their true meaning. This would make the data appear broken without actually fixing the underlying type. Rejected.

**Map to `Instant` instead of `OffsetDateTime`:** `Instant` is semantically correct for UTC storage but is less ergonomic in Java for scheduling and comparison logic that is inherently calendar-relative (e.g., "is this appointment in the next 24 hours?"). `OffsetDateTime` carries the offset, is serialisable with explicit timezone context, and is more natural for domain objects that represent scheduled times. `OffsetDateTime` is the approved type for this codebase.

**Migrate `BaseEntity` timestamps in Phase 1:** Including `created_at`/`updated_at` on all ~30 tables would significantly increase the blast radius of the migration (more column changes, more ORM changes, more potential for regression). These columns are used for display and generic audit purposes, not for business logic. The risk-to-benefit ratio favours deferring them to a subsequent phase.

---

## Consequences

### Positive

- Appointment datetimes are stored with explicit UTC offsets. No application layer needs to know or assume the storage timezone — the type carries the semantics.
- The JVM timezone guard can be relaxed after the migration. The system can run in UTC (the conventional default for servers) without affecting data correctness.
- Temporal comparisons against database `now()` (used for timeout and deadline logic) are unambiguous. There is no assumption that `now()` and `datetime` share the same timezone interpretation.
- The JSON API serialises datetimes with explicit offsets, which is less ambiguous for frontend consumers.

### Negative

- The JSON contract changes: responses that previously contained `2026-07-15T14:30:00` will now contain `2026-07-15T14:30:00+02:00`. Clients that parse the value as a bare local time will require updating.
- After Phase 1, entity objects carry mixed temporal types (`OffsetDateTime` for business-logic columns, `LocalDateTime` for inherited audit columns). This inconsistency is accepted as a transitional state; it is resolved when the Phase 2 migration covers `BaseEntity`.
- The migration requires a production database backup, a staging validation pass, and explicit approval. This adds lead time relative to simply deploying code changes.

### Neutral

- The two DST edge cases (spring-forward gap, fall-back overlap) affect at most two instants per year, both outside typical business hours. The documented PostgreSQL behaviour for these cases is consistent and testable.

---

## Engineering Principle

A timezone-naive timestamp in a database is a type that lacks the information needed to interpret it correctly — it is a number without units. The interim mitigation of pinning the runtime timezone converts a structural problem into an environmental constraint: the data is only correct if the environment is configured correctly. This is the kind of invariant that survives in codebases for years, is not visible to new contributors, and fails in ways that are subtle and hard to diagnose. The correct resolution is structural: store timestamps with their timezone attached. The cost of migration is a one-time operational risk. The cost of the unmigrated state is a permanent architectural liability.

---

## Related

- [ADR-017](./ADR-017-appointment-fsm-design.md) — the appointment FSM; `appointment.datetime` is the central temporal reference in the state machine and the primary candidate for this migration
- [ADR-011](./ADR-011-appointment-temporal-boundary.md) — the temporal boundary that divides the appointment lifecycle; all boundary evaluations depend on correct interpretation of `appointment.datetime`
- [ADR-014](./ADR-014-bot-data-minimisation-and-audit-log.md) — the `appointment_audit_log`, whose `occurred_at` column already uses `TIMESTAMPTZ` and serves as the target-state reference for temporal columns

## Source Code Reference

*Populated when source code is present.*

- `JvmTimezoneInvariant` — the startup guard that aborts the JVM if the timezone is not `Europe/Madrid`; remains active until the migration is executed and the guard is relaxed
- `AppointmentService` — the primary write site for appointment lifecycle timestamps; contains approximately 30 `LocalDateTime.now()` calls to be migrated to `OffsetDateTime.now()`
- `BaseEntity` — the base class contributing `created_at` and `updated_at` to all entities; these fields are excluded from Phase 1 scope
