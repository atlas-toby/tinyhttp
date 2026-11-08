package com.erenboz.tinyhttp.demo;

import com.erenboz.tinyhttp.HttpException;
import com.erenboz.tinyhttp.HttpRequest;
import com.erenboz.tinyhttp.HttpResponse;
import com.erenboz.tinyhttp.HttpServer;
import com.erenboz.tinyhttp.HttpStatus;
import com.erenboz.tinyhttp.Router;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Example application: a small notes API plus static files from {@code ./public}.
 *
 * <pre>
 * java -jar target/tinyhttp-1.0.0.jar [port]
 * </pre>
 */
public final class DemoApp {

    private DemoApp() {
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        NoteStore notes = new NoteStore();

        Router router = new Router()
                .get("/hello/{name}", req -> HttpResponse.text("Merhaba, " + req.pathParam("name").orElseThrow() + "!\n"))
                .get("/api/time", req -> HttpResponse.json("{\"now\":\"" + Instant.now() + "\"}"))
                .post("/echo", req -> HttpResponse.text(req.bodyAsString()))
                .get("/api/notes", req -> HttpResponse.json(notes.toJson()))
                .post("/api/notes", req -> {
                    NoteStore.Note note = notes.add(req.bodyAsString());
                    return HttpResponse.json(note.toJson())
                            .status(HttpStatus.CREATED)
                            .header("Location", "/api/notes/" + note.id());
                })
                .get("/api/notes/{id}", req -> notes.find(noteId(req))
                        .map(note -> HttpResponse.json(note.toJson()))
                        .orElseThrow(() -> new HttpException(HttpStatus.NOT_FOUND, "Note not found")))
                .delete("/api/notes/{id}", req -> {
                    if (!notes.remove(noteId(req))) {
                        throw new HttpException(HttpStatus.NOT_FOUND, "Note not found");
                    }
                    return HttpResponse.of(HttpStatus.NO_CONTENT);
                })
                // Registered last so that the API routes above take precedence.
                .staticFiles("/", Path.of("public"));

        HttpServer server = HttpServer.builder()
                .port(port)
                .router(router)
                .accessLog(true)
                .build()
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "tinyhttp-shutdown"));
        System.out.println("tinyhttp listening on http://localhost:" + server.port());
    }

    private static long noteId(HttpRequest request) {
        try {
            return Long.parseLong(request.pathParam("id").orElseThrow());
        } catch (NumberFormatException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Note id must be a number");
        }
    }
}
