package ru.it_spectrum.ai.rag.mcp.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.TestKnowledge;
import ru.it_spectrum.ai.rag.mcp.api.RebuildResult;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.embedding.EmbeddingModels;
import ru.it_spectrum.ai.rag.mcp.embedding.FakeEmbeddingModel;
import ru.it_spectrum.ai.rag.mcp.index.CollectionStore;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.it_spectrum.ai.rag.mcp.TestKnowledge.API;
import static ru.it_spectrum.ai.rag.mcp.TestKnowledge.DOCS;

/** {@code rebuildCollection} builds a collection of the collections file, as {@code rag-index} does. */
class AdminToolsTest {

    @TempDir
    Path dataDir;

    private AdminTools tools(boolean vectors) throws IOException {
        TestKnowledge.writeCollectionsFile(dataDir, vectors);
        RagMcpProperties properties = new RagMcpProperties(dataDir.toString(), null);
        return new AdminTools(properties, StepRegistry.builtIn(), FakeEmbeddingModel.models(dataDir, "fake"), name -> null);
    }

    @Test
    void aCollectionIsBuiltAndServed() throws IOException {
        RebuildResult result = tools(false).rebuildCollection(" " + API + " ");

        assertThat(result.collection()).isEqualTo(API);
        assertThat(result.records()).isPositive();
        assertThat(result.builtAt()).endsWith("Z");
        assertThat(result.embedded()).isNull();
        assertThat(new CollectionStore(dataDir).manifest(API)).isPresent();
    }

    @Test
    void aCollectionWithVectorsReportsTheirSource() throws IOException {
        AdminTools tools = tools(true);
        RebuildResult first = tools.rebuildCollection(DOCS);
        RebuildResult second = tools.rebuildCollection(DOCS);

        assertThat(first.embedded()).isEqualTo(first.records());
        assertThat(second.embedded()).isZero();
        assertThat(second.fromCache()).isEqualTo(second.records());
    }

    @Test
    void onlyCollectionsOfTheFileCanBeBuilt() throws IOException {
        assertThatThrownBy(() -> tools(false).rebuildCollection("other@billing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'other@billing' is not in").hasMessageContaining(DOCS);
    }

    @Test
    void aMissingCollectionsFileIsReported() throws IOException {
        AdminTools tools = tools(false);
        Files.delete(dataDir.resolve("collections.json"));

        assertThatThrownBy(() -> tools.rebuildCollection(DOCS)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not in").hasMessageContaining("collections.json");
    }
}
