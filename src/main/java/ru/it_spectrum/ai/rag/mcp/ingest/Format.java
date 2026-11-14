package ru.it_spectrum.ai.rag.mcp.ingest;

import ru.it_spectrum.ai.rag.mcp.collections.SplitSettings;

import java.util.List;

/**
 * Step 3 of a source: text to structure. A tree format gives a {@link SectionTree} that the
 * splitter cuts into units; a row format gives {@link Rows} that already are units.
 *
 * <p>A format also declares what the other steps need to know about it, so that nothing outside
 * the class of the format names the format.
 */
public interface Format {

    /** The value of {@code format} in a source entry. */
    String name();

    /** File extensions, lower case without the dot, that select this format when a source names none. */
    List<String> extensions();

    /** Glob used when a source names no {@code include}. */
    String defaultInclude();

    /** Templates used for the parts of a record the {@code map} block of a source leaves out. */
    MapDefaults mapDefaults();

    /** Names of the values this format gives to templates. */
    ValueNames valueNames();

    /**
     * Checks the {@code split} block of a source before anything is read. By default a format
     * takes no {@code split.list}.
     *
     * @throws IllegalArgumentException naming the setting that does not fit the format
     */
    default void check(SplitSettings split) {
        if (split.list() != null) {
            throw new IllegalArgumentException("split.list is not a setting of " + valueNames().label());
        }
    }

    /**
     * @param split the {@code split} block of the source, for a format that cuts its own units
     * @throws IllegalArgumentException when the text is not of this format
     */
    ParsedDocument parse(SourceText text, SplitSettings split);
}
