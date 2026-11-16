package ru.it_spectrum.ai.rag.mcp.ingest;

import java.util.List;
import java.util.Map;

/**
 * One searchable record: the unit that is indexed, ranked and returned.
 *
 * @param id         unique inside the index and stable while the heading it came from keeps
 *                   its text: {@code <document key>#<heading slug>}, with {@code ~N} for the
 *                   parts of a split section
 * @param document   key of the document the record belongs to
 * @param title      heading of the section
 * @param breadcrumb document title and ancestor headings down to this section, so the record is
 *                   understandable without its neighbors
 * @param text       the passage
 * @param source     path of the source file relative to its source root
 * @param ordinal    position among the records of the document, from 0
 * @param fields     document fields
 */
public record ChunkRecord(String id, String document, String title, String breadcrumb, String text,
                          String source, int ordinal, Map<String, List<String>> fields) {

    public static final String BREADCRUMB_SEPARATOR = " › ";
}
