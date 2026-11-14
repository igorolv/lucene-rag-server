package ru.it_spectrum.ai.rag.mcp.collections;

import java.nio.file.Path;

/**
 * One entry of a collection's {@code sources}.
 *
 * @param path    a directory or a single file, absolute after loading
 * @param include glob on the path relative to {@code path}, {@code /}-separated; {@code null}
 *                means the format's default
 * @param exclude glob of files to skip, or {@code null}
 * @param format  reader name
 * @param chunk   how the source is cut into records
 */
public record SourceDefinition(Path path, String include, String exclude, String format, ChunkSettings chunk) {

    public static final String MARKDOWN = "markdown";
}
