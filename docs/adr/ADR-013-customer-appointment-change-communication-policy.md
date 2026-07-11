# ADR-013 — Customer Appointment Change Communication Policy

**Status:** Accepted — Implementation Pending  
**Date:** 2026-06-21  
**Domain:** communications / privacy  
**Editorial:** ADVANCED

> **Engineering Question Answered:** When a multi-tenant booking system must notify customers of business-initiated changes to their appointments over a consumer messaging channel, how do you determine which customers are eligible to receive those notifications — and how do you build that eligibility model so that it is legally sound, architecturally verifiable, and not coupled to attributes of the booking that have no bearing on communication rights?

---

## Problem

The system's notification logic gated all business-initiated outbound communications on the appointment's `source` field — the value that records whether the appointment was created through the bot channel (customer-initiated) or through the administration panel (operator-initiated). Bot-origin appointments received change notifications; panel-origin appointments did not.

This was never an explicit decision. It was an implicit assumption that hardened into an architectural invariant over time. When a business operator rescheduled a panel-origin appointment, no notification reached the customer. The customer arrived at the original time with no awareness that the appointment had moved.

The root cause is a category error: `source` is operational provenance. It records how the appointment entered the system. It is write-once and immutable. Whether a customer should receive a notification about a change to their appointment has nothing to do with how the appointment was originally created — it depends on whether a legitimate communication channel to that customer has been established and is currently active. These are different concepts that the initial implementation conflated.

The secondary problem is legal. Communicating with a panel-origin customer who has never initiated contact with the business requires an established legal basis. A customer who walked into a salon, gave their phone number, and had an appointment booked on their behalf has not initiated a messaging relationship. Sending them unsolicited WhatsApp messages without establishing a legal basis is a potential violation of GDPR Art.6 and Art.25. At the same time, denying them change notifications when they have a legitimate expectation of being kept informed about their appointment is a failure of the service's core promise. The policy must resolve both sides of this tension.

---

## Context

The booking platform operates in two distinct modes for customer appointments:

- **Bot-origin:** the customer contacted the business on WhatsApp, the bot managed the interaction, and the appointment was created as a result. The customer's inbound contact is an unambiguous positive action that establishes a communication channel.
- **Panel-origin:** a business operator created the appointment on the customer's behalf. The customer may have been present at the time (an in-person booking), contacted the business by phone, or been booked from a contacts list. No WhatsApp interaction has occurred. The communication channel has not been established by the customer's own action.

The service name in an outbound message may contain sensitive information in verticals where the nature of the service implies a health condition or disability. This is an Art.9 GDPR consideration that intersects with the communication policy — the content of notifications must be structured so that service names in sensitive verticals do not reveal special-category personal data to parties who should not have access to it.

Production WhatsApp messaging requires pre-approved message templates. The WhatsApp Business Platform permits two categories relevant here: UTILITY (transactional, service-related) and MARKETING (promotional). A booking change notification is by definition UTILITY. Production deployment requires registered templates; the initial self-hosted WhatsApp integration that operates on free-form messages is not an acceptable production channel for this reason, among others.

---

## Decision

Ten decisions govern this policy. They are recorded as they were made. The policy covers all business-initiated communications about appointment changes, across all channels (immediate notifications and reminders).

---

### 1. Source ≠ Eligibility

Appointment `source` (BOT / PANEL / IMPORT / API) is **operational provenance**: it records how the appointment entered the system. It is write-once, immutable, and valid for operational classification.

Communication eligibility is a **separate concept**, governed by a per-customer outbound legitimacy state (`channel_legitimacy_status`). The legitimacy state is mutable. It can be established, revoked, and re-established over the customer's lifecycle.

