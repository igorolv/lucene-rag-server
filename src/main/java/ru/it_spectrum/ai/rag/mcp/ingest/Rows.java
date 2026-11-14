package ru.it_spectrum.ai.rag.mcp.ingest;

import java.util.List;
import java.util.Map;

/**
 * What a row format makes of one item: units that need no splitting.
 *
 * @param path   path of the item relative to the source root, {@code /}-separated
 * @param title  document title
 * @param fields document fields a record may inherit, name to values
 * @param values document values for templates
 * @param units  one unit per row, list item or line, in the order of the item
 */
public record Rows(String path, String title, Map<String, List<String>> fields,
                   Map<String, List<String>> values, List<Unit> units) implements ParsedDocument {
}
