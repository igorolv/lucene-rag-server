package ru.it_spectrum.ai.rag.mcp.collections;

import java.nio.file.Path;
import java.util.List;

/**
 * One entry of a collection's {@code sources}. A source has one origin: {@code path} (files) or
 * {@code command} (one run of a program).
 *
 * @param path    a directory or a single file, absolute after loading; {@code null} for a source
 *                with a {@code command}
 * @param include glob on the path relative to {@code path}, {@code /}-separated; {@code null}
 *                means the format's default
 * @param exclude glob of files to skip, or {@code null}
 * @param format  name of the format step, or {@code null} when the source names none and the
 *                file extension decides
 * @param split   how a file is cut into units
 * @param map     how a unit is laid out as a record
 * @param command the program whose output is the only item of the source, or {@code null}
 * @param convert the converters every item passes through, in order; empty for none
 */
public record SourceDefinition(Path path, String include, String exclude, String format,
                               SplitSettings split, MapSettings map, CommandSettings command,
                               List<ConvertStep> convert) {

    public SourceDefinition {
        convert = convert == null ? List.of() : List.copyOf(convert);
    }

    /** A source of files without converters. */
    public SourceDefinition(Path path, String include, String exclude, String format, SplitSettings split,
                            MapSettings map) {
        this(path, include, exclude, format, split, map, null, List.of());
    }
}
