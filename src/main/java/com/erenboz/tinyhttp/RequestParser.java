package com.erenboz.tinyhttp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads HTTP/1.0 and HTTP/1.1 requests from a byte stream (RFC 9112).
 * <p>
 * The parser enforces hard limits on line length, header count and body size so that a
 * misbehaving client cannot exhaust server memory.
 */
final class RequestParser {

    static final int MAX_LINE_LENGTH = 8192;
    static final int MAX_HEADER_COUNT = 100;
    private static final int MAX_LEADING_EMPTY_LINES = 8;

    private final int maxBodyBytes;

    RequestParser(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    /**
     * Parses the next request from the stream.
     *
     * @return the request, or {@code null} if the stream ended cleanly before a new request began
     *         (the client closed an idle keep-alive connection)
     * @throws HttpException if the request is malformed or exceeds a limit
     */
    HttpRequest parse(InputStream in, InetSocketAddress remote) throws IOException {
        String requestLine = readRequestLine(in);
        if (requestLine == null) {
            return null;
        }

        String[] parts = requestLine.split(" ", -1);
        if (parts.length != 3) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Malformed request line");
        }
        HttpMethod method = HttpMethod.parse(parts[0]);
        String target = parts[1];
        String version = parts[2];

        if (!target.startsWith("/")) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Request target must start with '/'");
        }
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            throw version.startsWith("HTTP/")
                    ? new HttpException(HttpStatus.HTTP_VERSION_NOT_SUPPORTED, "Unsupported version: " + version)
                    : new HttpException(HttpStatus.BAD_REQUEST, "Malformed HTTP version");
        }

        Headers headers = readHeaders(in);
        if (version.equals("HTTP/1.1") && !headers.contains("Host")) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Missing Host header");
        }
        if (headers.contains("Transfer-Encoding")) {
            throw new HttpException(HttpStatus.NOT_IMPLEMENTED, "Transfer-Encoding is not supported");
        }

        byte[] body = readBody(in, headers);

        int queryStart = target.indexOf('?');
        String rawPath = queryStart < 0 ? target : target.substring(0, queryStart);
        String rawQuery = queryStart < 0 ? "" : target.substring(queryStart + 1);

        return new HttpRequest(method, target, decodePath(rawPath), parseQuery(rawQuery),
                version, headers, body, Map.of(), remote);
    }

    private static String readRequestLine(InputStream in) throws IOException {
        // RFC 9112 section 2.2: servers should ignore empty lines received before the request line.
        for (int i = 0; i <= MAX_LEADING_EMPTY_LINES; i++) {
            String line = readLine(in, HttpStatus.URI_TOO_LONG);
            if (line == null || !line.isEmpty()) {
                return line;
            }
        }
        throw new HttpException(HttpStatus.BAD_REQUEST, "Too many empty lines before request");
    }

    private static Headers readHeaders(InputStream in) throws IOException {
        Headers headers = new Headers();
        int count = 0;
        while (true) {
            String line = readLine(in, HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE);
            if (line == null) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "Unexpected end of stream in headers");
            }
            if (line.isEmpty()) {
                return headers;
            }
            if (++count > MAX_HEADER_COUNT) {
                throw new HttpException(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE, "Too many headers");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "Malformed header line");
            }
            String name = line.substring(0, colon);
            // No whitespace is allowed between the field name and the colon (RFC 9112 section 5.1).
            if (name.indexOf(' ') >= 0 || name.indexOf('\t') >= 0) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "Malformed header name");
            }
            headers.add(name, line.substring(colon + 1).strip());
        }
    }

    private byte[] readBody(InputStream in, Headers headers) throws IOException {
        List<String> values = headers.all("Content-Length");
        if (values.isEmpty()) {
            return new byte[0];
        }
        String first = values.get(0).strip();
        for (String value : values) {
            if (!value.strip().equals(first)) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "Conflicting Content-Length headers");
            }
        }
        if (!first.matches("\\d{1,18}")) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Invalid Content-Length");
        }
        long length = Long.parseLong(first);
        if (length > maxBodyBytes) {
            throw new HttpException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Body exceeds limit of " + maxBodyBytes + " bytes");
        }
        byte[] body = in.readNBytes((int) length);
        if (body.length != length) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Body is shorter than Content-Length");
        }
        return body;
    }

    /**
     * Reads one line terminated by LF (optionally preceded by CR).
     *
     * @return the line without its terminator, or {@code null} if the stream ended before any byte
     */
    private static String readLine(InputStream in, HttpStatus tooLongStatus) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = buffer.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            if (buffer.size() >= MAX_LINE_LENGTH) {
                throw new HttpException(tooLongStatus, "Line exceeds " + MAX_LINE_LENGTH + " bytes");
            }
            buffer.write(b);
        }
        if (buffer.size() == 0) {
            return null;
        }
        throw new HttpException(HttpStatus.BAD_REQUEST, "Unexpected end of stream");
    }

    private static String decodePath(String rawPath) {
        // URLDecoder implements form decoding, where '+' means space. In a path '+' is a literal plus.
        return decode(rawPath.replace("+", "%2B"));
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            params.putIfAbsent(key, value);
        }
        return params;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Malformed percent-encoding");
        }
    }
}
