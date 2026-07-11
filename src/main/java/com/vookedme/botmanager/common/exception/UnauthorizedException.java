package com.vookedme.botmanager.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a request is made without valid authentication credentials.
 * Maps to HTTP 401 Unauthorized.
 *
 * <p>Distinct from {@link ForbiddenException} (HTTP 403 — authenticated but
 * not permitted). Typically thrown by the JWT filter chain when no valid
 * token is present, or by the service layer when an operation requires
 * authentication that was not established.
 *
 * <p>The {@code GlobalExceptionHandler} *(published in a subsequent source
 * batch)* intercepts this exception and writes a structured error response.
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }

    public UnauthorizedException() {
        super("Authentication required");
    }
}
