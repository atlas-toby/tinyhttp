package com.erenboz.tinyhttp;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestParserTest {

    private final RequestParser parser = new RequestParser(1024);

    private HttpRequest parse(String raw) throws IOException {
        return parser.parse(stream(raw), null);
    }

    private static InputStream stream(String raw) {
        return new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));
    }

    private HttpStatus failureStatus(String raw) {
        return assertThrows(HttpException.class, () -> parse(raw)).status();
    }

    @Test
    void parsesRequestLineHeadersAndQuery() throws IOException {
        HttpRequest request = parse("GET /search?q=java+21&page=2 HTTP/1.1\r\nHost: localhost\r\nX-Custom: abc\r\n\r\n");

        assertEquals(HttpMethod.GET, request.method());
        assertEquals("/search", request.path());
        assertEquals("/search?q=java+21&page=2", request.target());
        assertEquals("HTTP/1.1", request.version());
        assertEquals("java 21", request.query("q").orElseThrow());
        assertEquals("2", request.query("page").orElseThrow());
        assertEquals("abc", request.header("x-custom").orElseThrow(), "header lookup is case-insensitive");
    }

    @Test
    void readsBodyUsingContentLength() throws IOException {
        HttpRequest request = parse("POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nhello");

        assertEquals(HttpMethod.POST, request.method());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), request.body());
    }

    @Test
    void acceptsBareLineFeeds() throws IOException {
        assertEquals("/", parse("GET / HTTP/1.1\nHost: x\n\n").path());
    }

    @Test
    void ignoresEmptyLinesBeforeRequestLine() throws IOException {
        assertEquals("/a", parse("\r\n\r\nGET /a HTTP/1.1\r\nHost: x\r\n\r\n").path());
    }

    @Test
    void decodesPercentEncodedPathButKeepsPlusLiteral() throws IOException {
        HttpRequest request = parse("GET /files/my%20file+v2.txt HTTP/1.1\r\nHost: x\r\n\r\n");

        assertEquals("/files/my file+v2.txt", request.path());
    }

    @Test
    void keepsFirstValueOfRepeatedQueryParameter() throws IOException {
        assertEquals("1", parse("GET /?a=1&a=2&flag HTTP/1.1\r\nHost: x\r\n\r\n").query("a").orElseThrow());
    }

    @Test
    void returnsNullWhenStreamIsEmpty() throws IOException {
        assertNull(parse(""));
    }

    @Test
    void parsesPipelinedRequestsFromOneStream() throws IOException {
        InputStream in = stream("GET /a HTTP/1.1\r\nHost: x\r\n\r\n"
                + "POST /b HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\n\r\nhi");

        assertEquals("/a", parser.parse(in, null).path());
        HttpRequest second = parser.parse(in, null);
        assertEquals("/b", second.path());
        assertEquals("hi", second.bodyAsString());
        assertNull(parser.parse(in, null));
    }

    @Test
    void allowsMissingHostOnHttp10() throws IOException {
        assertEquals("HTTP/1.0", parse("GET / HTTP/1.0\r\n\r\n").version());
    }

    @Test
    void rejectsMalformedRequestLine() {
        assertEquals(HttpStatus.BAD_REQUEST, failureStatus("GET /\r\n\r\n"));
        assertEquals(HttpStatus.BAD_REQUEST, failureStatus("GET  / HTTP/1.1\r\nHost: x\r\n\r\n"));
        assertEquals(HttpStatus.BAD_REQUEST, failureStatus("GET example.com HTTP/1.1\r\nHost: x\r\n\r\n"));
    }

    @Test
    void rejectsMissingHostOnHttp11() {
        assertEquals(HttpStatus.BAD_REQUEST, failureStatus("GET / HTTP/1.1\r\n\r\n"));
    }

    @Test
    void rejectsUnknownMethod() {
        assertEquals(HttpStatus.NOT_IMPLEMENTED, failureStatus("BREW /pot HTTP/1.1\r\nHost: x\r\n\r\n"));
    }

    @Test
    void rejectsLowercaseMethod() {
        assertEquals(HttpStatus.NOT_IMPLEMENTED, failureStatus("get / HTTP/1.1\r\nHost: x\r\n\r\n"));
    }

    @Test
    void rejectsUnsupportedVersion() {
        assertEquals(HttpStatus.HTTP_VERSION_NOT_SUPPORTED, failureStatus("GET / HTTP/2.0\r\nHost: x\r\n\r\n"));
        assertEquals(HttpStatus.BAD_REQUEST, failureStatus("GET / FTP/1.0\r\nHost: x\r\n\r\n"));
    }

    @Test
    void rejectsOversizedRequestLine() {
        String path = "/" + "a".repeat(RequestParser.MAX_LINE_LENGTH + 10);
        assertEquals(HttpStatus.URI_TOO_LONG, failureStatus("GET " + path + " HTTP/1.1\r\nHost: x\r\n\r\n"));
    }

    @Test
    void rejectsOversizedHeaderLine() {
        String value = "v".repeat(RequestParser.MAX_LINE_LENGTH + 10);
        assertEquals(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE,
                failureStatus("GET / HTTP/1.1\r\nHost: x\r\nX-Big: " + value + "\r\n\r\n"));
    }

    @Test
    void rejectsTooManyHeaders() {
        StringBuilder raw = new StringBuilder("GET / HTTP/1.1\r\nHost: x\r\n");
        for (int i = 0; i < RequestParser.MAX_HEADER_COUNT; i++) {
            raw.append("X-").append(i).append(": v\r\n");
        }
        raw.append("\r\n");
        assertEquals(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE, failureStatus(raw.toString()));
    }

    @Test
    void rejectsBodyLargerThanLimit() {
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE,
                failureStatus("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 2048\r\n\r\n"));
    }

    @Test
    void rejectsTruncatedBody() {
        assertEquals(HttpStatus.BAD_REQUEST,
                failureStatus("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 10\r\n\r\nabc"));
    }

    @Test
    void rejectsInvalidOrConflictingContentLength() {
        assertEquals(HttpStatus.BAD_REQUEST, failureStatus("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: abc\r\n\r\n"));
        assertEquals(HttpStatus.BAD_REQUEST, failureStatus("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: -1\r\n\r\n"));
        assertEquals(HttpStatus.BAD_REQUEST,
                failureStatus("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nab"));
    }

    @Test
    void rejectsMalformedHeaders() {
        assertEquals(HttpStatus.BAD_REQUEST, failureStatus("GET / HTTP/1.1\r\nHost: x\r\nNoColonHere\r\n\r\n"));
        assertEquals(HttpStatus.BAD_REQUEST, failureStatus("GET / HTTP/1.1\r\nHost : x\r\n\r\n"));
    }

    @Test
    void rejectsStreamEndingInsideHeaders() {
        assertEquals(HttpStatus.BAD_REQUEST, failureStatus("GET / HTTP/1.1\r\nHost: x\r\n"));
    }

    @Test
    void rejectsChunkedRequestBodies() {
        assertEquals(HttpStatus.NOT_IMPLEMENTED,
                failureStatus("POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"));
    }

    @Test
    void rejectsMalformedPercentEncoding() {
        assertEquals(HttpStatus.BAD_REQUEST, failureStatus("GET /bad%zz HTTP/1.1\r\nHost: x\r\n\r\n"));
    }
}
