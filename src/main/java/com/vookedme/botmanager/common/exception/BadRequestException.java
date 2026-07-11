package com.vookedme.botmanager.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when the client has provided invalid input that the server cannot
 * process. Maps to HTTP 400 Bad Request.
 *
 * <p>The {@code GlobalExceptionHandler} *(published in a subsequent source
 * batch)* intercepts this exception and writes a structured error response.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
