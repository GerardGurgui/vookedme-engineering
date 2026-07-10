# ADR-015 — Art.9 GDPR Minimisation in Conversational Flow

**Status:** Accepted  
**Date:** 2026-06-05  
**Domain:** privacy / GDPR  
**Editorial:** ADVANCED

> **Engineering Question Answered:** When a language model-driven booking interface receives free-text messages from real users, and those users may spontaneously disclose health or other sensitive personal data while requesting an appointment, how do you ensure that sensitive data is never systematically collected or persisted — and how do you make that guarantee verifiable rather than relying on model behaviour?

---

## Problem

An appointment scheduling bot that communicates with customers over WhatsApp is a conversational interface. Users type naturally. A user asking to book a physiotherapy appointment may say "I have a herniated disc." A user requesting an afternoon slot may mention they have chemotherapy in the morning. A user booking a consultation may disclose a mental health condition unprompted.

These disclosures are user-initiated and unpredictable. EU data protection law (Art.9 GDPR) classifies health data, disability data, and a defined set of other categories as special category personal data requiring a reinforced legal basis for processing. A scheduling platform does not need health data to manage an appointment. It needs a date, a time, a service, and a customer identifier. The presence or absence of a health disclosure does not affect the outcome of a booking.

The risk is not that a user mentions health data in a conversation — the law does not prohibit users from volunteering information. The risk is that the system **solicits, catalogues, or systematically persists** that data without a reinforced legal basis. A system that stores the free text of every bot conversation note in the database — even incidentally, even without intending to collect health data — may be doing exactly this.

The goal is not to make the system incapable of receiving a message that contains health data. That is impossible in a free-text conversational interface. The goal is to make the system incapable of persisting that data, and to make that incapability verifiable.

## Context

The booking workflow passes through two distinct layers:

1. **Orchestration layer** — the conversational workflow that interprets customer messages, manages the conversation flow, and calls the backend. This layer uses a language model. The model's behaviour is probabilistic: it can be instructed, but instructions are not guarantees.

2. **Backend layer** — the Spring Boot application that validates requests, applies business rules, and writes to the database. This layer is deterministic: it executes code, not instructions. Whatever the backend does is what happens, regardless of what the language model intended.

The `Appointment` entity includes a `notes` field. This field was intended for operator use: a business owner or employee may record operational information about an appointment. The field is nullable. The bot's booking request DTO also contained a `notes` parameter, originally included to allow orchestration-layer context to be passed alongside the booking.

Before this ADR, the path from user message to database was:

```
User sends message → LLM interprets → orchestration constructs request → backend writes notes to database
```

If the LLM passed health-related content from the user's message into the `notes` field of the booking request, that content would be persisted to the database. Whether the LLM intended to do this was irrelevant — the backend accepted and stored whatever the orchestration layer sent.

An audit of this path confirmed that the only open vector for Art.9 data reaching the database through the bot was the `notes` field in the bot's booking request.

## Decision

The defence is layered: a behavioural control at the orchestration layer and a categorical enforcement at the backend. Each layer is independent. The combination provides defence in depth.

### Control 1 — Behavioural deflection at the orchestration layer

The system prompt governing the bot's behaviour includes an explicit rule that the model must not solicit, repeat, or persist sensitive personal data. When a user voluntarily discloses sensitive information, the model must redirect the conversation to the operational data needed for the booking without engaging with the disclosure.

The rule is expressed behaviourally rather than legally. It does not cite regulatory references. It instructs the model on what to do, not why:

> Do not solicit, ask about, or follow up on information relating to health, symptoms, injuries, diagnoses, treatments, medication, pregnancy, mental health, or disability.
> You do not need this information to manage a booking.
> If a user mentions it voluntarily:
> — Do not ask follow-up questions about it.
> — Do not repeat it back.
> — Do not use it to influence availability or booking decisions.
> — Do not include it in any tool call or in the notes field.
> Redirect the conversation immediately to the operational data needed for the booking.

This control is behavioural and probabilistic. A language model that is instructed not to do something will generally comply. It may not comply in every case. This is the nature of language model behaviour: instruction-following is not a guarantee.

Control 1 is the first layer. It reduces the frequency with which sensitive data would reach the notes field. It is not the compliance boundary.

### Control 2 — Deterministic non-persistence at the backend

The backend enforces a categorical rule: the `notes` field received in any bot booking request is discarded unconditionally. The assignment is not conditional on the content of the field. The value is always set to null before the appointment entity is persisted.

This control applies only to the bot path. The administration panel path — where a business owner or employee may add notes to an appointment — is not affected. Operators adding notes to appointments do so under a different legal basis and under their own responsibility as data controllers for their business.

**Why null, not a content filter:**

The alternative — filter the notes value through a keyword or pattern classifier before deciding whether to store it — was considered and rejected.

A content filter is probabilistic. It will produce false positives: "sports massage" might trigger a health keyword filter. It will produce false negatives: "I hurt myself last week" might not trigger a health keyword filter. A filter can be improved over time but cannot be made reliable. More fundamentally, a filter attempts to distinguish medical text from non-medical text — a classification problem whose failure modes directly correspond to compliance failures.

Setting the field to null is categorical. The system does not attempt to classify the content. It does not persist any free text from the bot path. This property is not dependent on the quality of any classifier, the breadth of any keyword list, or the linguistic patterns of health disclosures. It is a property of the code path: the field is null after the assignment, regardless of what was in it before.

When the system is asked to demonstrate compliance — by a regulator, in a dispute, or in an internal audit — a categorical guarantee is defensible where a statistical claim is not.

### Control 3 — Verification test battery

