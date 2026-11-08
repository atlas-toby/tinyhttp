package com.erenboz.tinyhttp;

import java.util.Map;
import java.util.Set;

/**
 * Result of looking up a request in the {@link Router}.
 */
sealed interface RouteMatch {

    /** A route matched both the path and the method. */
    record Found(Handler handler, Map<String, String> params) implements RouteMatch {
    }

    /** The path exists, but not for the requested method. */
    record MethodNotAllowed(Set<HttpMethod> allowed) implements RouteMatch {
    }

    /** No route matches the path. */
    record NotFound() implements RouteMatch {
    }
}
