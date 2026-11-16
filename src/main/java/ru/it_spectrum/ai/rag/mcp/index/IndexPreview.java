package ru.it_spectrum.ai.rag.mcp.index;

import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.SourceDefinition;
import ru.it_spectrum.ai.rag.mcp.document.RecordDocuments;
import ru.it_spectrum.ai.rag.mcp.document.SourceDocumentReader;
import ru.it_spectrum.ai.rag.mcp.ingest.ChunkRecord;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceItem;
import ru.it_spectrum.ai.rag.mcp.ingest.SourcePipeline;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The records an index would get, without building it (docs/decisions.md D93): every source is
 * read through its steps as {@link IndexBuilder} reads it, {@code where} and the checks of a build
 * included, but nothing is embedded and nothing is written. What it gives is what an operator,
 * or an agent writing the indexes file, needs to judge {@code split} and {@code map}: the counts
 * of each source, the fields with their values, and a few records in full.
 */
public final class IndexPreview {

    /** A field with at most this many different values in a source lists them. */
    public static final int FEW_VALUES = 5;

    private IndexPreview() {
    }

    /**
     * @param project the project of the index; it is not a field of the previewed records
     * @param sources one entry per source, in the order of the definition
     */
    public record Result(String project, List<Source> sources) {

        public int files() {
            return sources.stream().mapToInt(Source::files).sum();
        }

        public int documents() {
            return sources.stream().mapToInt(Source::documents).sum();
        }

        public int records() {
            return sources.stream().mapToInt(Source::records).sum();
        }
    }

    /**
     * @param label     the path of the source, or its command line
     * @param format    the format it is read with
     * @param files     items read
     * @param documents items that gave at least one record
     * @param records   records kept after {@code where}
     * @param longest   characters of the longest record as it is embedded: breadcrumb, a blank
     *                  line and text
     * @param fields    per field name, sorted: its values as first written with the number of
     *                  records of each, when there are at most {@link #FEW_VALUES} of them;
     *                  otherwise {@code null}, and {@link #distinct} says how many
     * @param distinct  per field name: the number of different values
     * @param samples   records spread over the items: the first record of each item, then the
     *                  second, until there are as many as asked for
     */
    public record Source(String label, String format, int files, int documents, int records, int longest,
                         Map<String, Map<String, Integer>> fields, Map<String, Integer> distinct,
                         List<ChunkRecord> samples) {
    }

    /**
     * @param samples how many records of each source to keep in full
     * @throws IOException              when a source cannot be read or a command fails
     * @throws IllegalArgumentException when the definition is unusable, a file does not fit its
     *                                  format or mapping, or two records share an id
     */
    public static Result preview(IndexDefinition definition, StepRegistry registry, int samples) throws IOException {
        if (!definition.usable()) {
            throw new IllegalArgumentException("Index " + definition.name() + ": " + definition.configError());
        }
        Map<String, String> ids = new HashMap<>();
        List<Source> sources = new ArrayList<>();
        try {
            for (SourceDefinition source : definition.sources()) {
                sources.add(source(source, registry.pipeline(source), samples, ids));
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return new Result(definition.project(), List.copyOf(sources));
    }

    private static Source source(SourceDefinition source, SourcePipeline pipeline, int samples,
                                 Map<String, String> ids) throws IOException {
        int files = 0;
        int documents = 0;
        int records = 0;
        int longest = 0;
        Map<String, Map<String, Value>> values = new TreeMap<>();
        List<List<ChunkRecord>> firsts = new ArrayList<>();
        for (SourceItem item : pipeline.items()) {
            files++;
            List<ChunkRecord> read = SourceDocumentReader.of(pipeline, item).get().stream()
                    .map(RecordDocuments::toRecord).toList();
            if (read.isEmpty()) {
                continue;
            }
            documents++;
            for (ChunkRecord record : read) {
                String other = ids.putIfAbsent(record.id(), item.location());
                if (other != null) {
                    throw new IllegalArgumentException("Two records share the id '" + record.id() + "': "
                            + other + " and " + item.location()
                            + ". Make the document keys or the map.id template distinguish them");
                }
                records++;
                longest = Math.max(longest, embedded(record).length());
                count(values, record);
            }
            if (firsts.size() < samples) {
                firsts.add(read.subList(0, Math.min(samples, read.size())));
            }
        }
        Map<String, Map<String, Integer>> fields = new TreeMap<>();
        Map<String, Integer> distinct = new TreeMap<>();
        for (var field : values.entrySet()) {
            distinct.put(field.getKey(), field.getValue().size());
            if (field.getValue().size() <= FEW_VALUES) {
                Map<String, Integer> listed = new LinkedHashMap<>();
                field.getValue().values().stream()
                        .sorted((a, b) -> b.records != a.records ? Integer.compare(b.records, a.records)
                                : a.value.compareTo(b.value))
                        .forEach(value -> listed.put(value.value, value.records));
                fields.put(field.getKey(), listed);
            } else {
                fields.put(field.getKey(), null);
            }
        }
        String label = source.path() != null ? source.path().toString().replace('\\', '/')
                : String.join(" ", source.command().command());
        return new Source(label, pipeline.format(), files, documents, records, longest, fields, distinct,
                spread(firsts, samples));
    }

    /** What the model is given of a record, without the passage prefix ({@code RecordContentFormatter}). */
    private static String embedded(ChunkRecord record) {
        String breadcrumb = record.breadcrumb() == null ? "" : record.breadcrumb().strip();
        return breadcrumb.isEmpty() ? record.text() : breadcrumb + "\n\n" + record.text();
    }

    private static void count(Map<String, Map<String, Value>> values, ChunkRecord record) {
        for (var field : record.fields().entrySet()) {
            Map<String, Value> known = values.computeIfAbsent(field.getKey(), name -> new LinkedHashMap<>());
            List<String> seen = new ArrayList<>();
            for (String value : field.getValue()) {
                String term = IndexBuilder.filterTerm(value);
                if (!seen.contains(term)) {
                    seen.add(term);
                    known.computeIfAbsent(term, t -> new Value(value.strip())).records++;
                }
            }
        }
    }

    /** The first record of each item, then the second, and so on, up to {@code samples}. */
    private static List<ChunkRecord> spread(List<List<ChunkRecord>> firsts, int samples) {
        List<ChunkRecord> result = new ArrayList<>();
        for (int round = 0; result.size() < samples; round++) {
            boolean any = false;
            for (List<ChunkRecord> records : firsts) {
                if (round < records.size() && result.size() < samples) {
                    result.add(records.get(round));
                    any = true;
                }
            }
            if (!any) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private static final class Value {
        final String value;
        int records;

        Value(String value) {
            this.value = value;
        }
    }
}
