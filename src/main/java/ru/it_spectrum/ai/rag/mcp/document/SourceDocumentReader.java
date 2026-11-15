package ru.it_spectrum.ai.rag.mcp.document;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceItem;
import ru.it_spectrum.ai.rag.mcp.ingest.SourcePipeline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The records of a source, or of one of its items, as a Spring AI {@link DocumentReader}
 * (docs/decisions.md D31): what the five steps of the source give, mapped by
 * {@link RecordDocuments}. The steps themselves keep their own shapes.
 *
 * <p>{@link #get()} throws {@link UncheckedIOException} when an item cannot be read or converted,
 * as the interface allows no checked exception, and {@link IllegalArgumentException} when an
 * item does not fit the format or the mapping.
 */
public final class SourceDocumentReader implements DocumentReader {

    private final SourcePipeline pipeline;
    private final SourceItem item;

    private SourceDocumentReader(SourcePipeline pipeline, SourceItem item) {
        this.pipeline = pipeline;
        this.item = item;
    }

    /** Every record of the source, item after item. */
    public static SourceDocumentReader of(SourcePipeline pipeline) {
        return new SourceDocumentReader(pipeline, null);
    }

    /** The records of one item of the source. */
    public static SourceDocumentReader of(SourcePipeline pipeline, SourceItem item) {
        return new SourceDocumentReader(pipeline, item);
    }

    @Override
    public List<Document> get() {
        try {
            if (item != null) {
                return RecordDocuments.toDocuments(pipeline.records(item));
            }
            List<Document> documents = new ArrayList<>();
            for (SourceItem each : pipeline.items()) {
                documents.addAll(RecordDocuments.toDocuments(pipeline.records(each)));
            }
            return documents;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
