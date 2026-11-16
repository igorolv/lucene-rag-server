package ru.it_spectrum.ai.rag.mcp.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.TestKnowledge;
import ru.it_spectrum.ai.rag.mcp.api.ListIndexesResult;
import ru.it_spectrum.ai.rag.mcp.api.RebuildResult;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.embedding.EmbeddingModels;
import ru.it_spectrum.ai.rag.mcp.embedding.FakeEmbeddingModel;
import ru.it_spectrum.ai.rag.mcp.index.IndexStore;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.it_spectrum.ai.rag.mcp.TestKnowledge.API;
import static ru.it_spectrum.ai.rag.mcp.TestKnowledge.DOCS;

/** {@code rebuildIndex} builds an index of the indexes file, as {@code rag-index} does. */
class AdminToolsTest {

    @TempDir
    Path dataDir;

    private AdminTools tools(boolean vectors) throws IOException {
        TestKnowledge.writeIndexesFile(dataDir, vectors);
        RagMcpProperties properties = new RagMcpProperties(dataDir.toString(), null);
        return new AdminTools(properties, StepRegistry.builtIn(), FakeEmbeddingModel.models(dataDir, "fake"), name -> null);
    }

    @Test
    void anIndexIsBuiltAndServed() throws IOException {
        RebuildResult result = tools(false).rebuildIndex(" " + API + " ");

        assertThat(result.index()).isEqualTo(API);
        assertThat(result.records()).isPositive();
        assertThat(result.builtAt()).endsWith("Z");
        assertThat(result.embedded()).isNull();
        assertThat(new IndexStore(dataDir).manifest(API)).isPresent();
    }

    @Test
    void anIndexWithVectorsReportsTheirSource() throws IOException {
        AdminTools tools = tools(true);
        RebuildResult first = tools.rebuildIndex(DOCS);
        RebuildResult second = tools.rebuildIndex(DOCS);

        assertThat(first.embedded()).isEqualTo(first.records());
        assertThat(second.embedded()).isZero();
        assertThat(second.fromCache()).isEqualTo(second.records());
    }

    @Test
    void onlyIndexesOfTheFileCanBeBuilt() throws IOException {
        assertThatThrownBy(() -> tools(false).rebuildIndex("other@billing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'other@billing' is not in").hasMessageContaining(DOCS);
    }

    @Test
    void listIndexesShowsBuiltAndUnbuiltIndexesAndAnOldFile() throws IOException {
        AdminTools tools = tools(true);
        tools.rebuildIndex(API);

        ListIndexesResult listed = tools.listIndexes();
        assertThat(listed.indexes()).extracting(ListIndexesResult.IndexInfo::name).containsExactly(API, DOCS);
        ListIndexesResult.IndexInfo api = listed.indexes().getFirst();
        assertThat(api.built()).isTrue();
        assertThat(api.records()).isPositive();
        assertThat(api.builtAt()).endsWith("Z");
        assertThat(api.model()).isNull();
        ListIndexesResult.IndexInfo docs = listed.indexes().get(1);
        assertThat(docs.built()).isFalse();
        assertThat(docs.model()).isEqualTo("fake");
        assertThat(listed.importProblems()).isNull();
        assertThat(listed.warnings()).isNull();
        assertThat(listed.notice()).isNull();

        Path file = dataDir.resolve("indexes.json");
        Files.writeString(file, Files.readString(file).replaceFirst("\"description\"", "\"descripton\""));
        assertThat(tools.listIndexes().warnings()).singleElement().asString()
                .contains("unknown key \"descripton\" is ignored; did you mean \"description\"?");

        Files.move(dataDir.resolve("indexes.json"), dataDir.resolve("collections.json"));
        ListIndexesResult old = tools.listIndexes();
        assertThat(old.indexes()).extracting(ListIndexesResult.IndexInfo::name).containsExactly(API);
        assertThat(old.notice()).contains("collections.json is not read", "rename it to indexes.json");
    }

    @Test
    void aMissingIndexesFileIsReported() throws IOException {
        AdminTools tools = tools(false);
        Files.delete(dataDir.resolve("indexes.json"));

        assertThatThrownBy(() -> tools.rebuildIndex(DOCS)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not in").hasMessageContaining("indexes.json");
    }
}
