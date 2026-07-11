# ADR-012 — Conversational Coherence via State Re-anchoring

**Status:** Accepted  
**Date:** 2026-06-14  
**Domain:** bot / conversation  
**Editorial:** ADVANCED

> **Engineering Question Answered:** When a backend system dispatches state-change notifications to customers through a messaging interface, and those customers respond to those notifications in the same conversation, how do you ensure that the bot's replies are consistent with the state that was communicated — rather than with a stale or fabricated version of it?

---

## Problem

A production incident exposed a structural incoherence in how the bot relates to backend state. An appointment was rescheduled by the business. The system dispatched a notification to the customer informing them of the new time. The customer replied to that notification asking to change it again. The bot replied as if the reschedule had never occurred — it did not know about the state that it had just communicated a message about.

This is not a probabilistic failure or a model quality issue. It is a structural gap: the bot had no mechanism to synchronise its knowledge of appointment state with the backend before composing a reply. The model's response was coherent with the conversation buffer but incoherent with reality. In a product whose primary value is a natural, accurate booking experience, this failure mode is not a statistical edge case to be tolerated — it is a violation of the product's core promise.

The incident revealed that the bot's state awareness was opportunistic. If the relevant appointment happened to be mentioned in the conversation buffer, the model might reference it correctly. If the state had changed after the last time it was mentioned — which is exactly the scenario when the system dispatches a notification about a change — the model had no way to know. The notification was sent outside the conversation buffer; the conversation buffer was the bot's only state reference.

## Context

The architecture assigns distinct roles to each layer:

- **Backend** — the single source of truth for all appointment state. Every booking, cancellation, and reschedule is validated by the backend before being committed. The backend's database is the authoritative record.
- **Orchestration layer** — coordinates the conversational workflow: receives inbound messages, calls backend tools, composes context for the language model, dispatches responses. It controls what the model sees and when.
- **Language model** — interprets natural language, identifies intent, and composes natural-language responses within the boundaries set by the orchestration layer. It has no independent access to the backend; it can only act through the tools the orchestration layer provides.

A mechanism existed to clear the conversation buffer when the business operator made changes to an appointment — a hard-purge that removed stale context after mutations. This mechanism was necessary and correct: stale context would cause the same incoherence. But by itself, clearing stale context only removed the wrong information; it did not provide the correct information. The model arrived at the next customer turn with an empty context about the recent state and no mechanism to refresh it before responding.

The conversational product must satisfy a set of minimum correctness properties:

- The bot must not assert something that contradicts the current appointment state.
- The bot must address what the customer actually asked, not redirect the conversation towards recent changes.
- The bot must not invent information when it is uncertain; it must ask or acknowledge limits.
- The bot must be able to act on or escalate any actionable customer request.

These properties must hold after a backend state change that the customer is aware of and responding to. They are the minimum viable standard for a booking assistant to be trustworthy.

## Decision

**Deterministic state re-anchoring at every inbound turn.** Before the language model composes a response to any customer message, the orchestration layer fetches the current appointment state from the backend and injects it into the model's context. This step is a control-flow operation in the orchestration layer — not a prompt instruction to the model. The model cannot skip it, override it, or choose not to execute it.

### Core properties of the re-anchoring

**1. Deterministic, not model-judged.** The re-anchor executes on 100% of inbound customer turns. It is not conditional on message content, on detected intent, or on any model judgment about whether re-anchoring is needed. A conditional re-anchor that fires only when the model thinks it is necessary is not a solution: the model that fails to detect the need for re-anchoring is the same model that would fail to re-anchor when needed.

**2. Anti-overcorrection discipline.** Access to the current state does not mean every response is about the state. If a customer asks about a payment method, the bot should answer the question — not redirect the conversation to the appointment that was rescheduled this morning. Re-anchoring is the correction floor (do not contradict the state), not the conversational agenda. The distinction between "be aware of the state" and "make the state the topic" is encoded in the orchestration logic and in the model's response guidelines.

**3. Recent-window scope.** The re-anchoring query does not return only future appointments. It returns appointments that are currently active or that transitioned within a recent time window.

This is necessary because messaging is asynchronous. A customer may receive a notification about a reschedule at 9pm and respond the following morning. By the time of the response, the rescheduled appointment's previous datetime may be in the past. A query that returns only future appointments would not return this appointment; the model would have no awareness of the state change it is being asked about.

The window is sized to cover the full asynchronous response cycle. A notification sent in the evening should remain visible to the bot until the following day — the natural delay in conversational response over a messaging channel. The window is implemented as a lookback from the current time, not as a start-of-day boundary (which would be insufficient for notifications sent late in the day and replied to the following morning).

**4. Minimisation by state.** Terminal appointment states — cancelled, completed, no-show — are excluded from the re-anchoring context. Appointments in these states are no longer actionable and present no meaningful coherence risk: a customer cannot request changes to a completed appointment, and the bot would decline. Including them in every re-anchor response would expand the data surface unnecessarily. Only appointments that are pending, confirmed, or in a cancellation-requested state are included.

