package ru.it_spectrum.ai.rag.mcp.source;

import java.util.List;
import java.util.Map;

/**
 * One source file after a format reader, before chunking.
 *
 * @param path     path relative to the source root, {@code /}-separated
 * @param key      stable identity of the document inside its collection: the {@code id} field
 *                 when the document has one, otherwise {@code path}
 * @param title    document title: the {@code title} field, the first top-level heading or the
 *                 file name
 * @param fields   document fields (Markdown front matter); every record of the document
 *                 inherits them
 * @param preamble text before the first heading; may be empty
 * @param sections top-level sections in document order
 */
public record SourceDocument(String path, String key, String title, Map<String, List<String>> fields,
                             String preamble, List<Section> sections) {
}
