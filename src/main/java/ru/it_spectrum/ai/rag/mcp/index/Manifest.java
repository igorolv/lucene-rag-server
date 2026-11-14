package ru.it_spectrum.ai.rag.mcp.index;

import ru.it_spectrum.ai.rag.mcp.collections.MapSettings;
import ru.it_spectrum.ai.rag.mcp.collections.SplitSettings;
import ru.it_spectrum.ai.rag.mcp.collections.WhereSettings;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Passport of a built collection, {@code <collection>/manifest.json}. Everything the server needs
 * to serve the collection without its sources and without {@code collections.json} is here.
 *
 * @param schema        index schema the collection was built with; see {@link #SCHEMA}
 * @param name          collection name; equals the directory name
 * @param description   what the collection holds, from {@code collections.json}
 * @param builtAt       build time, ISO-8601 UTC
 * @param serverVersion version of the server that built the index
 * @param files         source files read
 * @param documents     documents that produced at least one record
 * @param records       records in the index
 * @param fields        names of the document fields usable as filters, sorted
 * @param keys          names of the fields the {@code lookup} tool searches, sorted; empty for none
 * @param sources       what the collection was built from
 */
public record Manifest(int schema, String name, String description, String builtAt, String serverVersion,
                       int files, int documents, int records, List<String> fields, List<String> keys,
                       List<Source> sources) {

    public Manifest {
        keys = keys == null ? List.of() : List.copyOf(keys);
    }

    /** Raised whenever fields or their analysis change in a way an old index cannot serve. */
    public static final int SCHEMA = 6;

    public static final String FILE_NAME = "manifest.json";
    public static final String INDEX_DIR = "index";

    /**
     * One source as it was configured at build time.
     *
     * @param path           the files read, or {@code null} for a source with a command
     * @param command        the program whose output was read, or {@code null}
     * @param timeoutSeconds the timeout of {@code command}, or {@code null}
     * @param convert        the {@code convert} steps as written; empty for none
     * @param where          the conditions records were selected by
     */
    public record Source(String path, String include, String exclude, String format,
                         SplitSettings split, MapSettings map, List<String> command, Integer timeoutSeconds,
                         List<Map<String, Object>> convert, WhereSettings where) { }

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    public void write(Path collectionDir) throws IOException {
        try {
            Files.writeString(collectionDir.resolve(FILE_NAME), JSON.writeValueAsString(this));
        } catch (JacksonException e) {
            throw new IOException("Cannot write the manifest: " + e.getOriginalMessage(), e);
        }
    }

    /** @throws IOException when the file is missing, unreadable or not a manifest */
    public static Manifest read(Path collectionDir) throws IOException {
        try {
            Manifest manifest = JSON.readValue(Files.readString(collectionDir.resolve(FILE_NAME)), Manifest.class);
            if (manifest == null || manifest.name() == null) {
                throw new IOException("The manifest in " + collectionDir + " has no name");
            }
            return manifest;
        } catch (JacksonException e) {
            throw new IOException("The manifest in " + collectionDir + " is not valid: " + e.getOriginalMessage(), e);
        }
    }
}