**5. Slim payload.** The re-anchoring query returns the same minimal field set defined by the boundary DTO in ADR-014: appointment identifier, datetime, status, service name, offering name, assigned employee, duration. Personal identifiers, payment data, and internal fields are excluded. The re-anchoring mechanism does not create a new surface for sensitive data to reach the model.

**6. Temporal guards for bot-reachable write operations.** The recent-window scope means the model may now see appointments whose datetime has passed. Without a corresponding guard, the model could attempt to reschedule or cancel a past appointment — operations that are invalid under the temporal boundary principle established in ADR-011. Guards are added to the bot's reschedule and cancel operations to reject attempts on past appointments. The expansion of the readable window and the addition of these write guards are a single unit of change: the window creates the reachability, the guards contain it.

**7. Compatibility with hard-purge.** A mechanism that clears the conversation buffer after operator-initiated state changes already exists and is not modified. The two mechanisms are complementary: the hard-purge removes stale context; the re-anchoring provides fresh context. Together they ensure the model neither holds stale information nor arrives at a turn without current information.

## Alternatives Considered

The solution space was evaluated across eight candidate approaches. The alternatives are described and the reasons for rejection are given.

**Re-anchoring (chosen):** at every inbound turn, the orchestration layer fetches the current appointment state and injects it into the model's context as a structured fact. The model reads the facts; the orchestration controls the fetch. Minimum viable, additive, no new schema, compatible with all existing mechanisms.

**Persistent delta / event-sourced context:** record each state change as a durable event associated with the conversation. The model would receive the sequence of recent events alongside the current state. More expressive than re-anchoring alone but introduces new schema, new storage obligations, and tension with the hard-purge mechanism (which exists to clear history after mutations). Deferred as a potential future enhancement; E1 does not foreclose it.

**Temporary conversational memory:** give the model episodic memory of the conversation session, including events that occurred between turns. Addresses the coherence problem but retains more conversational content than is necessary for the minimum correctness properties. Data minimisation arguments favour the stateless re-anchor over episodic retention.

**Lightweight correlation:** link the customer's inbound message to the outbound notification that preceded it via message identifiers. When the customer replies to a notification, the correlation provides the bot with the state that was in the notification. Partial coverage: correlation works when the customer quotes or directly references the notification; it does not work for responses that come without a direct reference (a customer who simply types "can you change it?" without quoting the notification would not be covered). Rejected as insufficient on its own; remains compatible as a supplementary enhancement.

**Unified event stream:** treat outbound notifications as conversation turns, making state-change events part of the same message history the model sees. Architecturally cleaner but requires structural change to how notifications are dispatched and recorded. Higher complexity for the same practical outcome. Not necessary at this stage.

**Narrow the conversational promise:** limit what the bot claims to know about recent state changes, explicitly declining to discuss appointments that may have changed since the last turn. Technically solves the incoherence problem by removing the capability rather than closing the gap. Rejected as an anti-pattern: it degrades the product in order to avoid a product failure, rather than resolving the failure. A booking assistant that cannot reliably answer questions about recent changes to bookings is not a better product; it is a diminished one.

**Re-anchoring plus correlation (combined approach):** run both mechanisms simultaneously. A superset of the chosen approach that provides additional coverage in the case where correlation succeeds. Deferred: re-anchoring alone closes the production incident; correlation can be added without architectural conflict when the channel supports it.

**Escalation-first:** when the model detects potential state uncertainty, escalate to a human operator rather than attempt a response. Correct in principle for the supervised operating mode but incompatible with the autonomous mode, where escalation is not always available. A mechanism that works in some operating modes but not others is not a general solution; it would require mode-specific logic that complicates the orchestration.

## Rationale

State re-anchoring is the minimum intervention that closes the production incident. It introduces no new schema. It adds no state storage. It is reversible: removing the re-anchor step restores previous behaviour. It does not foreclose any of the more expressive alternatives as future enhancements.

The selection criterion for the architecture was not "which approach provides the most capability" but "which approach closes the production incident while satisfying the minimum correctness properties, without creating new obligations or architectural entanglements." Re-anchoring satisfies this criterion; the alternatives either exceed it (more complex, more data, more new components) or fall short (partial coverage, mode-specific applicability, or product degradation).

The key property that distinguishes re-anchoring from all alternatives involving model instructions is that the re-anchor executes at the orchestration layer, not at the model layer. An instruction to the model to "check the current state before responding" is a probabilistic control: the model may comply, may partially comply, or may fail to comply in a way that is not detectable at the instruction level. An orchestration-layer fetch that happens before the model receives its context is a deterministic control: the model receives the current state because the orchestration put it there, not because the model chose to retrieve it.

## GDPR and Data Minimisation

