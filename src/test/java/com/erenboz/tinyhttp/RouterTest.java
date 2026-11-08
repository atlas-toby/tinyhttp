package com.erenboz.tinyhttp;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RouterTest {

    private static final Handler A = request -> HttpResponse.text("a");
    private static final Handler B = request -> HttpResponse.text("b");

    private static RouteMatch.Found found(RouteMatch match) {
        return assertInstanceOf(RouteMatch.Found.class, match);
    }

    @Test
    void matchesLiteralPath() {
        Router router = new Router().get("/health", A);

        assertSame(A, found(router.resolve(HttpMethod.GET, "/health")).handler());
    }

    @Test
    void extractsPathParameters() {
        Router router = new Router().get("/users/{id}/posts/{postId}", A);

        RouteMatch.Found match = found(router.resolve(HttpMethod.GET, "/users/42/posts/7"));

        assertEquals(Map.of("id", "42", "postId", "7"), match.params());
    }

    @Test
    void wildcardCapturesRemainingPath() {
        Router router = new Router().get("/static/*", A);

        assertEquals("css/site.css", found(router.resolve(HttpMethod.GET, "/static/css/site.css")).params().get("*"));
        assertEquals("", found(router.resolve(HttpMethod.GET, "/static")).params().get("*"));
    }

    @Test
    void ignoresTrailingAndDuplicateSlashes() {
        Router router = new Router().get("/users/{id}", A);

        assertSame(A, found(router.resolve(HttpMethod.GET, "/users/5/")).handler());
        assertSame(A, found(router.resolve(HttpMethod.GET, "//users//5")).handler());
    }

    @Test
    void returnsNotFoundForUnknownPath() {
        Router router = new Router().get("/users/{id}", A);

        assertInstanceOf(RouteMatch.NotFound.class, router.resolve(HttpMethod.GET, "/users"));
        assertInstanceOf(RouteMatch.NotFound.class, router.resolve(HttpMethod.GET, "/users/5/extra"));
    }

    @Test
    void returnsMethodNotAllowedWithAllowedMethods() {
        Router router = new Router().get("/items", A).post("/items", B);

        RouteMatch.MethodNotAllowed match = assertInstanceOf(RouteMatch.MethodNotAllowed.class,
                router.resolve(HttpMethod.DELETE, "/items"));

        assertEquals(Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.OPTIONS), match.allowed());
    }

    @Test
    void headFallsBackToGet() {
        Router router = new Router().get("/page", A);

        assertSame(A, found(router.resolve(HttpMethod.HEAD, "/page")).handler());
    }

    @Test
    void explicitHeadRouteWinsOverGetFallback() {
        Router router = new Router().get("/page", A).route(HttpMethod.HEAD, "/page", B);

        assertSame(B, found(router.resolve(HttpMethod.HEAD, "/page")).handler());
    }

    @Test
    void firstRegisteredRouteWins() {
        Router router = new Router().get("/users/me", A).get("/users/{id}", B);

        assertSame(A, found(router.resolve(HttpMethod.GET, "/users/me")).handler());
        assertSame(B, found(router.resolve(HttpMethod.GET, "/users/5")).handler());
    }

    @Test
    void rejectsInvalidPatterns() {
        Router router = new Router();

        assertThrows(IllegalArgumentException.class, () -> router.get("/files/*/edit", A));
        assertThrows(IllegalArgumentException.class, () -> router.get("users", A));
    }
}
