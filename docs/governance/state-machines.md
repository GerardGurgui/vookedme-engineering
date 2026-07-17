# Governance: State Machines

**Domain:** appointment, schedule  
**Status:** Published — AX-3  
**Date:** 2026-07-14

> Canonical FSM definitions and transition tables for all lifecycle entities in the system. Each figure answers one engineering question and is grounded in the corresponding ADR. Implementation ground truth takes precedence over ADR text where discrepancies are explicitly documented.

---

## Contents

- [Figure 1 — Appointment lifecycle](#figure-1--appointment-lifecycle)
  - [Figure 1.a — Modo Recepcionista (APPROVAL\_REQUIRED)](#figure-1a--modo-recepcionista-approval_required)
  - [Figure 1.b — Modo Autónomo (AUTO\_CONFIRM)](#figure-1b--modo-autónomo-auto_confirm)
  - [Shared state semantics](#shared-state-semantics)
  - [Transition table](#transition-table)
- [Figure 2 — BlockedSlot approval lifecycle](#figure-2--blockedslot-approval-lifecycle)

---

## Figure 1 — Appointment lifecycle

The appointment lifecycle FSM has **six states** regardless of operating mode. What changes per mode is the initial state that the Bot assigns on creation, and the cancellation path the Bot follows. The mode is a per-business configuration (`BotBookingMode` field on `Business`).

**Three bot modes exist (`BotBookingMode.java`).** Two produce `Appointment` rows and are shown below. The third — `TRIAGE` (Modo Supervisado) — produces a `BotRequest` entity instead and does not write `Appointment` rows. It is therefore out of scope for this document.

| Mode | Bot creates | Bot cancels (CONFIRMED) | Slot-blocking | Status |
|---|---|---|---|---|
| `APPROVAL_REQUIRED` | `PENDING` — owner approves from panel. Timeout: `botApprovalTimeoutHours` (default 2 h); `PendingApprovalTimeoutJob` cancels on expiry with reason `TIMEOUT_BOT_APPROVAL`. | `CANCELLATION_REQUESTED` — owner decides (ADR-011 PFT-1) | ✅ Yes — `PENDING` blocks slots via `status IN ('PENDING','CONFIRMED')` queries | ✅ LIVE |
| `AUTO_CONFIRM` | `CONFIRMED` — directly; V66 auto-assigns first available employee | `CANCELLED` — direct, no gate (ADR-011 PFT-6) | ✅ Yes | ✅ LIVE (V64 default) |
| `TRIAGE` | No `Appointment` — creates `BotRequest` only | — | No | ⏸️ Not implemented |

---

### Figure 1.a — Modo Recepcionista (APPROVAL\_REQUIRED)

![Figure 1.a — Appointment lifecycle: Modo Recepcionista (APPROVAL_REQUIRED)](./Figure%201%20-%20Appointment%20FSM-selection.png)

> **Figure 1.a** — *Appointment lifecycle — Modo Recepcionista (`APPROVAL_REQUIRED`)*: In this mode the bot acts as a receptionist — it books a slot as `PENDING` and the owner holds the approval gate. The `PENDING` state already blocks calendar availability. If the owner does not decide within `botApprovalTimeoutHours` (default 2 h), `PendingApprovalTimeoutJob` cancels the appointment automatically. Bot-initiated cancel on a `CONFIRMED` appointment goes to `CANCELLATION_REQUESTED` (not direct cancel); bot-initiated cancel on a still-`PENDING` appointment cancels directly (customer withdrew before owner decided). See [ADR-011](../adr/ADR-011-cancellation-policy.md).

---

### Figure 1.b — Modo Autónomo (AUTO\_CONFIRM)

![Figure 1.b — Appointment lifecycle: Modo Autónomo (AUTO_CONFIRM)](./Figure%201b%20-%20Appointment%20FSM%20-%20Autonomo-selection.png)

> **Figure 1.b** — *Appointment lifecycle — Modo Autónomo (`AUTO_CONFIRM`)*: In this mode the bot has full booking authority — it creates `CONFIRMED` directly and V66 auto-assigns the first available employee. The owner receives an informational notification but no action is required. Bot-initiated cancel bypasses the approval gate and goes directly to `CANCELLED`. This is the V64 migration default for all existing businesses. See [ADR-011](../adr/ADR-011-cancellation-policy.md).

---

### Shared state semantics

Both modes share the same six states. The mode controls which actor initiates which transition — it does not add or remove states.

| State | Meaning | Terminal? |
|---|---|---|
| `PENDING` | Awaiting owner confirmation. Primary creation state in `APPROVAL_REQUIRED`. Blocks calendar availability. No creation path in `AUTO_CONFIRM` (legacy only). | No |
| `CONFIRMED` | Appointment is confirmed and slot is blocked. Primary creation state in `AUTO_CONFIRM`; reachable from `PENDING` via owner approval in `APPROVAL_REQUIRED`. | No |
| `CANCELLATION_REQUESTED` | Cancellation has been requested and awaits owner decision. Initiating actor varies by mode: Customer / Bot in `APPROVAL_REQUIRED`; EMPLOYEE in `AUTO_CONFIRM`. Silence is not approval — see ADR-011 PFT-1. | No |
| `CANCELLED` | Appointment cancelled — by owner, by system timeout (`PendingApprovalTimeoutJob` or `CancellationRequestTimeoutJob`), or by bot (direct in `AUTO_CONFIRM`). | Yes |
| `COMPLETED` | Appointment occurred and was marked as attended. Only reachable after `appointment.datetime` (Closure Plane). | Yes |
| `NO_SHOW` | Appointment slot passed and customer did not attend. Only reachable after `appointment.datetime` (Closure Plane). | Yes |

**Temporal boundary:** `appointment.datetime` partitions the lifecycle. `COMPLETED` and `NO_SHOW` are only reachable after this boundary (Closure Plane). All other active transitions occur before it (Operational Plane). See [ADR-011](../adr/ADR-011-cancellation-policy.md).

---

### Transition table

Grounded in `AppointmentService.validateStatusTransition()` (lines 5280–5292) and the mode-specific creation/cancel paths.

| From | To | Actor | Notes |
|---|---|---|---|
| (creation) → `PENDING` | Customer / Bot | `createFromBot` — `APPROVAL_REQUIRED` mode only. Blocks calendar. |
| (creation) → `CONFIRMED` | Customer / Bot | `createFromBot` — `AUTO_CONFIRM` mode. V66 auto-assigns employee. |
| (creation) → `CONFIRMED` | OWNER / ADMIN | `create()` panel path. Always `CONFIRMED` regardless of bot mode. |
| `PENDING` → `CONFIRMED` | OWNER / ADMIN | Approval from panel (`approveBotPending`). |
| `PENDING` → `CANCELLED` | OWNER / ADMIN | Rejection from panel. |
| `PENDING` → `CANCELLED` | System | `PendingApprovalTimeoutJob` — reason: `TIMEOUT_BOT_APPROVAL`. Default 2 h, configurable per business. |
| `PENDING` → `CANCELLED` | Customer / Bot | `cancelFromBot` — customer withdrew before owner decided (`APPROVAL_REQUIRED` only). |
| `CONFIRMED` → `CANCELLATION_REQUESTED` | Customer / Bot | `cancelFromBot` — `APPROVAL_REQUIRED` mode. CR actor stamped `"BOT"`. |
| `CONFIRMED` → `CANCELLATION_REQUESTED` | EMPLOYEE | `requestCancellationByEmployee`. |
| `CONFIRMED` → `CANCELLED` | Customer / Bot | `cancelFromBot` — `AUTO_CONFIRM` mode. Direct, no gate. ADR-011 PFT-6. |
| `CONFIRMED` → `CANCELLED` | OWNER / ADMIN | Direct cancel from panel. ADR-011 PFT-6. |
| `CONFIRMED` → `COMPLETED` | OWNER / ADMIN | Closure Plane only — after `appointment.datetime`. |
| `CONFIRMED` → `NO_SHOW` | OWNER / ADMIN | Closure Plane only — after `appointment.datetime`. |
| `CANCELLATION_REQUESTED` → `CONFIRMED` | OWNER / ADMIN | Owner rejects the CR (ADR-011 PFT-1: silence is not approval). |
| `CANCELLATION_REQUESTED` → `CONFIRMED` | System | `CancellationRequestTimeoutJob` — timeout elapses (ADR-011 PFT-1). |
| `CANCELLATION_REQUESTED` → `CANCELLED` | OWNER / ADMIN | Owner approves the CR. |

*Exception transitions (legal per FSM whitelist but operationally unusual): `CANCELLED → CONFIRMED` (owner revoke), `CONFIRMED → PENDING` (employee unassign), `PENDING → COMPLETED / NO_SHOW` (closure from pending).*

---

## Figure 2 — BlockedSlot approval lifecycle

![Figure 2 — BlockedSlot approval lifecycle](./Figure%202%20-%20BlockedSlot%20FSM-selection.png)

> **Figure 2** — *BlockedSlot approval lifecycle — five states and calendar-visibility invariant*: How does the `BlockedSlot` state machine enforce the owner approval gate on employee-initiated availability blocks, and which state is the sole gateway to calendar availability? See [ADR-002](../adr/ADR-002-blocked-slot-state-machine.md).

### State semantics

| State | Meaning | Blocks calendar? | Terminal? |
|---|---|---|---|
| `REQUESTED` | Employee has submitted a block request; awaiting owner review | No | No |
| `APPROVED` | Owner has approved; block is effective | **Yes — the only state that blocks bookings** | No |
| `REJECTED` | Owner denied the request | No | Yes |
| `CANCELLED` | Request withdrawn (pre-approval) or block removed (post-approval) | No | Yes |
| `EXPIRED` | `end_datetime` has passed; historical record of a completed block period | No | Yes |

### Transition table

Grounded in `BlockedSlotService.validateTransition()` (lines 1546–1558) and the actor-permission checks in `delete()`, `withdraw()`, `approve()`, and `reject()`.

| From | To | Actor | Notes |
|---|---|---|---|
| (creation) → `REQUESTED` | EMPLOYEE | Self-approval is never permitted (`rejectSelfApproval`). |
| (creation) → `APPROVED` | OWNER / ADMIN | Direct creation skips the approval queue. |
| (creation) → `APPROVED` | Bot | ADR-002 discrepancy — see implementation notes below. |
| `REQUESTED` → `APPROVED` | OWNER / ADMIN | Actor cannot be the same user who created the request. |
| `REQUESTED` → `REJECTED` | OWNER / ADMIN | Rejection reason required (`rejection_reason` field). Actor cannot be the creator. |
| `REQUESTED` → `CANCELLED` | EMPLOYEE (own request) | `withdraw()` — creator withdraws pending request. |
| `REQUESTED` → `CANCELLED` | OWNER / ADMIN | `delete()` on a REQUESTED row — equivalent to withdraw. |
| `APPROVED` → `CANCELLED` | OWNER / ADMIN only | EMPLOYEE authority revoked post-V40 — see implementation notes. |
| `APPROVED` → `EXPIRED` | System | `BlockedSlotExpirationJob` — `end_datetime ≤ now`. |

### Implementation notes — ADR-002 discrepancies

Three points in the implementation differ from or extend ADR-002. The diagram and table above reflect implementation ground truth. ADR-002 requires a future update to align with all three.

**`(creation) → APPROVED` — Bot actor:**  
ADR-002 lists OWNER / ADMIN only for direct-to-APPROVED creation. The implementation (`BlockedSlotService`, creation path) additionally routes Bot-originated blocks directly to `APPROVED`, treating Bot as operationally equivalent to OWNER / ADMIN for this transition. ADR-002 does not document this path.

**`REQUESTED → CANCELLED` actor:**  
ADR-002 specifies creator only. The implementation (`BlockedSlotStatus.java` Javadoc, `withdraw()` and `delete()` in `BlockedSlotService`) permits EMPLOYEE (own request) **or** OWNER / ADMIN. The broader actor set is the enforced rule.

**`APPROVED → CANCELLED` actor:**  
ADR-002 specifies OWNER / ADMIN or EMPLOYEE on self-owned blocks. A subsequent policy change (V40 P1C) revoked EMPLOYEE authority entirely for `APPROVED` cancellations (`BlockedSlotService.delete()`, lines 654–661). `OWNER / ADMIN only` is the enforced rule. The `BlockedSlotStatus.java` Javadoc is stale on this point and must be updated. ADR-002 must also be updated to reflect this.
