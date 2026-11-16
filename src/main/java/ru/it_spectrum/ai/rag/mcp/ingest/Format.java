package ru.it_spectrum.ai.rag.mcp.ingest;

import java.io.IOException;
import java.nio.file.Path;

import ru.it_spectrum.ai.rag.mcp.definition.SplitSettings;

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
        checkNoOutlineOrSelector(split);
    }

    /** Rejects the settings of the {@code pdf} and {@code html} formats for any other format. */
    default void checkNoOutlineOrSelector(SplitSettings split) {
        if (split.outline()) {
            throw new IllegalArgumentException("split.outline is not a setting of " + valueNames().label()
                    + "; it cuts a PDF file by its outline");
        }
        if (split.selector() != null) {
            throw new IllegalArgumentException("split.selector is not a setting of " + valueNames().label()
                    + "; it chooses the elements of an HTML file");
        }
    }

    /**
     * @param split the {@code split} block of the source, for a format that cuts its own units
     * @throws IllegalArgumentException when the text is not of this format
     */
    ParsedDocument parse(SourceText text, SplitSettings split);

    /**
     * The format reads the file of an item itself instead of its text, as a binary format must.
     * Such a format takes no {@code convert} steps and needs an origin that lists files.
     */
    default boolean readsFile() {
        return false;
    }

    /**
     * Parses the file of an item; called instead of {@link #parse(SourceText, SplitSettings)}
     * when {@link #readsFile()}.
     *
     * @param path  path of the item relative to the source root, {@code /}-separated
     * @throws IOException              when the file cannot be read
     * @throws IllegalArgumentException when the file is not of this format
     */
    default ParsedDocument parseFile(Path file, String path, SplitSettings split) throws IOException {
        throw new UnsupportedOperationException(name() + " does not read files");
    }
}
