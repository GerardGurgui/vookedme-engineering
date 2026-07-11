package com.vookedme.botmanager.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when the authenticated principal does not have permission to perform
 * the requested operation. Maps to HTTP 403 Forbidden.
 *
 * <p>Used throughout the service layer alongside {@link ConsentService}
 * *(published — SC-5)* and {@link AuthorizationService} *(published — SC-1)*
 * for tenant isolation and consent enforcement decisions.
 *
 * <p>The {@code GlobalExceptionHandler} *(published in a subsequent source
 * batch)* intercepts this exception and writes a structured error response.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }

    public ForbiddenException() {
        super("Access denied");
    }
}
