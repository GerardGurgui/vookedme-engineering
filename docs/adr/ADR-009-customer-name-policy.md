# ADR-009 — Customer Name Policy — Unicode and Input Validation

**Status:** Accepted  
**Date:** 2026-06-10  
**Domain:** customer  
**Editorial:** ADVANCED

> **Engineering Question Answered:** When a messaging interface creates customer records automatically using names derived from the user's messaging profile, and the backend validates those names against a locale-specific character pattern, how do you resolve the contradiction that arises when legitimate international names are structurally rejected — and what is the correct validation boundary for a CRM that must serve a multilingual customer base?

---

## Problem

An appointment scheduling system that accepts bookings through a messaging interface must resolve the question of who is booking. The mechanism used is the customer's messaging profile name — the display name they have set on the messaging platform. This name is passed to the backend as part of the booking request and used to create or identify the customer record.

The backend validates incoming customer names against a character set pattern. The original pattern accepted only Latin characters with Spanish diacritics: `^[a-zA-ZáéíóúÁÉÍÓÚñÑüÜçÇ\s'-]+$`. This pattern was chosen to ensure that names stored in the CRM were clean and human-readable, excluding emoji, numeric strings, and symbols.

The pattern works correctly for customers whose names fit within Spanish orthography. It produces a systematic failure for any other customer.

### The internationalisation failure

A customer whose name contains a character outside the accepted set triggers a validation error before the booking reaches the business logic layer. The rejection happens at the DTO validation stage — before the system checks whether the customer already exists in the database, before any appointment logic runs.

The failure mode this creates can be called the **internationalisation paradox**: a legitimate customer with a name that uses a valid character in their own language — `ã`, `ü`, `ç` in combinations the pattern does not accept, characters from non-Latin alphabets, diacritics used in Scandinavian, Eastern European, or East Asian names — is rejected at the API boundary. The booking fails. The customer gets no appointment. The business loses the booking. The system has incorrectly determined that this person's name is invalid.

This is not a rare case affecting unusual names. It affects any name outside a narrow Spanish character set: `João`, `Müller`, `François`, `Søren`, `Nguyễn`, `Αλέξης`. The customer base of a booking platform is not bounded by any single national orthography.

A compounding effect: the validation failure occurs even for returning customers. If a customer already exists in the database with a verified valid name, but the messaging interface sends their current profile name (which happens to contain a rejected character), the booking fails for a known customer — the system never reaches the logic that would look up their existing record.

### The name quality problem

The messaging interface does not guarantee that the profile name is a person's real name. Profile names on messaging platforms are user-set and may contain values that are operationally useless for a CRM: emoji strings, device descriptions, generic labels, names of other people ("Mum's phone"), or strings in formats that, while human-readable, are not appropriate as a booking name in a business's appointment system.

The system had no mechanism to distinguish a genuine personal name from a profile name that happens to pass the character pattern.

### Two failures with the same origin

Both failures — rejecting international names and accepting low-quality profile names — originate from the same design choice: treating the messaging interface's profile name as a direct input to the CRM without validation of its suitability as a booking identity.

## Context

The customer record creation flow follows a consistent path: when a booking arrives from the messaging interface, the system looks up the customer by phone number within the business's scope. If the customer exists, the existing record is used. If the customer does not exist, a new record is created using the name provided in the booking request.

The `customers.name` column is nullable by design. A customer can be created without a name, and their name can be updated in a later operation. The booking creation path should not fail because of a name validation problem when the customer phone already identifies the record.

## Decision

### 1. Expand the character set to Unicode

The character pattern for customer names is changed from the locale-specific Spanish set to a Unicode-aware pattern:

```
^[\p{L}\p{M}\s'-]+$
```

`\p{L}` accepts any Unicode letter from any alphabet. `\p{M}` accepts combining diacritical marks. The pattern continues to reject emoji, numeric characters, and symbols — including characters with XSS relevance (`<`, `>`, `&`). Length constraints (`minimum 2, maximum 50 characters`) are retained.

This change is made in the backend validation layer. It is the source of truth for what constitutes a valid name; any client-side pre-filtering must mirror this pattern to avoid asymmetric validation.

### 2. Differentiate returning and new customers at the messaging interface

Rather than sending the profile name on every booking request regardless of whether the customer is known, the messaging interface adopts a differentiated strategy:

**Returning customer with an existing validated name:** the interface sends a null value for the name field. The backend ignores the null — Bean Validation does not apply `@Size` or `@Pattern` to null values — and uses the name already stored for this customer. The profile name on the messaging platform is irrelevant to the booking.

**New customer with a plausible profile name:** the interface presents the profile name to the customer for confirmation before sending it. The customer either confirms the name or provides a different one. The name sent in the booking request is one the customer has actively verified.

**New customer with an implausible profile name:** the interface detects that the profile name does not look like a personal name — it is too short, contains only numeric characters or emoji, or matches a structural pattern associated with device descriptions or role labels. The interface asks the customer directly: "What name should I use for your booking?" The name sent in the booking request is the one the customer provided.

Implausible profile name detection uses a deterministic pre-filter applied at the messaging interface level, mirroring the backend character pattern. This covers the clear cases without requiring language model inference for every booking.

### 3. Align validation across the platform

The Unicode-aware pattern is the canonical validation definition for person names across the platform. Other input paths that accept customer or user names should use the same pattern to avoid creating asymmetric validation surfaces — where the same name is accepted by one entry point and rejected by another.

