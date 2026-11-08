package com.erenboz.tinyhttp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests that talk to a real server over TCP, using both {@link HttpClient} and raw
 * sockets for cases a well-behaved client would never produce.
 */
class HttpServerTest {

    @TempDir
    static Path publicDir;

    private static HttpServer server;
    private static HttpClient client;

    @BeforeAll
    static void startServer() throws IOException {
        Files.writeString(publicDir.resolve("index.html"), "<h1>home</h1>");
        Files.createDirectories(publicDir.resolve("css"));
        Files.writeString(publicDir.resolve("css/site.css"), "body{}");

        Router router = new Router()
                .get("/hello/{name}", req -> HttpResponse.text("Hello, " + req.pathParam("name").orElseThrow()))
                .get("/query", req -> HttpResponse.text(req.query("name").orElse("none")))
                .post("/echo", req -> HttpResponse.text(req.bodyAsString()))
                .get("/boom", req -> {
                    throw new IllegalStateException("simulated failure");
                })
                .get("/forbidden", req -> {
                    throw new HttpException(HttpStatus.FORBIDDEN, "nope");
                })
                .get("/slow", req -> {
                    Thread.sleep(200);
                    return HttpResponse.text("done");
                })
                .staticFiles("/static", publicDir);

        server = HttpServer.builder().port(0).router(router).maxBodyBytes(64 * 1024).build().start();
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    private static URI uri(String path) {
        return URI.create("http://localhost:" + server.port() + path);
    }

    private static java.net.http.HttpResponse<String> get(String path) throws Exception {
        return client.send(java.net.http.HttpRequest.newBuilder(uri(path)).build(), BodyHandlers.ofString());
    }

    private static java.net.http.HttpResponse<String> send(String method, String path, String body) throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(uri(path))
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body))
                .build();
        return client.send(request, BodyHandlers.ofString());
    }

    /** Sends raw bytes and reads until the server closes the connection. */
    private static String raw(String request) throws IOException {
        try (Socket socket = new Socket("localhost", server.port())) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void routesWithPathParameters() throws Exception {
        var response = get("/hello/Eren");

        assertEquals(200, response.statusCode());
        assertEquals("Hello, Eren", response.body());
        assertEquals("text/plain; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
    }

    @Test
    void decodesQueryParameters() throws Exception {
        assertEquals("Eren Boz", get("/query?name=Eren%20Boz").body());
    }

    @Test
    void echoesUtf8RequestBody() throws Exception {
        var response = send("POST", "/echo", "merhaba dünya, çğıöşü");

        assertEquals(200, response.statusCode());
        assertEquals("merhaba dünya, çğıöşü", response.body());
    }

    @Test
    void addsDateAndServerHeaders() throws Exception {
        var headers = get("/hello/x").headers();

        assertTrue(headers.firstValue("Date").orElseThrow().endsWith("GMT"));
        assertEquals("tinyhttp", headers.firstValue("Server").orElseThrow());
    }

    @Test
    void returns404ForUnknownPath() throws Exception {
        assertEquals(404, get("/does-not-exist").statusCode());
    }

    @Test
    void returns405WithAllowHeader() throws Exception {
        var response = send("DELETE", "/echo", null);

        assertEquals(405, response.statusCode());
        assertEquals("POST, OPTIONS", response.headers().firstValue("Allow").orElseThrow());
    }

    @Test
    void answersOptionsWithAllowedMethods() throws Exception {
        var response = send("OPTIONS", "/hello/x", null);

        assertEquals(204, response.statusCode());
        assertEquals("GET, HEAD, OPTIONS", response.headers().firstValue("Allow").orElseThrow());
    }

    @Test
    void headReturnsHeadersWithoutBody() throws Exception {
        var response = send("HEAD", "/hello/Eren", null);

        assertEquals(200, response.statusCode());
        assertEquals("", response.body());
        assertEquals("11", response.headers().firstValue("Content-Length").orElseThrow());
    }

    @Test
    void unexpectedHandlerExceptionBecomes500() throws Exception {
        assertEquals(500, get("/boom").statusCode());
    }

    @Test
    void httpExceptionControlsStatus() throws Exception {
        var response = get("/forbidden");

        assertEquals(403, response.statusCode());
        assertEquals("nope\n", response.body());
    }

    @Test
    void servesStaticFilesWithContentType() throws Exception {
        var css = get("/static/css/site.css");
        assertEquals(200, css.statusCode());
        assertEquals("body{}", css.body());
        assertEquals("text/css; charset=utf-8", css.headers().firstValue("Content-Type").orElseThrow());

        var index = get("/static/");
        assertEquals("<h1>home</h1>", index.body());
        assertEquals("text/html; charset=utf-8", index.headers().firstValue("Content-Type").orElseThrow());

        assertEquals(404, get("/static/missing.txt").statusCode());
    }

    @Test
    void blocksPathTraversal() throws IOException {
        String response = raw("GET /static/%2e%2e/%2e%2e/secret.txt HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");

        assertTrue(response.startsWith("HTTP/1.1 403 Forbidden\r\n"), response);
    }

    @Test
    void servesPipelinedRequestsOnOneConnection() throws IOException {
        String response = raw("GET /hello/a HTTP/1.1\r\nHost: x\r\n\r\n"
                + "GET /hello/b HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");

        assertEquals(2, response.split("HTTP/1.1 200 OK", -1).length - 1, response);
        assertTrue(response.contains("Connection: keep-alive"));
        assertTrue(response.endsWith("Hello, b"));
    }

    @Test
    void closesHttp10ConnectionsByDefault() throws IOException {
        String response = raw("GET /hello/old HTTP/1.0\r\n\r\n");

        assertTrue(response.contains("Connection: close"), response);
        assertTrue(response.endsWith("Hello, old"));
    }

    @Test
    void answersMalformedRequestWith400() throws IOException {
        assertTrue(raw("this is not http\r\n\r\n").startsWith("HTTP/1.1 400 Bad Request\r\n"));
    }

    @Test
    void rejectsOversizedBodyBeforeReadingIt() throws IOException {
        String response = raw("POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 10000000\r\n\r\n");

        assertTrue(response.startsWith("HTTP/1.1 413 Payload Too Large\r\n"), response);
    }

    @Test
    void handlesSlowRequestsConcurrently() {
        int requests = 100;
        long start = System.nanoTime();

        List<CompletableFuture<java.net.http.HttpResponse<String>>> futures = IntStream.range(0, requests)
                .mapToObj(i -> client.sendAsync(java.net.http.HttpRequest.newBuilder(uri("/slow")).build(),
                        BodyHandlers.ofString()))
                .toList();
        futures.forEach(future -> assertEquals(200, future.join().statusCode()));

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        // Served one at a time this would take 100 x 200 ms = 20 s.
        assertTrue(elapsedMillis < 10_000, "took " + elapsedMillis + " ms");
    }

    @Test
    void stopClosesIdleKeepAliveConnectionsQuickly() throws Exception {
        HttpServer temporary = HttpServer.builder()
                .port(0)
                .router(new Router().get("/", req -> HttpResponse.text("ok")))
                .readTimeout(Duration.ofSeconds(30))
                .build()
                .start();
        HttpClient keepAliveClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var request = java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + temporary.port() + "/")).build();
        assertEquals(200, keepAliveClient.send(request, BodyHandlers.ofString()).statusCode());

        long start = System.nanoTime();
        temporary.stop();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // Without closing idle connections, stop() would wait for the full shutdown grace period.
        assertTrue(elapsedMillis < 2_000, "stop took " + elapsedMillis + " ms");
    }
}
