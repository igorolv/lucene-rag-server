package ru.it_spectrum.ai.rag.mcp.embedding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import ru.it_spectrum.ai.rag.mcp.TestKnowledge;
import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.IndexesLoader;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.index.IndexBuilder;
import ru.it_spectrum.ai.rag.mcp.index.IndexStore;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;
import ru.it_spectrum.ai.rag.mcp.search.SearchPage;
import ru.it_spectrum.ai.rag.mcp.search.SearchService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A model of type {@code bean}: a Spring AI {@code EmbeddingModel} bean named as the model, with
 * no files under {@code <data-dir>/models/} (docs/decisions.md D43).
 */
class BeanModelTypeTest {

    @TempDir
    Path dataDir;

    StaticListableBeanFactory beans;
    EmbeddingModels models;

    @BeforeEach
    void setUp() {
        beans = new StaticListableBeanFactory();
        beans.addBean("remoteEmbedding", FakeEmbeddingModel.of(512));
        models = new EmbeddingModels(dataDir.resolve(EmbeddingModels.MODELS_DIR), List.of(new BeanModelType(beans)));
    }

    @Test
    void aBeanIsAModelWithoutFiles() throws IOException {
        VectorModel model = models.load("remoteEmbedding", new ModelParameters(BeanModelType.NAME, "query: ",
                "passage: ", 512));

        assertThat(model.info().type()).isEqualTo(BeanModelType.NAME);
        assertThat(model.info().dimension()).isEqualTo(FakeEmbeddingModel.DIMENSION);
        assertThat(model.info().files()).containsEntry(BeanModelType.IDENTITY_BEAN, "remoteEmbedding")
                .containsEntry(BeanModelType.IDENTITY_MODEL, FakeEmbeddingModel.TYPE);
        assertThat(Files.exists(dataDir.resolve(EmbeddingModels.MODELS_DIR))).isFalse();
    }

    @Test
    void aMissingBeanNamesTheBeansThereAre() {
        assertThatThrownBy(() -> models.load("other", new ModelParameters(BeanModelType.NAME, "", "", 512)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no EmbeddingModel bean named 'other'")
                .hasMessageContaining("remoteEmbedding");
    }

    @Test
    void anIndexIsBuiltAndSearchedWithABeanModel() throws IOException {
        String root = TestKnowledge.root().toString().replace('\\', '/');
        Path file = dataDir.resolve("indexes.json");
        Files.writeString(file, """
                {"models": {"remoteEmbedding": {"type": "bean", "queryPrefix": "query: ", "passagePrefix": "passage: "}},
                 "indexes": {"docs@billing": {"vectors": {"model": "remoteEmbedding"},
                                                  "sources": [{"path": "%s/articles"}]}}}
                """.formatted(root));
        IndexDefinition definition = StepRegistry.builtIn().checked(IndexesLoader.load(file, n -> null)).getFirst();
        assertThat(definition.configError()).isNull();

        IndexBuilder.build(definition, StepRegistry.builtIn(), models, dataDir, "test");
        IndexStore store = new IndexStore(dataDir);
        Manifest.Vectors vectors = store.manifest("docs@billing").orElseThrow().vectors();
        SearchPage page = new SearchService(store, new RagMcpProperties(dataDir.toString(), null), models)
                .search(List.of("docs@billing"), "отмена платежа", null, null, null, "vector");

        assertThat(vectors.type()).isEqualTo(BeanModelType.NAME);
        assertThat(vectors.files()).containsEntry(BeanModelType.IDENTITY_BEAN, "remoteEmbedding");
        assertThat(page.hits()).isNotEmpty();
    }
}
