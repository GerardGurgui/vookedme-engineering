package com.vookedme.botmanager.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a requested resource cannot be located. Maps to HTTP 404 Not Found.
 *
 * <p>The {@code GlobalExceptionHandler} *(published in a subsequent source
 * batch)* intercepts this exception and writes a structured error response.
 * The three-argument constructor produces a consistent message format:
 * "{resource} not found with {field}: '{value}'", which appears in API
 * error responses to provide actionable context to the caller.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String resource, String field, Object value) {
        super(String.format("%s not found with %s: '%s'", resource, field, value));
    }
}
