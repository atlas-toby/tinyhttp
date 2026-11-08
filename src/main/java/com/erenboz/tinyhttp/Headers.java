package com.erenboz.tinyhttp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Case-insensitive, multi-valued collection of HTTP header fields.
 */
public final class Headers {

    private final Map<String, List<String>> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    /** Appends a value, keeping any existing values for the same name. */
    public Headers add(String name, String value) {
        values.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        return this;
    }

    /** Replaces all existing values for the name with a single value. */
    public Headers set(String name, String value) {
        List<String> list = new ArrayList<>();
        list.add(value);
        values.put(name, list);
        return this;
    }

    public Headers remove(String name) {
        values.remove(name);
        return this;
    }

    public Optional<String> first(String name) {
        List<String> list = values.get(name);
        return list == null || list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<String> all(String name) {
        return List.copyOf(values.getOrDefault(name, List.of()));
    }

    public boolean contains(String name) {
        return values.containsKey(name);
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(values.keySet());
    }

    /**
     * Returns true if any comma-separated token of the header equals {@code token}, ignoring case.
     * Useful for list-valued headers such as {@code Connection: keep-alive, Upgrade}.
     */
    public boolean hasToken(String name, String token) {
        for (String value : values.getOrDefault(name, List.of())) {
            for (String part : value.split(",")) {
                if (part.strip().equalsIgnoreCase(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
