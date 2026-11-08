package com.erenboz.tinyhttp;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A mutable, fluent HTTP response builder returned by handlers.
 *
 * <pre>{@code
 * return HttpResponse.json("{\"id\":1}")
 *         .status(HttpStatus.CREATED)
 *         .header("Location", "/api/notes/1");
 * }</pre>
 *
 * {@code Content-Length}, {@code Connection}, {@code Date} and {@code Server} are managed by the server.
 */
public final class HttpResponse {

    private static final byte[] EMPTY = new byte[0];

    private HttpStatus status;
    private final Headers headers = new Headers();
    private byte[] body = EMPTY;

    private HttpResponse(HttpStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public static HttpResponse of(HttpStatus status) {
        return new HttpResponse(status);
    }

    public static HttpResponse text(String text) {
        return of(HttpStatus.OK).header("Content-Type", "text/plain; charset=utf-8").body(text);
    }

    public static HttpResponse html(String html) {
        return of(HttpStatus.OK).header("Content-Type", "text/html; charset=utf-8").body(html);
    }

    public static HttpResponse json(String json) {
        return of(HttpStatus.OK).header("Content-Type", "application/json").body(json);
    }

    public static HttpResponse redirect(String location) {
        return of(HttpStatus.FOUND).header("Location", location);
    }

    public HttpResponse status(HttpStatus status) {
        this.status = Objects.requireNonNull(status, "status");
        return this;
    }

    /**
     * Sets a header, replacing previous values. CR and LF are rejected so that user-controlled
     * values cannot inject additional headers (response splitting).
     */
    public HttpResponse header(String name, String value) {
        requireNoLineBreaks(name);
        requireNoLineBreaks(value);
        headers.set(name, value);
        return this;
    }

    /** Uses the given array as the body without copying it. */
    public HttpResponse body(byte[] body) {
        this.body = Objects.requireNonNull(body, "body");
        return this;
    }

    public HttpResponse body(String body) {
        return body(body.getBytes(StandardCharsets.UTF_8));
    }

    public HttpStatus status() {
        return status;
    }

    public Headers headers() {
        return headers;
    }

    public byte[] body() {
        return body;
    }

    private static void requireNoLineBreaks(String s) {
        Objects.requireNonNull(s);
        if (s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Header names and values must not contain CR or LF");
        }
    }
}
