package com.erenboz.tinyhttp.demo;

import com.erenboz.tinyhttp.HttpException;
import com.erenboz.tinyhttp.HttpStatus;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Thread-safe in-memory note storage used by the demo. Requests run on many virtual threads at
 * once, so the store relies on concurrent collections instead of locks.
 */
final class NoteStore {

    static final int MAX_TEXT_LENGTH = 500;

    record Note(long id, String text, Instant createdAt) {
        String toJson() {
            return "{\"id\":" + id + ",\"text\":\"" + escape(text) + "\",\"createdAt\":\"" + createdAt + "\"}";
        }
    }

    private final Map<Long, Note> notes = new ConcurrentSkipListMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    Note add(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Note text must not be empty");
        }
        if (trimmed.length() > MAX_TEXT_LENGTH) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Note text is limited to " + MAX_TEXT_LENGTH + " characters");
        }
        Note note = new Note(nextId.getAndIncrement(), trimmed, Instant.now());
        notes.put(note.id(), note);
        return note;
    }

    Optional<Note> find(long id) {
        return Optional.ofNullable(notes.get(id));
    }

    boolean remove(long id) {
        return notes.remove(id) != null;
    }

    String toJson() {
        return notes.values().stream().map(Note::toJson).collect(Collectors.joining(",", "[", "]"));
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
