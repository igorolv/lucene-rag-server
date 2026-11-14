package ru.it_spectrum.ai.rag.mcp.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One item an origin listed: usually a file.
 *
 * @param path path relative to the source root, {@code /}-separated; what templates see as
 *             {@code {path}}
 * @param file the file, or {@code null} when the item is not a file
 * @param text the text of an item that is not a file, otherwise {@code null}
 */
public record SourceItem(String path, Path file, String text) {

    public static SourceItem ofFile(Path file, String path) {
        return new SourceItem(path, file, null);
    }

    /** The text of the item; a file is read as UTF-8. */
    public SourceText read() throws IOException {
        return new SourceText(path, text != null ? text : Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Where the item is, for messages. */
    public String location() {
        return file != null ? file.toString() : path;
    }
}
