package com.erenboz.tinyhttp;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * An immutable, fully parsed HTTP request.
 */
public final class HttpRequest {

    private final HttpMethod method;
    private final String target;
    private final String path;
    private final Map<String, String> queryParams;
    private final String version;
    private final Headers headers;
    private final byte[] body;
    private final Map<String, String> pathParams;
    private final InetSocketAddress remoteAddress;

    HttpRequest(HttpMethod method, String target, String path, Map<String, String> queryParams,
                String version, Headers headers, byte[] body, Map<String, String> pathParams,
                InetSocketAddress remoteAddress) {
        this.method = method;
        this.target = target;
        this.path = path;
        this.queryParams = Map.copyOf(queryParams);
        this.version = version;
        this.headers = headers;
        this.body = body;
        this.pathParams = Map.copyOf(pathParams);
        this.remoteAddress = remoteAddress;
    }

    /** Returns a copy of this request carrying the path parameters extracted by the router. */
    HttpRequest withPathParams(Map<String, String> params) {
        return new HttpRequest(method, target, path, queryParams, version, headers, body, params, remoteAddress);
    }

    public HttpMethod method() {
        return method;
    }

    /** The raw request target as sent by the client, e.g. {@code /search?q=java%2021}. */
    public String target() {
        return target;
    }

    /** The percent-decoded path without the query string, e.g. {@code /search}. */
    public String path() {
        return path;
    }

    public String version() {
        return version;
    }

    public Headers headers() {
        return headers;
    }

    public Optional<String> header(String name) {
        return headers.first(name);
    }

    public Map<String, String> queryParams() {
        return queryParams;
    }

    public Optional<String> query(String name) {
        return Optional.ofNullable(queryParams.get(name));
    }

    /**
     * Returns a value captured by a route pattern. {@code {id}} segments are available by name,
     * and a trailing {@code *} wildcard is available as {@code "*"}.
     */
    public Optional<String> pathParam(String name) {
        return Optional.ofNullable(pathParams.get(name));
    }

    public byte[] body() {
        return body.clone();
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** The client address, or {@code null} when the request was not read from a socket. */
    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    @Override
    public String toString() {
        return method + " " + target + " " + version;
    }
}
