package com.erenboz.tinyhttp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Maps a method and path to a {@link Handler}.
 * <p>
 * Patterns are made of segments separated by {@code /}:
 * <ul>
 *   <li>{@code /users} matches literally,</li>
 *   <li>{@code /users/{id}} captures one segment as the path parameter {@code id},</li>
 *   <li>{@code /static/*} captures the rest of the path as the parameter {@code "*"}.</li>
 * </ul>
 * Routes are checked in registration order, so register specific routes before general ones.
 * Trailing slashes are ignored. {@code HEAD} falls back to the matching {@code GET} route.
 */
public final class Router {

    private record Route(HttpMethod method, List<String> segments, Handler handler) {
    }

    private final List<Route> routes = new ArrayList<>();

    public Router get(String pattern, Handler handler) {
        return route(HttpMethod.GET, pattern, handler);
    }

    public Router post(String pattern, Handler handler) {
        return route(HttpMethod.POST, pattern, handler);
    }

    public Router put(String pattern, Handler handler) {
        return route(HttpMethod.PUT, pattern, handler);
    }

    public Router patch(String pattern, Handler handler) {
        return route(HttpMethod.PATCH, pattern, handler);
    }

    public Router delete(String pattern, Handler handler) {
        return route(HttpMethod.DELETE, pattern, handler);
    }

    public Router route(HttpMethod method, String pattern, Handler handler) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(handler, "handler");
        if (!pattern.startsWith("/")) {
            throw new IllegalArgumentException("Pattern must start with '/': " + pattern);
        }
        List<String> segments = split(pattern);
        for (int i = 0; i < segments.size() - 1; i++) {
            if (segments.get(i).equals("*")) {
                throw new IllegalArgumentException("Wildcard '*' is only allowed as the last segment: " + pattern);
            }
        }
        routes.add(new Route(method, segments, handler));
        return this;
    }

    /**
     * Serves files from {@code directory} under {@code prefix}, e.g.
     * {@code staticFiles("/assets", Path.of("public"))} maps {@code /assets/css/site.css} to
     * {@code public/css/site.css}.
     */
    public Router staticFiles(String prefix, Path directory) {
        String pattern = prefix.endsWith("/") ? prefix + "*" : prefix + "/*";
        return get(pattern, new StaticFileHandler(directory));
    }

    RouteMatch resolve(HttpMethod method, String path) {
        List<String> segments = split(path);
        Set<HttpMethod> allowed = EnumSet.noneOf(HttpMethod.class);
        RouteMatch.Found headFallback = null;

        for (Route route : routes) {
            Map<String, String> params = match(route.segments(), segments);
            if (params == null) {
                continue;
            }
            allowed.add(route.method());
            if (route.method() == method) {
                return new RouteMatch.Found(route.handler(), params);
            }
            if (method == HttpMethod.HEAD && route.method() == HttpMethod.GET && headFallback == null) {
                headFallback = new RouteMatch.Found(route.handler(), params);
            }
        }

        if (headFallback != null) {
            return headFallback;
        }
        if (allowed.isEmpty()) {
            return new RouteMatch.NotFound();
        }
        if (allowed.contains(HttpMethod.GET)) {
            allowed.add(HttpMethod.HEAD);
        }
        allowed.add(HttpMethod.OPTIONS);
        return new RouteMatch.MethodNotAllowed(allowed);
    }

    /** Returns the captured parameters, or {@code null} if the path does not match the pattern. */
    private static Map<String, String> match(List<String> pattern, List<String> path) {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < pattern.size(); i++) {
            String expected = pattern.get(i);
            if (expected.equals("*")) {
                params.put("*", String.join("/", path.subList(i, path.size())));
                return params;
            }
            if (i >= path.size()) {
                return null;
            }
            String actual = path.get(i);
            if (expected.startsWith("{") && expected.endsWith("}")) {
                params.put(expected.substring(1, expected.length() - 1), actual);
            } else if (!expected.equals(actual)) {
                return null;
            }
        }
        return pattern.size() == path.size() ? params : null;
    }

    private static List<String> split(String path) {
        return Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toList();
    }
}
