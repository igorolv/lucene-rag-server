package ru.it_spectrum.ai.rag.mcp.embedding;

import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Vectors already computed, by a hash of the model fingerprint and the embedded text, in a Lucene
 * index under {@code <data-dir>/cache/embeddings/} (docs/decisions.md D27). A rebuild embeds only
 * the records whose text changed; indexes built with the same model share the entries.
 *
 * <p>The cache is derived like an index: deleting the directory costs only time. It is never
 * pruned. While a build holds it, another build runs without it.
 */
public final class EmbeddingCache implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingCache.class);

    private static final String KEY = "key";
    private static final String VECTOR = "vector";
    private static final String CUT = "cut";

    /**
     * A cached vector.
     *
     * @param vector the unit-length vector
     * @param cut    the text was longer than the model's input limit
     */
    public record Entry(float[] vector, boolean cut) {
    }

    private final Directory directory;
    private final IndexWriter writer;
    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final Map<String, Entry> added = new HashMap<>();

    private EmbeddingCache(Directory directory, IndexWriter writer, DirectoryReader reader) {
        this.directory = directory;
        this.writer = writer;
        this.reader = reader;
        this.searcher = reader == null ? null : new IndexSearcher(reader);
    }

    /** A cache that keeps nothing. */
    public static EmbeddingCache none() {
        return new EmbeddingCache(null, null, null);
    }

    /**
     * Opens the cache in {@code dir}, creating it when absent. When another build holds it, or it
     * cannot be opened, the build goes on without a cache.
     */
    public static EmbeddingCache open(Path dir) {
        Directory directory = null;
        IndexWriter writer = null;
        try {
            Files.createDirectories(dir);
            directory = FSDirectory.open(dir);
            writer = new IndexWriter(directory, new IndexWriterConfig(new KeywordAnalyzer()));
            return new EmbeddingCache(directory, writer, DirectoryReader.open(writer));
        } catch (LockObtainFailedException e) {
            log.warn("The embedding cache {} is in use by another build; this build embeds every record", dir);
        } catch (IOException | RuntimeException e) {
            log.warn("The embedding cache {} cannot be opened ({}); this build embeds every record", dir, e.getMessage());
        }
        closeQuietly(writer);
        closeQuietly(directory);
        return none();
    }

    /** The cache key of a text embedded by a model. */
    public static String key(String fingerprint, String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(fingerprint.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The cached entry, or {@code null}. */
    public Entry get(String key) throws IOException {
        Entry entry = added.get(key);
        if (entry != null || searcher == null) {
            return entry;
        }
        TopDocs top = searcher.search(new TermQuery(new Term(KEY, key)), 1);
        if (top.scoreDocs.length == 0) {
            return null;
        }
        Document doc = searcher.storedFields().document(top.scoreDocs[0].doc);
        BytesRef bytes = doc.getBinaryValue(VECTOR);
        ByteBuffer buffer = ByteBuffer.wrap(bytes.bytes, bytes.offset, bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[bytes.length / Float.BYTES];
        buffer.asFloatBuffer().get(result);
        Number cut = doc.getField(CUT) == null ? null : doc.getField(CUT).numericValue();
        return new Entry(result, cut != null && cut.intValue() == 1);
    }

    public void put(String key, float[] vector, boolean cut) throws IOException {
        if (writer == null || added.containsKey(key)) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(vector);
        Document doc = new Document();
        doc.add(new StringField(KEY, key, Field.Store.NO));
        doc.add(new StoredField(VECTOR, buffer.array()));
        doc.add(new StoredField(CUT, cut ? 1 : 0));
        writer.addDocument(doc);
        added.put(key, new Entry(vector, cut));
    }

    /** Commits what was added. */
    @Override
    public void close() throws IOException {
        try {
            if (writer != null) {
                writer.commit();
            }
        } finally {
            closeQuietly(reader);
            closeQuietly(writer);
            closeQuietly(directory);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            log.warn("Cannot close the embedding cache: {}", e.getMessage());
        }
    }
}
