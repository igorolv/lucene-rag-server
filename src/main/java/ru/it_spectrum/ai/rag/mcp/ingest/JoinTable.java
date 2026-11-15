package ru.it_spectrum.ai.rag.mcp.ingest;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The units of a lookup by their key, read once per build of a source. Keys are compared as
 * filters compare values: without surrounding blanks and ignoring case.
 */
public final class JoinTable {

    private final String name;
    private final String on;
    private final Map<String, Map<String, List<String>>> rows = new HashMap<>();

    /**
     * @param name the prefix of the values the lookup gives
     * @param on   template over the values of a unit of the source: the key it looks for
     */
    public JoinTable(String name, String on) {
        this.name = name;
        this.on = on;
    }

    public String name() {
        return name;
    }

    public String on() {
        return on;
    }

    /**
     * Adds a unit of the lookup. A unit whose key is empty is left out.
     *
     * @param values the values of the unit, by the names its format declares
     * @throws IllegalArgumentException when another unit has the key already
     */
    public void put(String key, Map<String, List<String>> values) {
        String normalized = normalize(key);
        if (normalized.isEmpty()) {
            return;
        }
        if (rows.putIfAbsent(normalized, Map.copyOf(values)) != null) {
            throw new IllegalArgumentException("two units have the key \"" + key.strip()
                    + "\"; a key must name one unit");
        }
    }

    /** The values of the unit with the key, or {@code null}. */
    public Map<String, List<String>> row(String key) {
        return rows.get(normalize(key));
    }

    public int size() {
        return rows.size();
    }

    private static String normalize(String key) {
        return key.strip().toLowerCase(Locale.ROOT);
    }
}
