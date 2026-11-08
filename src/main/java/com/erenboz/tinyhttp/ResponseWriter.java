package com.erenboz.tinyhttp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Serializes an {@link HttpResponse} onto the wire.
 */
final class ResponseWriter {

    /** IMF-fixdate, the preferred date format in HTTP (RFC 9110 section 5.6.7). */
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private ResponseWriter() {
    }

    static void write(OutputStream out, HttpResponse response, boolean headOnly, boolean keepAlive)
            throws IOException {
        HttpStatus status = response.status();
        Headers headers = response.headers();
        boolean bodyAllowed = status != HttpStatus.NO_CONTENT && status != HttpStatus.NOT_MODIFIED;

        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(status.code()).append(' ').append(status.reason()).append("\r\n");

        for (String name : headers.names()) {
            // Framing headers are always computed by the server, never taken from the handler.
            if (name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Connection")) {
                continue;
            }
            for (String value : headers.all(name)) {
                head.append(name).append(": ").append(value).append("\r\n");
            }
        }
        if (!headers.contains("Date")) {
            head.append("Date: ").append(HTTP_DATE.format(Instant.now())).append("\r\n");
        }
        if (!headers.contains("Server")) {
            head.append("Server: tinyhttp\r\n");
        }
        if (bodyAllowed) {
            head.append("Content-Length: ").append(response.body().length).append("\r\n");
        }
        head.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n\r\n");

        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (bodyAllowed && !headOnly) {
            out.write(response.body());
        }
        out.flush();
    }
}
