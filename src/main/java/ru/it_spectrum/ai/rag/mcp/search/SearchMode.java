package ru.it_spectrum.ai.rag.mcp.search;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** How {@code search} ranks records (docs/decisions.md D12, D26). */
public enum SearchMode {

    /** BM25 over the words of the query. */
    LEXICAL,
    /** Nearest vectors to the embedded query; needs a collection with vectors and its model. */
    VECTOR,
    /** Both lists fused by reciprocal rank fusion. */
    HYBRID;

    /** The name a caller passes. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * @return the mode, or {@code null} for a blank value, which means the default
     * @throws IllegalArgumentException for an unknown name
     */
    public static SearchMode parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (SearchMode mode : values()) {
            if (mode.id().equals(value.strip().toLowerCase(Locale.ROOT))) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown mode '" + value + "'; use one of "
                + Arrays.stream(values()).map(SearchMode::id).collect(Collectors.joining(", ")));
    }
}