A suite of integration tests runs the full booking path against a real PostgreSQL instance. The test cases are designed to verify the non-persistence property across the range of inputs that the system may receive in production.

The test battery covers three categories:

**Category 1 — Special category data (Art.9):** inputs that explicitly contain health, disability, or other Art.9 category data. Eight test cases: cancer, pregnancy, HIV, antidepressants, herniated disc, a child's autism diagnosis, anxiety, wheelchair use.

**Category 2 — Benign operational context:** inputs that are operationally relevant but do not contain sensitive data. Four test cases: scheduling preference ("I prefer the afternoon"), tardiness notice, a request to call ahead, bringing a companion.

**Category 3 — Edge cases:** null input, whitespace-only input.

Each test case asserts two properties:

1. **Non-persistence:** `appointment.getNotes() == null` after re-fetching the appointment from the database. The assertion is on the persisted value, not on an in-memory object. This distinguishes a test that verifies the code path from a test that verifies object construction.

2. **Booking success:** the appointment was created successfully. Non-persistence must not break the booking. A system that prevents health data persistence by rejecting all bookings with notes would not be compliant — it would just be broken in a different way.

The benign category cases asserting null for inputs like "I prefer the afternoon" are intentional. They demonstrate the categorical property: the system discards all bot-path notes, not only the ones that contain recognisable health data. This is the stronger claim, and it is verifiable.

### Scope and boundaries

The decision applies exclusively to the bot's booking path. The affected field is `notes` in the bot booking request DTO.

The following are out of scope:

- **Operator notes via the panel:** a business owner or employee adding notes to an appointment through the administration panel operates under a different legal basis. This path is not modified by this decision.
- **Service notes on the customer record:** a separate field on the customer entity for recording service preferences is a panel-managed field subject to separate treatment.
- **Conversational logs:** messages sent over WhatsApp are logged by the messaging infrastructure for a bounded period before being purged. This is a retention question governed separately; it does not affect what the backend persists.

## Alternatives Considered

| Option | Why Rejected |
|---|---|
| Content filter on the notes field | Probabilistic: false positives and false negatives exist by definition. The failure modes of a classifier correspond directly to compliance failures. A categorical guarantee is more defensible than a statistical one. |
| Block all bookings that include notes | Prevents persistence but breaks the booking for any user who includes any context in their message. This is not a proportionate response and would degrade the product significantly. |
| Rely entirely on the orchestration layer behavioural rule | The orchestration layer uses a language model. Language model behaviour is probabilistic. A rule that depends on the model's compliance for its correctness is not a verifiable compliance guarantee. |
| Upfront privacy notice before every conversation | Considered as an additional transparency measure. Rejected for general deployment because it adds friction to every conversation including the majority that contain no sensitive data, and because the combination of deflection behaviour and backend non-persistence already provides the structural guarantee. This measure remains available as an optional addition for business types where it would be appropriate. |

## Consequences

### Positive

- The system has a categorical, verifiable guarantee: no free text from the bot booking path reaches the database. This guarantee is independent of model behaviour, orchestration workflow configuration, and keyword lists.
- The guarantee is verified by automated tests running against a real database. The tests do not mock the persistence layer; they verify the actual persisted state.
- Both layers of the defence are independently effective. A model that violates the behavioural rule is contained by the backend. The model's probabilistic behaviour does not affect the compliance guarantee.
- The scope is minimal: only the bot booking path is constrained. The panel path for human operators, with its different legal basis, is unchanged.

### Negative

- Any context a user provides in the notes field of a bot booking request is permanently discarded. If a user provides genuinely useful operational context — "I will be five minutes late" — this context is not persisted. The operator must ask if they need it. This is an accepted trade-off: the categorical guarantee requires discarding all bot-path free text, not only the sensitive portion.
- The behavioural rule at the orchestration layer may not always be followed by the language model. Occasional non-compliance is expected and is handled by the backend layer; it does not affect correctness but may produce moments of conversational incongruity before the backend clears the note.

### Neutral

- Residual exposure exists in conversational logs at the infrastructure layer. This exposure is bounded by the log retention window and represents an accepted residual risk for a deployment not serving regulated health sectors.

## Engineering Principle

The compliance boundary of a system is the layer whose behaviour is deterministic. When a language model is part of the system, the model is not the compliance boundary — it is an interpretive layer whose outputs the compliance boundary must be prepared to handle. Building a privacy guarantee on model instruction-following is building the guarantee on a probabilistic foundation. Building it on code that executes the same way regardless of the model's output is building it on a deterministic foundation. The two are not equivalent. The backend is the compliance boundary. The model can assist compliance; it cannot be the guarantee.

## Related

- [ADR-014](./ADR-014-bot-data-minimisation-and-audit-log.md) — the slim boundary DTO that restricts what data the model receives in the first place; and the audit log that records that the appointment was created, without recording the notes content
- [ADR-012](./ADR-012-conversational-coherence.md) — conversational state re-anchoring; the same constraint (notes are not persisted) applies when the re-anchoring query returns appointment context to the model
- [ADR-003](./ADR-003-hybrid-audit-strategy.md) — the three-layer audit architecture; the audit log records that an appointment was created by the bot, not the content of any field that was discarded

## Source Code Reference

*Populated when source code is present.*

- `AppointmentService.createFromBot()` — the bot booking path; contains the unconditional `.notes(null)` assignment that enforces the non-persistence guarantee
- `BotNotesMinimisationIT` — the integration test suite; runs fourteen test cases against a live PostgreSQL instance; verifies null persistence and booking success for every case
- The orchestration layer system prompt — contains the behavioural deflection rule as an explicit instruction governing model behaviour for health and sensitive data disclosures
