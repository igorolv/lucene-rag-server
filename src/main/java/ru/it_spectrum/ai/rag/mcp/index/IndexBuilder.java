package ru.it_spectrum.ai.rag.mcp.index;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.DocumentWriter;
import org.springframework.ai.document.MetadataMode;
import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.ConvertStep;
import ru.it_spectrum.ai.rag.mcp.definition.JoinDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.ModelDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.SourceDefinition;
import ru.it_spectrum.ai.rag.mcp.document.RecordContentFormatter;
import ru.it_spectrum.ai.rag.mcp.document.RecordDocuments;
import ru.it_spectrum.ai.rag.mcp.document.SourceDocumentReader;
import ru.it_spectrum.ai.rag.mcp.embedding.EmbeddingCache;
import ru.it_spectrum.ai.rag.mcp.embedding.EmbeddingModels;
import ru.it_spectrum.ai.rag.mcp.embedding.Embeddings;
import ru.it_spectrum.ai.rag.mcp.embedding.ModelInfo;
import ru.it_spectrum.ai.rag.mcp.embedding.ModelParameters;
import ru.it_spectrum.ai.rag.mcp.embedding.VectorModel;
import ru.it_spectrum.ai.rag.mcp.ingest.ChunkRecord;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceItem;
import ru.it_spectrum.ai.rag.mcp.ingest.SourcePipeline;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * Builds one index from its sources into {@code <data-dir>/<index>/}. How a source
 * becomes records is the business of its {@link SourcePipeline}; no step is named here. The
 * records of an item come as Spring AI documents ({@link SourceDocumentReader}) and are written by
 * a {@link DocumentWriter} (docs/decisions.md D31).
 *
 * <p>An index with {@code vectors} gets a vector field per record, embedded in batches by its
 * model; a vector already in the embedding cache is taken from there.
 *
 * <p>The index is written into a temporary sibling directory and replaces the previous one only
 * after it is complete, so a failed build leaves the old index in place. The old directory is
 * renamed aside before the new one is moved in, so an index is never half deleted; on Windows
 * the rename fails while another process has a file of the old index open, and the build fails
 * with the old index intact.
 */
public final class IndexBuilder {

    private static final String BUILD_PREFIX = ".building-";
    /** A replaced collection directory waiting to be removed; a name with a dot is never served. */
    private static final String OLD_PREFIX = ".old-";

    private static final Logger log = LoggerFactory.getLogger(IndexBuilder.class);

    /** Directory of derived data shared by collections, under the data directory. */
    public static final String CACHE_DIR = "cache";
    /** The embedding cache, under {@link #CACHE_DIR}. */
    public static final String EMBEDDINGS_DIR = "embeddings";

    private IndexBuilder() {
    }

    /**
     * Builds a collection without vectors.
     *
     * @throws IllegalArgumentException when the definition is unusable, has vectors or two records
     *                                  share an id
     * @see #build(IndexDefinition, StepRegistry, EmbeddingModels, Path, String)
     */
    public static Manifest build(IndexDefinition definition, StepRegistry registry, Path dataDir,
                                 String serverVersion) throws IOException {
        return build(definition, registry, null, dataDir, serverVersion).manifest();
    }

    /**
     * @param definition    a usable collection definition
     * @param registry      the steps sources are built with
     * @param models        the embedding models; may be {@code null} for a collection without vectors
     * @param dataDir       server data directory
     * @param serverVersion recorded in the manifest
     * @return the manifest of the collection just built and where its vectors came from
     * @throws IOException              when a source cannot be read, the model cannot be loaded or
     *                                  the index cannot be written
     * @throws IllegalArgumentException when the definition is unusable or two records share an id
     */
    public static Built build(IndexDefinition definition, StepRegistry registry, EmbeddingModels models,
                              Path dataDir, String serverVersion) throws IOException {
        if (!definition.usable()) {
            throw new IllegalArgumentException("Collection " + definition.name() + ": " + definition.configError());
        }
        VectorModel model = null;
        ModelDefinition vectors = definition.vectors();
        if (vectors != null) {
            if (models == null) {
                throw new IllegalArgumentException("Collection " + definition.name()
                        + " has vectors, and no embedding models are available");
            }
            model = models.load(vectors.name(), new ModelParameters(vectors.type(), vectors.queryPrefix(),
                    vectors.passagePrefix(), vectors.maxTokens(), vectors.pooling()));
        }
        Files.createDirectories(dataDir);
        Path target = dataDir.resolve(definition.name());
        Path building = dataDir.resolve(BUILD_PREFIX + definition.name() + "-" + System.nanoTime());
        try {
            Files.createDirectories(building);
            final Built built;
            try (EmbeddingCache cache = model == null ? EmbeddingCache.none()
                    : EmbeddingCache.open(dataDir.resolve(CACHE_DIR).resolve(EMBEDDINGS_DIR))) {
                built = write(definition, registry, model, cache, building, serverVersion);
            }
            swap(building, target, dataDir.resolve(OLD_PREFIX + definition.name() + "-" + System.nanoTime()));
            return built;
        } finally {
            deleteTree(building);
        }
    }

