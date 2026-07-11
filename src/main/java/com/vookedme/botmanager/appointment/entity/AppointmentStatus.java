package com.vookedme.botmanager.appointment.entity;

/**
 * The six-state finite state machine governing the lifecycle of an
 * {@link Appointment}. Legal transitions are defined in
 * {@code docs/governance/state-machines.md}.
 *
 * <p>The temporal boundary — whether the appointment's {@code datetime}
 * is past — divides the FSM into two operational planes (see ADR-011):
 * the <em>operational plane</em> (future appointments: booking, confirmation,
 * cancellation handling) and the <em>closure plane</em> (past appointments:
 * COMPLETED, NO_SHOW, CANCELLED-accounting). Transition guards enforce
 * this boundary at the service layer.
 */
public enum AppointmentStatus {
    /** Awaiting confirmation — the initial state for most appointments. */
    PENDING,
    /** Confirmed by the business or auto-confirmed by bot AUTO_CONFIRM mode. */
    CONFIRMED,
    /** Customer or bot has requested cancellation; awaiting owner decision. */
    CANCELLATION_REQUESTED,
    /** Cancelled. The appointment slot is freed. */
    CANCELLED,
    /** The appointment took place successfully. */
    COMPLETED,
    /** The customer did not appear. */
    NO_SHOW,
}
