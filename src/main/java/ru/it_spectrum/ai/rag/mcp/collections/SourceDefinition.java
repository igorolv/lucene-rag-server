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
 * @param split   how a file is cut into units
 * @param map     how a unit is laid out as a record
 */
public record SourceDefinition(Path path, String include, String exclude, String format,
                               SplitSettings split, MapSettings map) {

    public static final String MARKDOWN = "markdown";
}
