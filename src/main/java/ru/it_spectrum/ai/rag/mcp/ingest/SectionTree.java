package ru.it_spectrum.ai.rag.mcp.ingest;

import java.util.List;
import java.util.Map;

/**
 * The second of the three shapes that travel between steps: what a tree format makes of one
 * item, before splitting.
 *
 * @param path     path relative to the source root, {@code /}-separated
 * @param title    document title: the {@code title} field, the first top-level heading or the
 *                 file name
 * @param fields   document fields (Markdown front matter), name to values
 * @param values   document values for templates ({@code frontmatter.<key>})
 * @param preamble text before the first heading; may be empty
 * @param sections top-level sections in document order
 */
public record SectionTree(String path, String title, Map<String, List<String>> fields,
                          Map<String, List<String>> values, String preamble, List<Section> sections)
        implements ParsedDocument {
}
