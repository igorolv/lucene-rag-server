package ru.it_spectrum.ai.rag.mcp.ingest;

import java.io.IOException;

/**
 * Step 2 of a source: text to text. The converters of a source run in the order they are
 * written, each on the result of the previous one; a source without converters hands the text of
 * the item to the format as it is.
 *
 * <p>No source can name a converter yet: the {@code convert} key of {@code collections.json} and
 * the settings a converter receives come with the first implementation (docs/plan.md, stage 2).
 */
public interface Converter {

    /** The name the registry finds the converter by. */
    String name();

    /**
     * @param item what the origin listed; a converter that reads the file itself (a binary
     *             format) uses {@link SourceItem#file()}
     * @param text the text so far
     * @throws IOException when the conversion fails
     */
    SourceText convert(SourceItem item, SourceText text) throws IOException;
}
