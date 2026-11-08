package com.erenboz.tinyhttp;

/**
 * Request methods understood by the server. Any other method is answered with 501 Not Implemented.
 */
public enum HttpMethod {
    GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS;

    /**
     * Parses a method token. Method names are case-sensitive (RFC 9110, section 9.1).
     */
    static HttpMethod parse(String token) {
        for (HttpMethod method : values()) {
            if (method.name().equals(token)) {
                return method;
            }
        }
        throw new HttpException(HttpStatus.NOT_IMPLEMENTED, "Unsupported method: " + token);
    }
}