Bot-origin appointments *trigger the establishment* of `channel_legitimacy_status` (the customer's inbound message is itself the legitimating action). But `source` is not the gate. The gate is always `channel_legitimacy_status`.

This principle is elevated to a governance invariant: the eligibility gate must never be re-conflated with appointment source. The approximately ten outbound notification paths migrate to the new eligibility concept; the broader uses of `source` for operational classification are unaffected.

---

### 2. The Right to Be Informed of Material Changes

A customer has the right to be informed of material changes that a business introduces to their appointment, **given a legitimate channel exists**.

Legal basis: accuracy of personal data (Art.5.1.d GDPR), fairness and transparency (Art.5.1.a and Art.12 GDPR), and performance of a contract (Art.6.1.b GDPR).

The right to be informed is conditioned on channel legitimacy, not on appointment source. A panel-origin customer with an established legitimate channel has the same right to be informed as a bot-origin customer.

---

### 3. Definition of "Material Change"

Material means any business-initiated change that reasonably affects the customer's main plans or expectations. The following events are material:

- Rescheduling (any change to date or time)
- Cancellation
- Revocation or reactivation
- Change of assigned professional, when the customer booked with a specific person
- Substantive change of service
- Change of location (multi-location deployments)

The following are **not material** and do not trigger notifications: appointment notes, duration in isolation, price changes, internal status fields.

Service change is material by the general rule (substantive change to what the customer is expecting) — not conditionally based on what information is exposed in the notification. This avoids a circular dependency between the materiality rule and the content filtering rule: whether a change is material is determined before considering what can be communicated about it.

---

### 4. Channel Legitimacy Model

The `channel_legitimacy_status` field is per-customer and per-phone-number. It is inherited by all appointments associated with that customer. The eligibility gate evaluates this field, not appointment source.

**How legitimacy is established:**

- **Bot-origin:** the customer's own inbound WhatsApp message is an unambiguous positive action. Legitimacy is set automatically at the moment of first contact.
- **Panel-origin:** the business operator attests (with a permanent log record) that they have informed the customer out-of-band about how they will be contacted, satisfying the Art.13 GDPR transparency obligation. Explicit opt-in from the customer is not required — the legal basis is contract performance (Art.6.1.b), not consent (Art.6.1.a).

**Default deny (Art.25.2 GDPR):** the initial state of `channel_legitimacy_status` is null / false — ineligible. The backend suppresses all outbound business-initiated communications, including reminders, until legitimacy is explicitly established.

**Temporal precedence:** legitimacy must be established before the first message is sent. A successfully delivered message does not retroactively establish legitimacy; it is evidence that a violation occurred.

**Legitimation log:** every establishment of legitimacy is recorded with a UTC timestamp, the identifier of the staff member who performed the attestation, and the attestation text. This log is append-only and permanent.

---

### 5. Message Content Constraints

Notification content follows the structure of a UTILITY template on the WhatsApp Business Platform: a fixed message skeleton with bounded transactional variables.

**Required in every change notification:**
- A change indicator (explicitly signalling that something about the appointment changed — this prevents a "partial signal" failure where the customer receives an updated reminder but no indication that a change occurred)
- The new appointment state (date, time, service as applicable)
- Art.13 GDPR transparency information on first contact with a panel-origin customer

**Configurable per business:**
- Business name, date, time, service name (subject to Art.9 handling in sensitive verticals), employee name
- Selection among pre-approved template variants

**Prohibited in all notifications:**
- Promotional or marketing content (Art.6.1.a GDPR, Spanish ePrivacy legislation)
- Special-category personal data in clear text (health conditions, diagnoses, or other Art.9 data — governed by the Art.9 minimisation principle)
- Price or financial information

The complete change diff is not required. The change indicator alone is sufficient for the customer to know that a change occurred and to understand the new state. This design is compatible with the WhatsApp UTILITY template model, which does not permit arbitrary free-form content.

---

### 6. Global Floor vs Per-Tenant Configuration

A non-disableable global floor governs the events that must always generate a notification. This floor covers material-critical changes — rescheduling, cancellation, and events whose omission would undermine the customer's core expectations.

Above the floor, tenants may configure:
- Whether borderline or optional events generate notifications
- Reminder timing (24-hour and 1-hour pre-appointment reminders)
- Which approved template variant to use

Optional events default to on. The global floor cannot be disabled by tenant configuration.

The customer opt-out (see decision 7) takes precedence over all tenant configuration. A business cannot override a customer's opt-out by configuring notifications on.

---

### 7. Opt-Out Architecture

**Total channel opt-out:** a customer who signals opt-out (via a standard stop keyword or equivalent) has `channel_legitimacy_status` set to false, per-customer. This takes effect immediately and suppresses all subsequent business-initiated outbound communications, including reminders. The business is notified that the customer is no longer contactable via this channel.

**Reversibility is dual and depends on the reason for the deny state:**

- **Default / never-legitimated:** reversible by a new business attestation (panel-origin) or by a new customer inbound message (bot or panel-origin)
- **Explicit customer opt-out:** reversible **only** by a positive action from the customer — a new inbound message or an explicit re-opt-in. Business attestation cannot override an explicit customer opt-out. This is a hard constraint: re-attestation by the business after an explicit opt-out would circumvent the customer's Art.21 GDPR right to object.

This precedence rule is elevated to an explicit policy invariant: **the customer's explicit opt-out prevails over any subsequent action by the business; reactivation after explicit opt-out requires positive action from the customer**.

---

### 8. Communications Accountability Log — Metadata Only

Every business-initiated outbound communication is logged with the following fields:

- Customer identifier, business identifier
- UTC timestamp
- Event type (drawn from the material change definitions in decision 3)
- Template identifier
- `channel_legitimacy_status` at the time of sending
- Delivery result
- Opt-out events
- Legitimation origin: how legitimacy was established (`BOT_INBOUND` / `BUSINESS_ATTESTATION` / `CUSTOMER_REACTIVATION`)

**Prohibited from the log:** phone number in clear text, rendered message content, service name where it would reveal special-category data, full message payload.

The prohibition on personal content in the log is structural. The log is immutable (append-only). If personal content were included, it would be permanently retained regardless of any future erasure request. The metadata-only constraint eliminates this conflict.

This log reuses the append-only, metadata-only pattern established for the appointment audit log (ADR-014): immutable by database-level enforcement, actor-attributed, transactional co-write with the operation it records.

---

### 9. Failed Delivery Handling

Silent failure is prohibited. A notification that fails to deliver is a failure of the customer's right to be informed of a material change. The system must respond.

**Material change notifications:**
- Bounded retry, with `channel_legitimacy_status` re-checked on each attempt (do not retry to a customer who has opted out during the retry window)
- If delivery cannot be confirmed after retries, escalate to the business owner as a fallback mechanism — the owner is responsible for contacting the customer out-of-band

**Reminders:** retry with logging; no escalation required (reminders are a convenience, not a right-to-know obligation)

**Explicit distinction between failure types:**
- **Temporary delivery failure** — a transient infrastructure or provider error. Does not modify `channel_legitimacy_status`. Log and retry.
- **Blocked by customer** — the carrier or channel signals that the customer has blocked messages. This is treated as an implicit opt-out: `channel_legitimacy_status` is set to false, subject to the opt-out reversibility rules in decision 7.

These two failure types must be distinguished in code. Treating a temporary failure as an opt-out would incorrectly suppress future communications. Treating a customer block as a transient failure would continue sending to a customer who has signalled they do not want to receive messages.

Delivery callbacks from the WhatsApp provider are the mechanism that makes this distinction possible. The initial self-hosted integration does not support reliable delivery callbacks; this is one of the technical constraints that drives the requirement for the production channel migration.

---

### 10. Channel Requirements

The communication channel must be bidirectional with effective human escalation capability — not merely technically bidirectional.

Bidirectionality serves two purposes: it allows the customer to reply (which opens the messaging session and can re-establish legitimacy), and it ensures that the first contact message can truthfully declare what the customer can do in response (reply, cancel, reach a person). A channel that is read-only in practice cannot meet these requirements.

Human escalation is a production requirement, not an enhancement. The bot can confirm and cancel appointments but cannot autonomously reschedule them. When a customer wants to reschedule in response to a business-initiated change notification, a human must be reachable. The first contact with a panel-origin customer must declare: how to respond, how to cancel, how to reach a person. The customer's right to object (Art.21 GDPR) and the general expectation of human oversight in automated decision-adjacent workflows depends on this.

---

## Invariants and Constraints

**Source ≠ Eligibility invariant:** communication eligibility is always determined by `channel_legitimacy_status` (mutable, per-customer legitimacy state), never by `source` (write-once operational provenance). Bot-origin appointments establish `channel_legitimacy_status`; they are not the gate.

**Opt-out precedence:** the customer's explicit opt-out prevails over any subsequent business action. Re-establishing communication after an explicit opt-out requires a positive action from the customer.

**Failure type distinction:** `TEMPORARY_DELIVERY_FAILURE` does not affect `channel_legitimacy_status`. `BLOCKED_BY_CUSTOMER` sets `channel_legitimacy_status` to false, subject to opt-out reversibility rules.

**Template constraint:** all production business-initiated communications must be expressible as pre-approved UTILITY templates with a fixed body and bounded parameters. No free-form business-authored message body.

**Production channel requirement:** the initial self-hosted WhatsApp integration is not the production channel. The WhatsApp Business API (Meta's official channel) is required for production: it provides UTILITY template approval, reliable delivery callbacks, and the bidirectional session model that underpins decisions 9 and 10.

**Audit log constraint:** the communications accountability log stores metadata only. Phone numbers in clear text, rendered message content, and sensitive service names are prohibited from the log.

---

## Alternatives Considered

**Status quo (source-based gate, no change):** retaining `source == BOT` as the eligibility signal means panel-origin customers with legitimate expectation of being informed continue to receive no change notifications. This does not satisfy the right to be informed and is architecturally unsound because `source` is immutable and cannot represent a state that changes over the customer's lifecycle. Rejected.

**Expanding the source gate (treat more sources as eligible):** `source` is write-once at appointment creation. It cannot represent a customer's current contactability. Extending the source-based gate to cover more cases would expand the category error rather than correct it. Rejected.

**Mandatory explicit opt-in from the customer:** would require customers to actively opt in before any notification is sent, even about changes to their own appointment. The legal basis for change notifications is contract performance (Art.6.1.b GDPR), not consent — opt-in is not required. Mandatory opt-in would create unnecessary friction and would effectively prohibit change notifications for panel-origin customers who cannot be contacted to request opt-in. Rejected.

**Opt-out as the only state (no positive legitimacy establishment):** would leave panel-origin customers ineligible until they opted out of something they were never notified about. Circular and non-functional. Rejected.

**Business re-attestation overriding an explicit customer opt-out:** would allow businesses to restore communications to a customer who has explicitly signalled they do not want them. This circumvents the customer's Art.21 GDPR right to object. Rejected as a hard constraint.

**Free-form message body for business-initiated notifications:** free-form text is not permitted under the WhatsApp UTILITY template model. It also creates uncontrollable surfaces for promotional content and sensitive data disclosure. Rejected; the template constraint is structural.

**Best-effort delivery with silent failure:** silent swallow of delivery failures is incompatible with the right to be informed of material changes. A notification that is silently swallowed means the customer is not informed. Rejected.

**Unidirectional channel:** a channel that cannot receive customer responses cannot meet the human escalation requirement or the Art.21 right to object. Rejected.

---

## Consequences

### Positive

- The architectural confusion between operational provenance and communication eligibility is resolved. The source field retains its correct meaning (operational classification). The legitimacy state carries the correct semantics (communication eligibility).
- Panel-origin customers with a legitimate communication channel receive the same change notifications as bot-origin customers. The service delivers on its core promise for all customer populations.
- The eligibility model is legally grounded: default deny, legitimation log, business attestation, and opt-out with explicit reversibility rules address Art.5, Art.6, Art.12, Art.13, Art.21, Art.25, and Art.9 considerations.
- The audit log provides accountability without creating erasure conflicts: metadata-only, no personal content.
- The opt-out model correctly distinguishes between customers who were never legitimated and customers who explicitly withdrew consent. Business actions cannot override customer-explicit opt-outs.

### Negative

- The legitimacy state, attestation workflow, opt-out ingestion, and communications accountability log must be built. This is substantial greenfield work. Until it is built, the current source-based gate remains in place as the operational baseline.
- Panel-origin communication requires a business attestation step before the customer can be notified. This adds friction to the operator workflow for businesses that bring existing customer relationships to the platform.
- The legitimacy gate must be toggled on only after the full legitimacy infrastructure is in place. Operating with the gate toggled on before the attestation and opt-out workflows exist would lock out all notifications.

### Neutral

- The right-to-be-informed framework applies to change notifications and reminders. Booking confirmations (the first acknowledgment of a new appointment) raise additional considerations about first-contact disclosure and are governed by the same legitimacy model but with stricter Art.13 requirements on content.
- Deferred items (WhatsApp template format, audit log retention, per-category granular opt-out, IMPORT/API source handling) do not affect the policy decisions recorded here and can be resolved independently.

---

## Implementation Status (as of 2026-06-28)

The `channel_legitimacy_status` field, supporting columns, opt-out ingestion endpoint, and business attestation endpoint are implemented and deployed. The service layer that governs legitimacy establishment, revocation, and re-establishment is in place.

The legitimacy gate is implemented but toggled off. The existing source-based gate (`source == BOT`) remains the active notification gate. The toggle-on requires verifying the full legitimacy workflow in a staged environment and completing the remaining production prerequisites. Until the gate is toggled on, the behaviour of the notification system is unchanged from the pre-ADR baseline.

What remains to be built: the communications accountability log (decision 8), the audit log retention policy, the WhatsApp Business API migration with UTILITY templates, delivery callback handling, human escalation capability, and the Art.9 service-name generalisation for sensitive verticals.

---

## Production Prerequisites

The following must be completed before production deployment. They follow from the policy decisions above; they are not new decisions.

1. **WhatsApp Business API channel** — official Meta channel with UTILITY template approval and delivery callbacks; required by the template constraint and decision 9
2. **Human escalation capability** — a reachable human operator path for customers who cannot or do not want to interact with the bot; required by decision 10
3. **Privacy documentation update** — the public privacy notice must accurately describe the panel-origin customer population and the business attestation mechanism; currently describes only bot-origin (customer-initiated) users
4. **Legitimacy gate activation** — toggle on the `channel_legitimacy_status` gate in all outbound paths, replacing the source-based gate; requires verifying attestation and opt-out workflows end-to-end
5. **Audit log retention policy** — the decision of how long to retain the communications accountability log is deferred but must be made before production; absence of a retention policy blocks the privacy documentation reconciliation
6. **Data Protection Impact Assessment** (Art.35 GDPR) — required before production given the nature of the data processing
7. **Art.9 service-name generalisation** — for verticals where a service name implies a health condition, the service name must be generalised or omitted in outbound notifications before accepting bookings in those verticals

---

## Deferred Items

The following are explicitly outside the scope of this ADR and will be resolved separately:

- WhatsApp template format details and 24-hour session window mechanics
- The retention decision for the communications accountability log
- Auto-notification suppression (whether a business can suppress a confirmation it initiated)
- IMPORT and API origin handling in the legitimacy model
- Per-category granular opt-out (beyond the total channel opt-out)
- Customer archive and anonymisation interaction with the legitimacy state

---

## Engineering Principle

Communication eligibility and booking provenance are different properties of a customer's relationship with a business. Conflating them — using one as a proxy for the other — creates an architecture that is wrong by construction: it can never correctly represent a customer whose eligibility changes over time, because the proxy is immutable and the thing it stands in for is not. When two concepts have different lifecycles, different mutability properties, and different legal significance, they must be modelled separately. An architecture that combines them is not simplified — it is wrong in ways that are difficult to untangle later, because the confusion is embedded in the data model and in every conditional that reads from it.

---

## Related

- [ADR-003](./ADR-003-hybrid-audit-strategy.md) — the three-layer audit architecture; the metadata-only communications accountability log (decision 8) follows the audit pattern established here
- [ADR-014](./ADR-014-bot-data-minimisation-and-audit-log.md) — the appointment audit log; the communications log reuses the append-only, metadata-only, transactional co-write pattern
- [ADR-015](./ADR-015-art9-gdpr-minimisation-conversational-flow.md) — Art.9 minimisation in the conversational flow; the same principle (special-category data must not be persisted or exposed) applies to service names in outbound notifications
- [ADR-004](./ADR-004-customer-lifecycle-states.md) — customer lifecycle states; the legitimacy state interacts with customer archive and anonymisation workflows, to be coordinated when those workflows are implemented
- [ADR-012](./ADR-012-conversational-coherence.md) — conversational coherence; the bot's state re-anchoring and the outbound notification system share the same appointment state as their source of truth

## Source Code Reference

- `OutboundLegitimacyGate.java` *(published — SC-4)* — the default-deny gate; `evaluate(Long customerId)` performs a fresh database read at dispatch time; `Boolean.TRUE.equals(customer.getChannelLegitimacyStatus())` is the only eligible branch; null and false are both ineligible; feature-flag gated (`app.legitimation.gate-enabled`) with default OFF pending full activation
- `LegitimacyDecision.java` *(published — SC-4)* — the gate return value; a record with `(boolean eligible, ReasonOfDeny reason)`; compact constructor enforces `eligible=true ⟹ reason=null`; factory methods `allow()` and `deny(reason)` avoid collision with the auto-generated `eligible()` accessor
- `CustomerLegitimacyService.java` *(published — SC-4)* — the sole write owner for channel legitimacy; four public methods (`legitimateFromBot`, `optOut`, `reactivateByCustomer`, `attest`);