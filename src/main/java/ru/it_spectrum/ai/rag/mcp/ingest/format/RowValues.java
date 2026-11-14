package ru.it_spectrum.ai.rag.mcp.ingest.format;

import ru.it_spectrum.ai.rag.mcp.ingest.Unit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What the row formats share: turning the keys of a parsed row into named unit values, the same
 * way Markdown front matter becomes fields. A scalar is one value, a list of scalars is several;
 * nested structures, blank values and keys with characters other than letters, digits,
 * {@code .}, {@code _}, {@code -} are skipped.
 */
final class RowValues {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]+");

    private final String prefix;
    private final Map<String, List<String>> values = new LinkedHashMap<>();

    /** @param prefix prefix of every value name: {@code record.} */
    RowValues(String prefix) {
        this.prefix = prefix;
    }

    /** Adds a key with a scalar or a list of scalars under the prefix; anything else is skipped. */
    RowValues put(Object key, Object value) {
        String name = String.valueOf(key);
        return NAME.matcher(name).matches() ? putAs(prefix + name, value) : this;
    }

    /** Adds a value under its own name, without the prefix. */
    RowValues putAs(String name, Object value) {
        List<String> found = new ArrayList<>();
        if (value instanceof Collection<?> items) {
            for (Object item : items) {
                addScalar(found, item);
            }
        } else {
            addScalar(found, value);
        }
        if (!found.isEmpty()) {
            values.put(name, List.copyOf(found));
        }
        return this;
    }

    /** Every key of a map, in its order. */
    RowValues putAll(Map<?, ?> map) {
        map.forEach(this::put);
        return this;
    }

    /** The values added so far. */
    Map<String, List<String>> values() {
        return Map.copyOf(values);
    }

    /** The unit of the row at a position, from 1. */
    Unit unit(int row) {
        Map<String, List<String>> result = new LinkedHashMap<>(values);
        result.put(Unit.ROW, List.of(Integer.toString(row)));
        return new Unit(List.of(), false, Map.copyOf(result));
    }

    private static void addScalar(List<String> values, Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof Collection<?>) {
            return;
        }
        String text = String.valueOf(value).strip();
        if (!text.isEmpty()) {
            values.add(text);
        }
    }

    /** The lines of a text with any line ends and without a byte order mark. */
    static String[] lines(String text) {
        String result = text.replace("\r\n", "\n").replace('\r', '\n');
        if (result.startsWith("﻿")) {
            result = result.substring(1);
        }
        return result.split("\n", -1);
    }

    /** The file name without its extension. */
    static String fileName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
