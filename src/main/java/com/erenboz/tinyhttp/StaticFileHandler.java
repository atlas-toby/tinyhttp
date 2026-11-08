package com.erenboz.tinyhttp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Serves files below a root directory. Expects the relative file path in the {@code "*"} path
 * parameter, which is what {@link Router#staticFiles(String, Path)} sets up.
 * <p>
 * Requests that resolve outside the root, including through {@code ..} segments or symbolic
 * links, are rejected with 403 Forbidden.
 */
public final class StaticFileHandler implements Handler {

    private final Path root;

    public StaticFileHandler(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public HttpResponse handle(HttpRequest request) throws IOException {
        String relative = request.pathParam("*").orElse("");

        Path target;
        try {
            target = root.resolve(relative).normalize();
        } catch (InvalidPathException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Invalid path");
        }
        if (!target.startsWith(root)) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Access denied");
        }
        if (Files.isDirectory(target)) {
            target = target.resolve("index.html");
        }
        if (!Files.isRegularFile(target)) {
            throw new HttpException(HttpStatus.NOT_FOUND, "File not found");
        }
        // Resolve symbolic links and check again, so a link cannot point outside the root.
        if (!target.toRealPath().startsWith(root.toRealPath())) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Access denied");
        }

        return HttpResponse.of(HttpStatus.OK)
                .header("Content-Type", MimeTypes.forPath(target))
                .body(Files.readAllBytes(target));
    }
}