    /**
     * Puts a complete build in place of a collection. The old directory is renamed aside first, a
     * single step that either happens or not, so a collection is never left half deleted; it is
     * then removed, and what cannot be removed now (a file another process holds open) is left to
     * the next build.
     */
    private static void swap(Path building, Path target, Path old) throws IOException {
        boolean replaced = false;
        if (Files.exists(target)) {
            try {
                Files.move(target, old);
            } catch (IOException e) {
                throw new IOException("The previous index of " + target.getFileName()
                        + " is in use and was kept; build again: " + e.getMessage(), e);
            }
            replaced = true;
        }
        try {
            Files.move(building, target);
        } catch (IOException e) {
            if (replaced) {
                Files.move(old, target);
            }
            throw e;
        }
        removeLeftovers(target.getParent(), target.getFileName().toString());
    }

    /** Removes old directories of a collection, those of earlier builds included; skips what is in use. */
    private static void removeLeftovers(Path dataDir, String name) {
        String prefix = OLD_PREFIX + name + "-";
        try (Stream<Path> children = Files.list(dataDir)) {
            for (Path dir : (Iterable<Path>) children.filter(p -> p.getFileName().toString().startsWith(prefix)
                    && Files.isDirectory(p))::iterator) {
                try {
                    deleteTree(dir);
                } catch (IOException e) {
                    log.info("Old index {} is still in use and stays until the next build", dir.getFileName());
                }
            }
        } catch (IOException e) {
            log.info("Old indexes of {} were not removed: {}", name, e.getMessage());
        }
    }

    /**
     * A collection just built.
     *
     * @param manifest its manifest
     * @param embedded records the model embedded in this build
     * @param cached   records whose vector came from the embedding cache
     */
    public record Built(Manifest manifest, int embedded, int cached) {
    }

