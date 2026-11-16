package ru.it_spectrum.ai.rag.mcp.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import ru.it_spectrum.ai.rag.mcp.TestKnowledge;
import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.IndexesLoader;
import ru.it_spectrum.ai.rag.mcp.ingest.ChunkRecord;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceItem;
import ru.it_spectrum.ai.rag.mcp.ingest.SourcePipeline;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordDocumentsTest {

    @TempDir
    Path dataDir;

    private static final ChunkRecord RECORD = new ChunkRecord("article.billing.refunds#статусы-возврата",
            "article.billing.refunds", "Статусы возврата", "Возвраты › Статусы возврата",
            "| Статус | Что означает |", "refunds.md", 2,
            Map.of("kind", List.of("article"), "tables", List.of("REFUND", "PAYMENT")));

    @Test
    void aRecordIsADocumentWithItsFieldsAsMetadata() {
        Document document = RecordDocuments.toDocument(RECORD);

        assertThat(document.getId()).isEqualTo(RECORD.id());
        assertThat(document.getText()).isEqualTo(RECORD.text());
        assertThat(document.getMetadata())
                .containsEntry("kind", List.of("article"))
                .containsEntry("tables", List.of("REFUND", "PAYMENT"))
                .containsEntry(RecordDocuments.DOCUMENT, "article.billing.refunds")
                .containsEntry(RecordDocuments.BREADCRUMB, "Возвраты › Статусы возврата")
                .containsEntry(RecordDocuments.SOURCE, "refunds.md")
                .containsEntry(RecordDocuments.ORDINAL, 2);
        assertThat(RecordDocuments.toRecord(document)).isEqualTo(RECORD);
    }

    @Test
    void absentPartsAndEmptyTextSurviveTheRoundTrip() {
        ChunkRecord bare = new ChunkRecord("x", null, null, null, "", null, 0, Map.of());

        Document document = RecordDocuments.toDocument(bare);

        assertThat(document.getMetadata()).containsOnlyKeys(RecordDocuments.ORDINAL);
        assertThat(RecordDocuments.toRecord(document)).isEqualTo(bare);
    }

    @Test
    void aFieldMayNotUseTheReservedPrefix() {
        ChunkRecord clash = new ChunkRecord("x", "d", "t", "t", "text", "f.md", 0,
                Map.of("rag.title", List.of("v")));

        assertThatThrownBy(() -> RecordDocuments.toDocument(clash))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'rag.title'");
    }

    @Test
    void aDocumentFromElsewhereKeepsItsScalarMetadataAsFields() {
        Document foreign = Document.builder().id("manual-1").text("Page text")
                .metadata(Map.of("file_name", "manual.pdf", "page_number", 3, "draft", false,
                        "authors", List.of("A", "B"), "layout", Map.of("columns", 2)))
                .build();

        ChunkRecord record = RecordDocuments.toRecord(foreign);

        assertThat(record.id()).isEqualTo("manual-1");
        assertThat(record.text()).isEqualTo("Page text");
        assertThat(record.document()).isNull();
        assertThat(record.ordinal()).isZero();
        assertThat(record.fields()).containsOnlyKeys("file_name", "page_number", "draft", "authors")
                .containsEntry("page_number", List.of("3"))
                .containsEntry("authors", List.of("A", "B"));
    }

    @Test
    void aSourceReadsAsTheDocumentsOfItsRecords() throws IOException {
        Path file = TestKnowledge.writeIndexesFile(dataDir);
        IndexDefinition docs = StepRegistry.builtIn().checked(IndexesLoader.load(file, name -> null))
                .stream().filter(d -> d.name().equals(TestKnowledge.DOCS)).findFirst().orElseThrow();
        SourcePipeline pipeline = StepRegistry.builtIn().pipeline(docs.sources().getFirst());
        List<ChunkRecord> records = new ArrayList<>();
        for (SourceItem item : pipeline.items()) {
            records.addAll(pipeline.records(item));
        }

        List<Document> all = SourceDocumentReader.of(pipeline).read();
        List<Document> first = SourceDocumentReader.of(pipeline, pipeline.items().getFirst()).get();

        assertThat(records).isNotEmpty();
        assertThat(all.stream().map(RecordDocuments::toRecord).toList()).isEqualTo(records);
        assertThat(first).isNotEmpty().allSatisfy(document ->
                assertThat(document.getMetadata().get(RecordDocuments.SOURCE)).isEqualTo(
                        pipeline.items().getFirst().path()));
    }
}
