package ru.it_spectrum.ai.rag.mcp.cli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.TestKnowledge;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.embedding.EmbeddingModels;
import ru.it_spectrum.ai.rag.mcp.embedding.FakeEmbeddingModel;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.it_spectrum.ai.rag.mcp.TestKnowledge.DOCS;

/** rag-index and rag-search on an index with vectors of the fake model. */
class VectorCommandsTest {

    @TempDir
    static Path dataDir;

    static RagMcpProperties properties;
    static EmbeddingModels models;
    static String indexOutput;

    @BeforeAll
    static void build() throws IOException {
        TestKnowledge.writeIndexesFile(dataDir, true);
        properties = new RagMcpProperties(dataDir.toString(), null);
        models = FakeEmbeddingModel.models(dataDir, "fake");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = RagIndexCommand.run(new String[]{"--all"}, properties, StepRegistry.builtIn(), models,
                new PrintStream(out, true, StandardCharsets.UTF_8), name -> null, "test");
        indexOutput = out.toString(StandardCharsets.UTF_8);
        assertThat(code).as(indexOutput).isZero();
    }

    private static String search(int expectedCode, EmbeddingModels with, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = RagSearchCommand.run(args, properties, with, new PrintStream(out, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(code).as(text).isEqualTo(expectedCode);
        return text;
    }

    @Test
    void indexReportsTheVectors() {
        assertThat(indexOutput).contains("  model: files in " + dataDir.resolve("models").resolve("fake"));
        assertThat(indexOutput).contains("vectors: model fake, 64 dimensions, ")
                .contains(" embedded, 0 from cache, 0 cut to 512 tokens")
                .contains("RESULT: OK (2 indexes built)");
    }

    @Test
    void searchTakesAMode() {
        assertThat(search(0, models, DOCS, "отмена", "платежей")).contains("RESULT: OK").contains(", hybrid)");
        assertThat(search(0, models, DOCS, "отмена", "платежей", "--mode", "vector")).contains(", vector)");
        assertThat(search(0, models, DOCS, "отмена", "платежей", "--mode", "lexical"))
                .contains("article.billing.payments#отмена-платежа").contains(", lexical)");
        assertThat(search(2, models, DOCS, "отмена", "--mode", "semantic")).contains("Unknown mode");
    }

    @Test
    void aMissingModelFailsVectorSearchWithItsOwnCode(@TempDir Path empty) {
        var none = EmbeddingModels.inProcess(empty);

        assertThat(search(5, none, DOCS, "отмена", "--mode", "vector")).contains("needs model 'fake'");
        assertThat(search(0, none, DOCS, "отмена", "--mode", "lexical")).contains("RESULT: OK");
    }
}
