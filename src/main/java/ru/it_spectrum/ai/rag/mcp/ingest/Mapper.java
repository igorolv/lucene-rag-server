package ru.it_spectrum.ai.rag.mcp.ingest;

import ru.it_spectrum.ai.rag.mcp.definition.MapSettings;
import ru.it_spectrum.ai.rag.mcp.definition.SplitSettings;

import java.util.List;
import java.util.Map;

/** Step 5 of a source: how the values of a unit are laid out as records. */
public interface Mapper {

    /**
     * Checks the {@code map} block of a source before anything is read, so a misspelled value
     * name is a configuration error and not a silently empty field.
     *
     * @throws IllegalArgumentException naming the template and the value that does not fit
     */
    default void check(MapSettings map, SplitSettings split, Format format) {
        check(map, split, format, List.of());
    }

    /**
     * The same check for a source with lookups: the {@code key} template of each against the
     * values of its lookup, its {@code on} template and the values {@code <name>.<value>} against
     * those of the source.
     */
    void check(MapSettings map, SplitSettings split, Format format, List<JoinSpec> joins);

    /** A template over the values of a unit of a lookup, as {@code join.<name>.key} is. */
    String key(String template, Map<String, List<String>> values);

    /**
     * @param maxChars a longer text is split into several records
     * @throws IllegalArgumentException when a document key or a record id comes out empty
     */
    default List<ChunkRecord> map(ParsedDocument document, List<Unit> units, MapSettings map, MapDefaults defaults,
                                  int maxChars) {
        return map(document, units, map, defaults, maxChars, List.of());
    }

    /**
     * @param joins the lookups of the source; the unit of a lookup that a unit of the source
     *              names gives it the values {@code <name>.<value>}
     */
    List<ChunkRecord> map(ParsedDocument document, List<Unit> units, MapSettings map, MapDefaults defaults,
                          int maxChars, List<JoinTable> joins);
}
