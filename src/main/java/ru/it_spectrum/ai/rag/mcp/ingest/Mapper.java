package ru.it_spectrum.ai.rag.mcp.ingest;

import ru.it_spectrum.ai.rag.mcp.collections.MapSettings;
import ru.it_spectrum.ai.rag.mcp.collections.SplitSettings;

import java.util.List;

/** Step 5 of a source: how the values of a unit are laid out as records. */
public interface Mapper {

    /**
     * Checks the {@code map} block of a source before anything is read, so a misspelled value
     * name is a configuration error and not a silently empty field.
     *
     * @throws IllegalArgumentException naming the template and the value that does not fit
     */
    void check(MapSettings map, SplitSettings split, Format format);

    /**
     * @param maxChars a longer text is split into several records
     * @throws IllegalArgumentException when a document key or a record id comes out empty
     */
    List<ChunkRecord> map(ParsedDocument document, List<Unit> units, MapSettings map, MapDefaults defaults,
                          int maxChars);
}
