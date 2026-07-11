package com.vookedme.botmanager.common.exception;

/**
 * Thrown when an operation conflicts with the current state of the system and
 * resolution is the caller's responsibility — duplicate data, a resource
 * modified concurrently by another actor, and so on.
 *
 * <p>Maps to HTTP 409 Conflict in the {@code GlobalExceptionHandler}
 * *(published in a subsequent source batch)*. Distinct from
 * {@link BadRequestException} (HTTP 400 — invalid input format) and from
 * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}
 * (HTTP 409 — stale optimistic-lock version, handled separately by the
 * exception handler).
 *
 * <p>Representative use case: the bot identifies customers by phone number;
 * an employee and a customer belonging to the same business cannot share the
 * same number. Attempting to register such a number throws this exception.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
