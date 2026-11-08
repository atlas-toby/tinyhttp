package com.erenboz.tinyhttp;

/**
 * Turns a request into a response. Handlers run on their own virtual thread, so blocking calls
 * (database queries, {@code Thread.sleep}, file I/O) are fine.
 * <p>
 * Throw {@link HttpException} to answer with a specific status; any other exception results in
 * 500 Internal Server Error.
 */
@FunctionalInterface
public interface Handler {
    HttpResponse handle(HttpRequest request) throws Exception;
}