    private static Built write(IndexDefinition definition, StepRegistry registry, VectorModel model,
                               EmbeddingCache cache, Path dir, String serverVersion) throws IOException {
        int files = 0;
        int documents = 0;
        List<Manifest.Source> sources = new ArrayList<>();
        try (var directory = FSDirectory.open(dir.resolve(Manifest.INDEX_DIR));
             var writer = new IndexWriter(directory, new IndexWriterConfig(Analyzers.analyzer()))) {
            Batch batch = new Batch(definition.name(), writer, model, cache);
            Records records = new Records(batch);
            for (SourceDefinition source : definition.sources()) {
                SourcePipeline pipeline = registry.pipeline(source);
                List<String> keys = source.map().keys() == null ? List.of() : source.map().keys();
                sources.add(new Manifest.Source(source.path() == null ? null : source.path().toString(),
                        source.include(), source.exclude(), pipeline.format(), source.split(), source.map(),
                        source.command() == null ? null : source.command().command(),
                        source.command() == null ? null : source.command().timeoutSeconds(),
                        source.convert().stream().map(ConvertStep::settings).toList(), source.where(),
                        source.joins().stream().map(join -> manifestJoin(join, pipeline)).toList()));
                for (SourceItem item : pipeline.items()) {
                    files++;
                    var read = SourceDocumentReader.of(pipeline, item).get();
                    if (!read.isEmpty()) {
                        documents++;
                    }
                    records.from(item.location(), keys, pipeline.sections());
                    records.write(read);
                }
            }
            batch.flush();
            writer.commit();
            Manifest.Vectors vectors = null;
            if (model != null) {
                ModelInfo info = model.info();
                ModelParameters parameters = model.parameters();
                vectors = new Manifest.Vectors(model.name(), parameters.type(), parameters.queryPrefix(),
                        parameters.passagePrefix(), parameters.maxTokens(), info.dimension(), info.files(),
                        batch.truncated, parameters.pooling());
            }
            Manifest manifest = new Manifest(Manifest.SCHEMA, definition.name(), definition.description(),
                    Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), serverVersion,
                    files, documents, records.count, List.copyOf(records.fieldNames), List.copyOf(records.keyNames),
                    List.copyOf(sources), vectors);
            manifest.write(dir);
            return new Built(manifest, batch.embedded, batch.cached);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * The records of a collection written as Spring AI documents: checks that ids are unique,
     * collects the field and key names for the manifest and hands each record to the batch.
     * {@link #accept} throws {@link UncheckedIOException} when the index cannot be written.
     */
    private static final class Records implements DocumentWriter {

        private final Batch batch;
        private final Map<String, String> ids = new HashMap<>();
        final TreeSet<String> fieldNames = new TreeSet<>();
        final TreeSet<String> keyNames = new TreeSet<>();
        int count;
        private String location = "";
        private List<String> keys = List.of();
        private boolean sections;

        Records(Batch batch) {
            this.batch = batch;
        }

        /** Where the next documents come from, for messages, and the key fields of their source. */
        void from(String location, List<String> keys, boolean sections) {
            this.location = location;
            this.keys = keys;
            this.sections = sections;
        }

        @Override
        public void accept(List<org.springframework.ai.document.Document> documents) {
            for (var document : documents) {
                ChunkRecord record = RecordDocuments.toRecord(document);
                String other = ids.putIfAbsent(record.id(), location);
                if (other != null) {
                    throw new IllegalArgumentException("Two records share the id '" + record.id()
                            + "': " + other + " and " + location
                            + ". Make the document keys or the map.id template distinguish them");
                }
                fieldNames.addAll(record.fields().keySet());
                for (String key : keys) {
                    if (record.fields().containsKey(key)) {
                        keyNames.add(key);
                    }
                }
                try {
                    batch.add(record, document, keys, sections ? record.document() : record.id());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                count++;
            }
        }
    }

    /**
     * Records waiting to be written, so that the model embeds them in batches. What a record is
     * embedded as is its document formatted for {@code MetadataMode.EMBED} by a
     * {@link RecordContentFormatter} (docs/decisions.md D44).
     */
    private static final class Batch {

        private static final int SIZE = 64;

        private final String collection;
        private final IndexWriter writer;
        private final VectorModel model;
        private final EmbeddingCache cache;
        private final String fingerprint;
        private final RecordContentFormatter formatter;
        private final List<ChunkRecord> records = new ArrayList<>();
        private final List<org.springframework.ai.document.Document> documents = new ArrayList<>();
        private final List<List<String>> keys = new ArrayList<>();
        private final List<String> groups = new ArrayList<>();
        int embedded;
        int cached;
        int truncated;

        Batch(String collection, IndexWriter writer, VectorModel model, EmbeddingCache cache) {
            this.collection = collection;
            this.writer = writer;
            this.model = model;
            this.cache = cache;
            this.fingerprint = model == null ? null : model.fingerprint();
            this.formatter = model == null ? null : new RecordContentFormatter(model.parameters().passagePrefix());
        }

        void add(ChunkRecord record, org.springframework.ai.document.Document document, List<String> recordKeys,
                 String group) throws IOException {
            records.add(record);
            documents.add(document);
            keys.add(recordKeys);
            groups.add(group);
            if (model == null || records.size() >= SIZE) {
                flush();
            }
        }

        void flush() throws IOException {
            if (records.isEmpty()) {
                return;
            }
            float[][] vectors = model == null ? null : vectors();
            for (int i = 0; i < records.size(); i++) {
                Document doc = document(collection, records.get(i), keys.get(i));
                doc.add(new StoredField(IndexFields.STORED_GROUP, groups.get(i)));
                if (vectors != null) {
                    doc.add(new KnnFloatVectorField(IndexFields.VECTOR, vectors[i], VectorSimilarityFunction.COSINE));
                }
                writer.addDocument(doc);
            }
            records.clear();
            documents.clear();
            keys.clear();
            groups.clear();
        }

        private float[][] vectors() throws IOException {
            float[][] vectors = new float[records.size()][];
            String[] cacheKeys = new String[records.size()];
            List<Integer> missing = new ArrayList<>();
            List<org.springframework.ai.document.Document> toEmbed = new ArrayList<>();
            for (int i = 0; i < records.size(); i++) {
                var document = formatter.apply(documents.get(i));
                String input = document.getFormattedContent(MetadataMode.EMBED);
                cacheKeys[i] = EmbeddingCache.key(fingerprint, input);
                EmbeddingCache.Entry entry = cache.get(cacheKeys[i]);
                if (entry == null) {
                    missing.add(i);
                    toEmbed.add(document);
                } else {
                    vectors[i] = entry.vector();
                    cached++;
                    if (entry.cut()) {
                        truncated++;
                    }
                }
            }
            if (!missing.isEmpty()) {
                Embeddings embeddings = model.embed(toEmbed);
                truncated += embeddings.truncated();
                for (int j = 0; j < missing.size(); j++) {
                    int i = missing.get(j);
                    vectors[i] = embeddings.vectors()[j];
                    cache.put(cacheKeys[i], vectors[i], embeddings.cut()[j]);
                }
                embedded += missing.size();
            }
            return vectors;
        }
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

    private static Manifest.Join manifestJoin(JoinDefinition join, SourcePipeline pipeline) {
        SourceDefinition read = join.source();
        return new Manifest.Join(join.name(), read.path() == null ? null : read.path().toString(), read.include(),
                read.exclude(), pipeline.lookupFormats().get(join.name()), read.split(),
                read.command() == null ? null : read.command().command(),
                read.command() == null ? null : read.command().timeoutSeconds(),
                read.convert().stream().map(ConvertStep::settings).toList(), join.key(), join.on());
    }
}
