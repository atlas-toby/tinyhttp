package com.erenboz.tinyhttp;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static java.util.Map.entry;

/**
 * Maps file extensions to {@code Content-Type} values.
 */
final class MimeTypes {

    private static final String DEFAULT = "application/octet-stream";

    private static final Map<String, String> TYPES = Map.ofEntries(
            entry("html", "text/html; charset=utf-8"),
            entry("htm", "text/html; charset=utf-8"),
            entry("css", "text/css; charset=utf-8"),
            entry("js", "text/javascript; charset=utf-8"),
            entry("mjs", "text/javascript; charset=utf-8"),
            entry("json", "application/json"),
            entry("txt", "text/plain; charset=utf-8"),
            entry("md", "text/markdown; charset=utf-8"),
            entry("xml", "application/xml"),
            entry("svg", "image/svg+xml"),
            entry("png", "image/png"),
            entry("jpg", "image/jpeg"),
            entry("jpeg", "image/jpeg"),
            entry("gif", "image/gif"),
            entry("webp", "image/webp"),
            entry("ico", "image/x-icon"),
            entry("pdf", "application/pdf"),
            entry("wasm", "application/wasm"),
            entry("woff", "font/woff"),
            entry("woff2", "font/woff2"),
            entry("mp3", "audio/mpeg"),
            entry("mp4", "video/mp4"));

    private MimeTypes() {
    }

    static String forPath(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return DEFAULT;
        }
        return TYPES.getOrDefault(name.substring(dot + 1).toLowerCase(Locale.ROOT), DEFAULT);
    }
}
