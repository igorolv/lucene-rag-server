package ru.it_spectrum.ai.rag.mcp.index;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import ru.it_spectrum.ai.rag.mcp.collections.CollectionDefinition;
import ru.it_spectrum.ai.rag.mcp.collections.ConvertStep;
import ru.it_spectrum.ai.rag.mcp.collections.SourceDefinition;
import ru.it_spectrum.ai.rag.mcp.ingest.ChunkRecord;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceItem;
import ru.it_spectrum.ai.rag.mcp.ingest.SourcePipeline;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Builds one collection from its sources into {@code <data-dir>/<collection>/}. How a source
 * becomes records is the business of its {@link SourcePipeline}; no step is named here.
 *
 * <p>The index is written into a temporary sibling directory and replaces the previous one only
 * after it is complete, so a failed build leaves the old collection in place. The replacement is
 * a delete followed by a move: on Windows it fails while another process has the old index open.
 */
public final class CollectionIndexer {

    private static final String BUILD_PREFIX = ".building-";

    private CollectionIndexer() {
    }

    /**
     * @param definition    a usable collection definition
     * @param registry      the steps sources are built with
     * @param dataDir       server data directory
     * @param serverVersion recorded in the manifest
     * @return the manifest of the collection just built
     * @throws IOException              when a source cannot be read or the index cannot be written
     * @throws IllegalArgumentException when the definition is unusable or two records share an id
     */
    public static Manifest build(CollectionDefinition definition, StepRegistry registry, Path dataDir,
                                 String serverVersion) throws IOException {
        if (!definition.usable()) {
            throw new IllegalArgumentException("Collection " + definition.name() + ": " + definition.configError());
        }
        Files.createDirectories(dataDir);
        Path target = dataDir.resolve(definition.name());
        Path building = dataDir.resolve(BUILD_PREFIX + definition.name() + "-" + System.nanoTime());
        try {
            Files.createDirectories(building);
            Manifest manifest = write(definition, registry, building, serverVersion);
            deleteTree(target);
            Files.move(building, target);
            return manifest;
        } finally {
            deleteTree(building);
        }
    }

    private static Manifest write(CollectionDefinition definition, StepRegistry registry, Path dir,
                                  String serverVersion) throws IOException {
        int files = 0;
        int documents = 0;
        int records = 0;
        var fieldNames = new TreeSet<String>();
        var keyNames = new TreeSet<String>();
        Map<String, String> ids = new HashMap<>();
        List<Manifest.Source> sources = new ArrayList<>();
        try (var directory = FSDirectory.open(dir.resolve(Manifest.INDEX_DIR));
             var writer = new IndexWriter(directory, new IndexWriterConfig(Analyzers.analyzer()))) {
            for (SourceDefinition source : definition.sources()) {
                SourcePipeline pipeline = registry.pipeline(source);
                List<String> keys = source.map().keys() == null ? List.of() : source.map().keys();
                sources.add(new Manifest.Source(source.path() == null ? null : source.path().toString(),
                        source.include(), source.exclude(), pipeline.format(), source.split(), source.map(),
                        source.command() == null ? null : source.command().command(),
                        source.command() == null ? null : source.command().timeoutSeconds(),
                        source.convert().stream().map(ConvertStep::settings).toList(), source.where()));
                for (SourceItem item : pipeline.items()) {
                    files++;
                    List<ChunkRecord> chunks = pipeline.records(item);
                    if (!chunks.isEmpty()) {
                        documents++;
                    }
                    for (ChunkRecord record : chunks) {
                        String other = ids.putIfAbsent(record.id(), item.location());
                        if (other != null) {
                            throw new IllegalArgumentException("Two records share the id '" + record.id()
                                    + "': " + other + " and " + item.location()
                                    + ". Make the document keys or the map.id template distinguish them");
                        }
                        fieldNames.addAll(record.fields().keySet());
                        for (String key : keys) {
                            if (record.fields().containsKey(key)) {
                                keyNames.add(key);
                            }
                        }
                        writer.addDocument(document(definition.name(), record, keys));
                        records++;
                    }
                }
            }
            writer.commit();
        }
        Manifest manifest = new Manifest(Manifest.SCHEMA, definition.name(), definition.description(),
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), serverVersion,
                files, documents, records, List.copyOf(fieldNames), List.copyOf(keyNames), List.copyOf(sources));
        manifest.write(dir);
        return manifest;
    }

    private static Document document(String collection, ChunkRecord record, List<String> keys) {
        Document doc = new Document();
        doc.add(new StringField(IndexFields.ID, record.id(), Field.Store.YES));
        doc.add(new StringField(IndexFields.DOCUMENT, record.document(), Field.Store.YES));
        doc.add(new StoredField(IndexFields.COLLECTION, collection));
        doc.add(new TextField(IndexFields.TEXT, record.text(), Field.Store.NO));
        doc.add(new TextField(IndexFields.TEXT_EN, record.text(), Field.Store.NO));
        doc.add(new TextField(IndexFields.TITLE, record.breadcrumb(), Field.Store.NO));
        doc.add(new TextField(IndexFields.TITLE_EN, record.breadcrumb(), Field.Store.NO));
        doc.add(new StoredField(IndexFields.STORED_TEXT, record.text()));
        doc.add(new StoredField(IndexFields.STORED_TITLE, record.title()));
        doc.add(new StoredField(IndexFields.STORED_BREADCRUMB, record.breadcrumb()));
        doc.add(new StoredField(IndexFields.STORED_SOURCE, record.source()));
        doc.add(new StoredField(IndexFields.STORED_ORDINAL, record.ordinal()));
        for (var field : record.fields().entrySet()) {
            for (String value : field.getValue()) {
                doc.add(new StringField(IndexFields.FILTER_PREFIX + field.getKey(), filterTerm(value),
                        Field.Store.NO));
                doc.add(new StoredField(IndexFields.VALUE_PREFIX + field.getKey(), value));
            }
        }
        for (String key : keys) {
            for (String value : record.fields().getOrDefault(key, List.of())) {
                doc.add(new StringField(IndexFields.LOOKUP, filterTerm(value), Field.Store.NO));
            }
        }
        return doc;
    }

    /** The exact term a field value is indexed and filtered by. */
    public static String filterTerm(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(path);
            }
        }
    }
}
