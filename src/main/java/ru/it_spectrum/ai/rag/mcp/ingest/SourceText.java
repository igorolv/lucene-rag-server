package ru.it_spectrum.ai.rag.mcp.ingest;

/**
 * The first of the three shapes that travel between steps: the text of one item.
 *
 * @param path path of the item relative to the source root, {@code /}-separated
 */
public record SourceText(String path, String text) {
}