## Rationale

**Accepting Unicode letters closes the internationalisation paradox.** Any human name expressible in any writing system is accepted. The border between valid and invalid is conceptually coherent: letters and diacritics are accepted; emoji, numbers, and symbols are not.

**Null-on-returning-customer eliminates the returning-customer failure.** Sending a null name for a customer who already has a validated name in the database means the booking path never encounters a name validation problem for an existing customer. The validation problem disappears for the case it should never have applied to.

**Active confirmation for new customers improves CRM quality.** A customer whose profile name is used directly — without confirmation — may find their appointment records attributed to a name they do not recognise, or to a label that makes no sense in a business context. Asking the customer to confirm the name adds one conversational step to first bookings and eliminates the downstream problem of cleaning up unusable names.

**Deterministic pre-filtering at the interface reduces operational cost.** Routing every profile name through a language model to determine plausibility would add latency and inference cost to every booking. Deterministic structural checks handle the obvious cases — emoji-only strings, pure numeric sequences, strings shorter than the minimum — without any model involvement. Language model inference is reserved for cases where structure alone cannot determine plausibility.

**The backend constraint is the authority.** The interface-level pre-filter exists to improve user experience and reduce API round trips; it is not the enforcement layer. The backend pattern is the definition of validity; the interface mirrors it to avoid sending names that will be rejected, not to replace the backend's judgment.

## Alternatives Considered

| Option | Why Rejected |
|---|---|
| Accept all strings without pattern validation | Eliminates the internationalisation problem but creates an XSS vector and allows the CRM to accumulate names that are operationally useless or actively harmful. The pattern constraint serves a real purpose; removing it entirely trades one failure mode for several others. |
| Expand to Latin Extended only | Adds accented characters from Western European languages while excluding all non-Latin scripts. Addresses the most common cases in a European market but embeds the same structural assumption — that the valid customer name space is defined by Western European orthography — at a wider boundary. The Unicode approach requires no such boundary. |
| Transliterate non-Latin names to ASCII | Convert incoming names to their Latin-alphabet approximations before storage. Loses information, produces incorrect or culturally inappropriate representations, and requires a transliteration library with non-trivial accuracy constraints. The original name is the correct name; storing a transliteration is storing a different thing. |
| Require manual name entry for all new customers | Eliminate profile name use entirely; always ask the customer for their name. Correct in principle but adds conversational friction to every first booking, even when the profile name is accurate. The confirmation approach described in the Decision section achieves the quality goal while minimising friction for the common case where the profile name is usable. |
| Validate name at a separate enrichment step after customer creation | Create the customer record with whatever name arrives, then validate and flag it for manual correction. Separates creation from validation at the cost of always needing a manual correction loop. Better to get the name right at creation time when the customer is present in the conversation. |

## Consequences

### Positive

- Bookings from customers with international names succeed. The structural rejection of valid human names is eliminated.
- Returning customers are not affected by profile name changes on the messaging platform. Their booking path uses the validated name already in the database.
- New customer names are reviewed before being stored, improving the baseline quality of the CRM from the first interaction.
- No new XSS attack surface: the Unicode letter pattern continues to exclude `<`, `>`, `&`, and other characters with injection significance.

### Negative

- If a returning customer has changed their profile name to a different real name and would like the update reflected in their booking records, the system does not detect this. The null-on-returning-customer strategy deliberately ignores profile name changes for existing customers. Detecting and offering optional name updates is a future enhancement.
- A very small number of legitimate names contain characters that look cosmetically unusual in certain rendering contexts — for example, sequences of combining diacritical marks. The Unicode pattern permits these; length constraints bound the cosmetic impact.
- The implausible-name detection logic at the interface level requires maintenance when new pattern categories are identified. The backend pattern is authoritative but the interface filter must track it.

### Neutral

- Most customers who book appointments provide only a first name via their messaging profile. Last names are absent in the majority of cases. This is an operational reality, not a system limitation. The CRM stores what the customer provides.

## Engineering Principle

Validation patterns that encode assumptions about which writing systems are legitimate are not neutral technical choices — they are decisions about which users the system will serve. A character pattern that accepts Spanish names but rejects a Portuguese, French, or any non-Latin name is not a name validation pattern; it is a nationality filter that happens to be expressed as a regex. Expanding the pattern to Unicode letters is not a loosening of validation discipline — `\p{L}` is more precise than `[a-zA-Záéíóú...]` because it is defined by a universal standard rather than by a list that someone chose. The engineering discipline is in knowing what you are accepting and why, not in keeping the allowed set small.

## Related

- [ADR-006](./ADR-006-user-identity-model.md) — the `customers` entity and its per-business identity scope; customer names are CRM data held by the business, not authentication credentials
- [ADR-004](./ADR-004-customer-lifecycle-states.md) — the customer lifecycle; name quality affects the usefulness of the customer record across all lifecycle states

## Source Code Reference

*Populated when source code is present.*

- `WebhookAppointmentRequest.java` — the DTO that receives booking requests from the messaging interface; the `customerName` field carries the updated `\p{L}\p{M}\s'-` pattern with a null-permissive annotation that skips validation when the value is absent
- `CustomerService.getOrCreateByPhone()` — the customer resolution path; looks up by phone within business scope before creating a new record; uses the name from the request only for new customers
- `Customer.java` — the customer entity; `name` is nullable, reflecting that a customer can be identified by phone before a valid name is confirmed