The recent-window scope means the re-anchoring query exposes more appointment records to the language model than a future-only query would. This expansion is a data minimisation consideration that requires explicit justification under the proportionality principle.

The justification rests on three properties:

**Necessity:** a lookback window shorter than the full asynchronous response cycle (evening notification, following-morning response) would fail the coherence requirement it is designed to meet. The window is the minimum that covers the cycle. A window insufficient for its purpose is not proportionate; it is simply inadequate.

**Proportionality:** the DTO returned by the re-anchoring query carries no personal identifiers — it contains status, time, service, and employee information. Past appointments in the window are returned in read-only context: the temporal guards on write operations mean the model cannot act on them. Expanding the readable window without expanding write authority means the additional exposure is informational, not operational.

**Scope control:** terminal states (cancelled, completed, no-show) are excluded. Appointments that are no longer relevant to any possible customer action are not included in the re-anchor payload. The window is time-bounded, not unbounded.

These three properties — minimum window, minimal payload, exclusion of terminal states — constitute the data minimisation argument for the re-anchoring scope.

## Consequences

### Positive

- The production coherence failure — the bot responding inconsistently with a state it had just communicated — is closed structurally. The re-anchor executes on every turn; there is no turn on which the model may accidentally use stale state.
- The mechanism is additive and reversible. It does not introduce new schema, new tables, or new services. Removing it restores prior behaviour with no migration.
- Architectural optionality is preserved. None of the more expressive alternatives — event correlation, episodic memory, unified event streams — are foreclosed. They can be added on top of re-anchoring if future evidence justifies them.
- The temporal guard additions resolve a latent gap in the write operations available to the bot: past appointments were reachable but unguarded before this change.

### Negative

- Each inbound customer turn now requires a backend read for the re-anchoring query. This adds one database query per customer turn. At the volumes the system operates at, this is not a performance concern; at much higher scale it would be. Caching strategies could reduce this cost without changing the architectural contract.
- Anti-overcorrection — the discipline of being aware of the state without making the state the topic — is expressed partly in the model's response guidelines. This is a probabilistic element: the model will not always be perfectly calibrated. The consequences of overcorrection (the bot unexpectedly steering conversation towards a recent change) are disruptive but not structurally harmful.
- The 24-hour lookback window is a parameter that encodes a data minimisation decision. If the actual asynchronous response cycle changes — for example, if the system is deployed in a context where notifications are frequently responded to more than 24 hours later — the window parameter requires adjustment. This is an acknowledged operational dependency.

### Neutral

- The recent-window read expands the data surface the language model sees relative to a future-only query. This expansion is controlled (minimal payload, write guards, terminal state exclusion) and is justified by the proportionality reasoning above.
- The re-anchoring mechanism operates identically regardless of which bot operating mode is active. This uniformity simplifies the orchestration logic and ensures the coherence guarantee applies to all operational configurations.

## Engineering Principle

The compliance boundary of a system is the layer whose execution is deterministic. The same principle applies to conversational correctness: the correctness boundary of a bot is not the language model, which composes responses based on what it was told — it is the orchestration layer, which controls what the model is told and when. A bot that is "good at remembering" the current state is a probabilistic system. A bot that is forced to re-read the current state before every response is a deterministic one. The difference is not a quality difference about the model; it is an architectural difference about where the guarantee lives. When product integrity depends on the bot knowing the current state of the world, that guarantee should live in the orchestration layer, not in an instruction to the model.

## Related

- [ADR-011](./ADR-011-appointment-temporal-boundary.md) — the temporal boundary that governs which operations are legal before and after `appointment.datetime`; the write guards added by this ADR apply the same principle to bot-reachable write operations on past appointments
- [ADR-014](./ADR-014-bot-data-minimisation-and-audit-log.md) — the slim boundary DTO used by the re-anchoring query; the re-anchor uses the same minimised field set as the webhook response
- [ADR-015](./ADR-015-art9-gdpr-minimisation-conversational-flow.md) — the non-persistence of bot-path free text; the re-anchoring query returns structured appointment state, not free-text content
- [ADR-007](./ADR-007-bot-panel-derive-architecture.md) — the derive architecture for the bot's operational view; the re-anchoring read uses the same appointment-centric data model

## Source Code Reference

- `TurnContext.java` *(published — SC-2)* — MDC-based static accessor for the conversational turn identifier; `forensicTurnId()` returns the header-sourced UUID or null for synthetic fallbacks; documents why MDC was chosen over `@RequestScope` (ordering dependency on `RequestContextHolder` binding during the filter phase)
- `TurnCorrelationFilter.java` *(published — SC-2)* — reads `X-Turn-Id` from webhook requests and binds it to the thread via `TurnContext`; HEADER vs SYNTHETIC source discrimination; MDC cleanup in `finally` prevents thread-pool leaks; positioned after `WebhookApiKeyFilter` and before `WebhookSignatureFilter`
- `TurnCorrelationIT.java` 