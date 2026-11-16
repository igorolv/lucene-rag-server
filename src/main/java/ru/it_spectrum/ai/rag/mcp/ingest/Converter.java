package ru.it_spectrum.ai.rag.mcp.ingest;

import ru.it_spectrum.ai.rag.mcp.definition.ConvertStep;

import java.io.IOException;

/**
 * Step 2 of a source: text to text. The {@code convert} array of a source lists its steps, each
 * an object that names its converter by one of its keys ({@code {"command": [...]}}). They run in
 * the order they are written, each on the result of the previous one; a source without converters
 * hands the text of the item to the format as it is.
 */
public interface Converter {

    /** The name the registry finds the converter by, and the key that names it in a step. */
    String name();

    /**
     * Checks the settings of one step before anything is read and makes the step ready to run.
     *
     * @throws IllegalArgumentException naming the setting that does not fit
     */
    Conversion prepare(ConvertStep step);

    /** One step of {@code convert}, checked and ready. */
    interface Conversion {

        /**
         * The step reads the file of the item itself instead of the text so far, so it may come
         * first for a binary file, and an item that is not a file cannot pass through it.
         */
        boolean readsFile();

        /**
         * @param item what the origin listed
         * @param text the text so far; {@code null} when this step comes first and
         *             {@link #readsFile()}, so the file was not read as text
         * @throws IOException when the conversion fails
         */
        SourceText convert(SourceItem item, SourceText text) throws IOException;
    }
}
