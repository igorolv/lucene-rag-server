package ru.it_spectrum.ai.rag.mcp.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import ru.it_spectrum.ai.rag.mcp.embedding.CountBatchingStrategy;
import ru.it_spectrum.ai.rag.mcp.embedding.Embeddings;
import ru.it_spectrum.ai.rag.mcp.embedding.FakeEmbeddingModel;
import ru.it_spectrum.ai.rag.mcp.embedding.ModelParameters;
import ru.it_spectrum.ai.rag.mcp.embedding.VectorModel;
import ru.it_spectrum.ai.rag.mcp.ingest.ChunkRecord;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Records embedded as Spring AI documents (docs/decisions.md D44). */
class RecordContentFormatterTest {

    @TempDir
    Path dir;

    private static Document document(String breadcrumb, String text) {
        return RecordDocuments.toDocument(new ChunkRecord("id", "doc", "t", breadcrumb, text, "f.md", 0, Map.of()));
    }

    @Test
    void aRecordIsEmbeddedAsPrefixBreadcrumbAndText() {
        var formatter = new RecordContentFormatter("passage: ");
        Document document = formatter.apply(document("Возвраты › Статусы", "Текст"));

        assertThat(document.getFormattedContent(MetadataMode.EMBED)).isEqualTo("passage: Возвраты › Статусы\n\nТекст");
        assertThat(document.getFormattedContent(MetadataMode.INFERENCE)).isEqualTo("Возвраты › Статусы\n\nТекст");
        assertThat(document.getFormattedContent(MetadataMode.NONE)).isEqualTo("Текст");
        assertThat(formatter.apply(document("  ", "Текст")).getFormattedContent(MetadataMode.EMBED))
                .isEqualTo("passage: Текст");
        assertThat(new RecordContentFormatter(null).apply(document(null, "Текст"))
                .getFormattedContent(MetadataMode.EMBED)).isEqualTo("Текст");
    }

    @Test
    void documentsAreBatchedInOrder() {
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            documents.add(document("b", "t" + i));
        }

        List<List<Document>> batches = new CountBatchingStrategy(2).batch(documents);

        assertThat(batches).extracting(List::size).containsExactly(2, 2, 1);
        assertThat(batches.get(2).getFirst().getText()).isEqualTo("t4");
        assertThatThrownBy(() -> new CountBatchingStrategy(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aModelEmbedsDocumentsAsTheirFormattedContent() throws IOException {
        VectorModel model = FakeEmbeddingModel.models(dir, "fake")
                .load("fake", new ModelParameters(FakeEmbeddingModel.TYPE, "query: ", "passage: ", 4));
        var formatter = new RecordContentFormatter("passage: ");
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            documents.add(formatter.apply(document("Возвраты", i == 69 ? "один два три четыре пять" : "платёж " + i)));
        }

        Embeddings byDocument = model.embed(documents);
        Embeddings byText = model.embedInputs(documents.stream()
                .map(d -> d.getFormattedContent(MetadataMode.EMBED)).toList());

        assertThat(byDocument.vectors()).hasSameDimensionsAs(byText.vectors());
        for (int i = 0; i < 70; i++) {
            assertThat(byDocument.vectors()[i]).containsExactly(byText.vectors()[i]);
        }
        assertThat(byDocument.cut()[69]).as("the cut is kept across batches").isTrue();
        assertThat(byDocument.truncated()).isEqualTo(1);
    }
}
