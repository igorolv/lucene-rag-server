package ru.it_spectrum.ai.rag.mcp.ingest;

import java.util.List;
import java.util.Map;

/** What a format makes of one item: a {@link SectionTree} or {@link Rows}. */
public sealed interface ParsedDocument permits SectionTree, Rows {

    /** Path of the item relative to the source root, {@code /}-separated. */
    String path();

    /** Document title. */
    String title();

    /** Document fields a record may inherit as filter fields, name to values. */
    Map<String, List<String>> fields();

    /** Document values for templates, by the names the format declares in {@link Format#valueNames()}. */
    Map<String, List<String>> values();
}
