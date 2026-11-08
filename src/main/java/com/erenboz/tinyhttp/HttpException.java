package com.erenboz.tinyhttp;

/**
 * Signals that request processing should stop and the client should receive the given status.
 * <p>
 * Thrown by the parser for malformed requests, and may be thrown by handlers, e.g.
 * {@code throw new HttpException(HttpStatus.NOT_FOUND, "User not found")}.
 */
public class HttpException extends RuntimeException {

    private final HttpStatus status;

    public HttpException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
