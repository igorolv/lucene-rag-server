package ru.it_spectrum.ai.rag.mcp.document;

import org.springframework.ai.document.Document;
import ru.it_spectrum.ai.rag.mcp.ingest.ChunkRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one mapping between the server's record and Spring AI's {@link Document}
 * (docs/decisions.md D31).
 *
 * <p>The document's id and text are the record's. The document fields of the record are metadata
 * entries under their own names, each a list of strings, so Spring AI code reads them as it reads
 * any metadata. The other parts of the record are metadata under names starting with
 * {@value #RESERVED_PREFIX}, which a document field may not use. A part that is {@code null} has
 * no entry.
 *
 * <p>A document from elsewhere, such as one a Spring AI reader gives, becomes a record the same way:
 * a part without its entry is {@code null}, the ordinal 0, and every other metadata entry whose
 * value is a string, a number, a boolean or a collection of them is a field.
 */
public final class RecordDocuments {

    /** Prefix of the metadata names of the record's parts. */
    public static final String RESERVED_PREFIX = "rag.";
    public static final String DOCUMENT = RESERVED_PREFIX + "document";
    public static final String TITLE = RESERVED_PREFIX + "title";
    public static final String BREADCRUMB = RESERVED_PREFIX + "breadcrumb";
    public static final String SOURCE = RESERVED_PREFIX + "source";
    public static final String ORDINAL = RESERVED_PREFIX + "ordinal";

    private RecordDocuments() {
    }

    /**
     * @throws IllegalArgumentException when a field name starts with {@value #RESERVED_PREFIX}
     */
    public static Document toDocument(ChunkRecord record) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (var field : record.fields().entrySet()) {
            if (field.getKey().startsWith(RESERVED_PREFIX)) {
                throw new IllegalArgumentException("Record " + record.id() + ": the field name '" + field.getKey()
                        + "' starts with '" + RESERVED_PREFIX + "', which is reserved; rename it in map.fields");
            }
            metadata.put(field.getKey(), List.copyOf(field.getValue()));
        }
        putIfPresent(metadata, DOCUMENT, record.document());
        putIfPresent(metadata, TITLE, record.title());
        putIfPresent(metadata, BREADCRUMB, record.breadcrumb());
        putIfPresent(metadata, SOURCE, record.source());
        metadata.put(ORDINAL, record.ordinal());
        return Document.builder().id(record.id()).text(record.text()).metadata(metadata).build();
    }

    public static List<Document> toDocuments(List<ChunkRecord> records) {
        return records.stream().map(RecordDocuments::toDocument).toList();
    }

    /**
     * @throws IllegalArgumentException when the document has no text
     */
    public static ChunkRecord toRecord(Document document) {
        if (document.getText() == null) {
            throw new IllegalArgumentException("Document " + document.getId() + " has no text");
        }
        Map<String, Object> metadata = document.getMetadata();
        Map<String, List<String>> fields = new LinkedHashMap<>();
        for (var entry : metadata.entrySet()) {
            if (entry.getKey().startsWith(RESERVED_PREFIX)) {
                continue;
            }
            List<String> values = values(entry.getValue());
            if (values != null) {
                fields.put(entry.getKey(), values);
            }
        }
        Object ordinal = metadata.get(ORDINAL);
        return new ChunkRecord(document.getId(), string(metadata.get(DOCUMENT)), string(metadata.get(TITLE)),
                string(metadata.get(BREADCRUMB)), document.getText(), string(metadata.get(SOURCE)),
                ordinal instanceof Number number ? number.intValue() : 0, fields);
    }

    private static void putIfPresent(Map<String, Object> metadata, String name, String value) {
        if (value != null) {
            metadata.put(name, value);
        }
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    /** The values of a metadata entry as a field, or {@code null} when it is not one. */
    private static List<String> values(Object value) {
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return List.of(value.toString());
        }
        if (value instanceof Collection<?> items) {
            List<String> values = new ArrayList<>();
            for (Object item : items) {
                if (!(item instanceof CharSequence || item instanceof Number || item instanceof Boolean)) {
                    return null;
                }
                values.add(item.toString());
            }
            return List.copyOf(values);
        }
        return null;
    }
}
