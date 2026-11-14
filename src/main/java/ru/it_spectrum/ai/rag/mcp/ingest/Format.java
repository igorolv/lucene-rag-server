package ru.it_spectrum.ai.rag.mcp.ingest;

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

    /** @throws IllegalArgumentException when the text is not of this format */
    ParsedDocument parse(SourceText text);
}
