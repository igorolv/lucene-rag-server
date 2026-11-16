package ru.it_spectrum.ai.rag.mcp.index;

import ru.it_spectrum.ai.rag.mcp.definition.MapSettings;
import ru.it_spectrum.ai.rag.mcp.definition.SplitSettings;
import ru.it_spectrum.ai.rag.mcp.definition.WhereSettings;
import ru.it_spectrum.ai.rag.mcp.embedding.ModelParameters;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Passport of a built index, {@code <index>/manifest.json}. Everything the server needs
 * to serve the index without its sources and without {@code indexes.json} is here.
 *
 * @param schema        index schema the index was built with; see {@link #SCHEMA}
 * @param name          index name; equals the directory name
 * @param description   what the index holds, from {@code indexes.json}
 * @param project       the project of the index, also the field {@code project} of each record,
 *                      or {@code null} for none (docs/decisions.md D83)
 * @param builtAt       build time, ISO-8601 UTC
 * @param serverVersion version of the server that built the index
 * @param files         source files read
 * @param documents     documents that produced at least one record
 * @param records       records in the index
 * @param fields        names of the document fields usable as filters, sorted
 * @param fieldValues   what values each field of {@code fields} has, by field name
 * @param keys          names of the fields the {@code lookup} tool searches, sorted; empty for none
 * @param sources       what the index was built from
 * @param vectors       the model the records were embedded with, or {@code null} for an index
 *                      searched lexically only
 */
public record Manifest(int schema, String name, String description, String project, String builtAt,
                       String serverVersion, int files, int documents, int records, List<String> fields,
                       Map<String, FieldValues> fieldValues, List<String> keys, List<Source> sources,
                       Vectors vectors) {

    public Manifest {
        keys = keys == null ? List.of() : List.copyOf(keys);
        fieldValues = fieldValues == null ? Map.of() : Map.copyOf(fieldValues);
    }

    /** Raised whenever fields or their analysis change in a way an old index cannot serve. */
    public static final int SCHEMA = 11;

    /**
     * How many values of a field are few enough to be listed in {@link FieldValues#values()}. A
     * first guess (docs/decisions.md D89).
     */
    public static final int FEW_VALUES = 15;

    /**
     * The values of one field over the records of the index. Values are told apart as filters
     * compare them: ignoring case and surrounding spaces.
     *
     * @param distinct how many different values the field has
     * @param values   each value as the first record that has it writes it, with the number of
     *                 records that have it, most records first; {@code null} when there are more
     *                 than {@link #FEW_VALUES}
     */
    public record FieldValues(int distinct, List<Value> values) {

        public FieldValues {
            values = values == null ? null : List.copyOf(values);
        }
    }

    /** A value of a field and the number of records that have it. */
    public record Value(String value, int records) { }

    /**
     * The embedding model of an index with vectors: everything search needs to embed a query
     * the same way and to tell whether the model at hand is the one the records were embedded
     * with (docs/decisions.md D7).
     *
     * @param model         name of the model; its files are in {@code <data-dir>/models/<model>/}
     * @param type          how the model is run
     * @param queryPrefix   put before a query
     * @param passagePrefix put before a record's text
     * @param maxTokens     input limit in tokens
     * @param dimension     length of a vector
     * @param files         SHA-256 of each model file, by file name
     * @param truncated     records longer than {@code maxTokens} whose vector covers only their beginning
     */
    public record Vectors(String model, String type, String queryPrefix, String passagePrefix, int maxTokens,
                          int dimension, Map<String, String> files, int truncated, String pooling) {

        public ModelParameters parameters() {
            return new ModelParameters(type, queryPrefix, passagePrefix, maxTokens, pooling);
        }
    }

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
     * @param joins          the lookups of the source; empty for none
     */
    public record Source(String path, String include, String exclude, String format,
                         SplitSettings split, MapSettings map, List<String> command, Integer timeoutSeconds,
                         List<Map<String, Object>> convert, WhereSettings where, List<Join> joins) {

        public Source {
            joins = joins == null ? List.of() : List.copyOf(joins);
        }
    }

    /**
     * A lookup of a source as it was built ({@code join} in the indexes file).
     *
     * @param name   the prefix of the values the lookup gave
     * @param format name of the format step the lookup was read with
     * @param key    template of the key of a unit of the lookup
     * @param on     template of the key a unit of the source looked for
     */
    public record Join(String name, String path, String include, String exclude, String format,
                       SplitSettings split, List<String> command, Integer timeoutSeconds,
                       List<Map<String, Object>> convert, String key, String on) { }

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    public void write(Path indexDir) throws IOException {
        try {
            Files.writeString(indexDir.resolve(FILE_NAME), JSON.writeValueAsString(this));
        } catch (JacksonException e) {
            throw new IOException("Cannot write the manifest: " + e.getOriginalMessage(), e);
        }
    }

    /** @throws IOException when the file is missing, unreadable or not a manifest */
    public static Manifest read(Path indexDir) throws IOException {
        try {
            Manifest manifest = JSON.readValue(Files.readString(indexDir.resolve(FILE_NAME)), Manifest.class);
            if (manifest == null || manifest.name() == null) {
                throw new IOException("The manifest in " + indexDir + " has no name");
            }
            return manifest;
        } catch (JacksonException e) {
            throw new IOException("The manifest in " + indexDir + " is not valid: " + e.getOriginalMessage(), e);
        }
    }
}
