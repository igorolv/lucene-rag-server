package ru.it_spectrum.ai.rag.mcp.search;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.TestKnowledge;
import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.IndexesLoader;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.embedding.EmbeddingModels;
import ru.it_spectrum.ai.rag.mcp.embedding.FakeEmbeddingModel;
import ru.it_spectrum.ai.rag.mcp.embedding.ModelUnavailableException;
import ru.it_spectrum.ai.rag.mcp.index.IndexBuilder;
import ru.it_spectrum.ai.rag.mcp.index.IndexStore;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.it_spectrum.ai.rag.mcp.TestKnowledge.API;
import static ru.it_spectrum.ai.rag.mcp.TestKnowledge.DOCS;

/** Builds {@link TestKnowledge#DOCS} with vectors of a fake model and {@link TestKnowledge#API} without. */
class VectorSearchTest {

    @TempDir
    static Path dataDir;

    static RagMcpProperties properties;
    static IndexStore store;
    static EmbeddingModels models;
    static SearchService search;
    static Map<String, IndexBuilder.Built> firstBuild = new HashMap<>();

    static List<IndexDefinition> definitions() throws IOException {
        Path file = TestKnowledge.writeIndexesFile(dataDir, true);
        return StepRegistry.builtIn().checked(IndexesLoader.load(file, name -> null));
    }

    @BeforeAll
    static void build() throws IOException {
        models = FakeEmbeddingModel.models(dataDir, "fake");
        for (IndexDefinition definition : definitions()) {
            firstBuild.put(definition.name(),
                    IndexBuilder.build(definition, StepRegistry.builtIn(), models, dataDir, "test"));
        }
        properties = new RagMcpProperties(dataDir.toString(), null);
        store = new IndexStore(dataDir);
        search = new SearchService(store, properties, models);
    }

    private static List<String> ids(SearchPage page) {
        return page.hits().stream().map(SearchHit::id).toList();
    }

    @Test
    void theManifestRecordsTheModel() {
        Manifest docs = store.manifest(DOCS).orElseThrow();
        assertThat(docs.vectors()).isNotNull();
        assertThat(docs.vectors().model()).isEqualTo("fake");
        assertThat(docs.vectors().type()).isEqualTo(FakeEmbeddingModel.TYPE);
        assertThat(docs.vectors().dimension()).isEqualTo(FakeEmbeddingModel.DIMENSION);
        assertThat(docs.vectors().queryPrefix()).isEqualTo("query: ");
        assertThat(docs.vectors().passagePrefix()).isEqualTo("passage: ");
        assertThat(docs.vectors().files()).containsOnlyKeys(FakeEmbeddingModel.FILE);
        assertThat(store.manifest(API).orElseThrow().vectors()).isNull();
        assertThat(firstBuild.get(DOCS).embedded()).isEqualTo(docs.records());
    }

    @Test
    void aRebuildTakesUnchangedVectorsFromTheCache() throws IOException {
        IndexDefinition docs = definitions().stream().filter(d -> d.name().equals(DOCS)).findFirst().orElseThrow();
        IndexBuilder.Built again = IndexBuilder.build(docs, StepRegistry.builtIn(), models, dataDir, "test");

        assertThat(again.embedded()).isZero();
        assertThat(again.cached()).isEqualTo(again.manifest().records());
        assertThat(Files.isDirectory(dataDir.resolve("cache").resolve("embeddings"))).isTrue();
    }

    @Test
    void vectorModeFindsThePassageOfTheQuery() throws IOException {
        SearchPage page = search.search(List.of(DOCS), "почему банк отклонил возврат недостаточно средств",
                null, 3, null, "vector");

        assertThat(page.mode()).isEqualTo(SearchMode.VECTOR);
        assertThat(ids(page)).first().isEqualTo("article.billing.refunds#ошибка-недостаточно-средств");
        assertThat(page.hits().getFirst().score()).isBetween(0f, 1f);
    }

    @Test
    void hybridIsTheDefaultForAnIndexWithVectorsAndFusesBothLists() throws IOException {
        SearchPage page = search.search(List.of(DOCS), "отмена платежей", null, 5, null);

        assertThat(page.mode()).isEqualTo(SearchMode.HYBRID);
        assertThat(ids(page)).contains("article.billing.payments#отмена-платежа");
        // A reciprocal rank fusion score is at most 2 / (k + 1) for two lists.
        assertThat(page.hits().getFirst().score()).isLessThanOrEqualTo(2f / (SearchService.RRF_K + 1) + 1e-6f);
    }

    @Test
    void lexicalModeIsUnchangedAndTheDefaultWithoutVectors() throws IOException {
        SearchPage lexical = search.search(List.of(DOCS), "отмена платежей", null, null, null, "lexical");
        assertThat(lexical.mode()).isEqualTo(SearchMode.LEXICAL);
        assertThat(ids(lexical)).first().isEqualTo("article.billing.payments#отмена-платежа");

        assertThat(search.search(List.of(API), "retrying deliveries", null, null, null).mode())
                .isEqualTo(SearchMode.LEXICAL);
        assertThat(search.search(List.of(DOCS), null, Map.of("kind", "article"), null, null).mode())
                .isEqualTo(SearchMode.LEXICAL);
    }

    @Test
    void filtersApplyToTheVectorList() throws IOException {
        SearchPage page = search.search(List.of(DOCS), "платёж", Map.of("kind", "playbook"), 50, null, "vector");

        assertThat(page.hits()).isNotEmpty()
                .allSatisfy(hit -> assertThat(hit.id()).startsWith("playbook.billing.payment-not-matched#"));

        SearchPage byExpression = search.search(List.of(DOCS), "платёж", null, "kind != 'article'", 50, null,
                "hybrid");
        assertThat(byExpression.hits()).isNotEmpty()
                .allSatisfy(hit -> assertThat(hit.id()).startsWith("playbook.billing.payment-not-matched#"));
    }

    @Test
    void hybridOverAnIndexWithoutVectorsUsesItsBm25ListOnly() throws IOException {
        SearchPage page = search.search(List.of(DOCS, API), "payment платёж", null, 50, null, "hybrid");

        assertThat(page.hits()).extracting(SearchHit::collection).contains(DOCS, API);
        assertThatThrownBy(() -> search.search(List.of(DOCS, API), "payment", null, null, null, "vector"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(API + " has no vectors");
        assertThatThrownBy(() -> search.search(List.of(API), "payment", null, null, null, "hybrid"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mode lexical");
    }

    @Test
    void badModesAreReported() {
        assertThatThrownBy(() -> search.search(List.of(DOCS), "платёж", null, null, null, "semantic"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lexical, vector, hybrid");
        assertThatThrownBy(() -> search.search(List.of(DOCS), null, Map.of("kind", "article"), null, null, "vector"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs a query");
    }

    @Test
    void aFusedListKeepsTheBestSectionOfEachDocument() throws IOException {
        SearchPage page = search.search(List.of(DOCS), "платёж возврат", null, null, 50, null, "hybrid", null);

        assertThat(page.hits()).extracting(SearchHit::document).doesNotHaveDuplicates().hasSize(3);
        assertThat(page.nextOffset()).isNull();
    }

    @Test
    void pagesOfAFusedListDoNotOverlap() throws IOException {
        SearchPage first = search.search(List.of(DOCS), "платёж возврат", null, null, 3, null, "hybrid", 10);
        SearchPage second = search.search(List.of(DOCS), "платёж возврат", null, null, 3, first.nextOffset(),
                "hybrid", 10);

        assertThat(first.nextOffset()).isEqualTo(3);
        assertThat(ids(second)).isNotEmpty().doesNotContainAnyElementsOf(ids(first));
    }

    @Test
    void anotherModelFailsVectorSearchAndLexicalStillWorks(@TempDir Path otherModels) throws IOException {
        Path dir = otherModels.resolve(EmbeddingModels.MODELS_DIR).resolve("fake");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(FakeEmbeddingModel.FILE), "fake model 2");
        var other = new EmbeddingModels(otherModels.resolve(EmbeddingModels.MODELS_DIR),
                Map.of(FakeEmbeddingModel.TYPE, FakeEmbeddingModel::load));
        var withOther = new SearchService(store, properties, other);

        assertThatThrownBy(() -> withOther.search(List.of(DOCS), "платёж", null, null, null, "vector"))
                .isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("another version of model 'fake'").hasMessageContaining("lexical");
        assertThat(withOther.search(List.of(DOCS), "платёж", null, null, null, "lexical").hits()).isNotEmpty();

        // Without a mode the search runs by words and says why.
        SearchPage fallback = withOther.search(List.of(DOCS), "платёж", null, null, null);
        SearchPage lexical = withOther.search(List.of(DOCS), "платёж", null, null, null, "lexical");
        assertThat(fallback.mode()).isEqualTo(SearchMode.LEXICAL);
        assertThat(ids(fallback)).isEqualTo(ids(lexical));
        assertThat(fallback.notice()).startsWith("Searched by words only: ")
                .contains("another version of model 'fake'");
        assertThat(lexical.notice()).isNull();

        // What listCollections reports: nothing known before loading, the mismatch once loaded.
        Manifest.Vectors vectors = store.manifest(DOCS).orElseThrow().vectors();
        assertThat(other.problem(DOCS, "fake", vectors.parameters(), vectors.dimension(), vectors.files()))
                .hasValueSatisfying(p -> assertThat(p).contains("another version of model 'fake'"));
        var empty = new EmbeddingModels(otherModels.resolve("none"),
                Map.of(FakeEmbeddingModel.TYPE, FakeEmbeddingModel::load));
        assertThat(empty.problem(DOCS, "fake", vectors.parameters(), vectors.dimension(), vectors.files()))
                .hasValueSatisfying(p -> assertThat(p).contains("of model 'fake' are not in"));
        assertThat(new EmbeddingModels(otherModels.resolve("none"), Map.of()).problem(DOCS, "fake",
                vectors.parameters(), vectors.dimension(), vectors.files()))
                .hasValueSatisfying(p -> assertThat(p).contains("is not supported here"));

        var missing = new SearchService(store, properties, new EmbeddingModels(otherModels.resolve("none"),
                Map.of(FakeEmbeddingModel.TYPE, FakeEmbeddingModel::load)));
        assertThatThrownBy(() -> missing.search(List.of(DOCS), "платёж", null, null, null, "hybrid"))
                .isInstanceOf(ModelUnavailableException.class).hasMessageContaining("cannot be loaded");

        var none = new SearchService(store, properties);
        assertThatThrownBy(() -> none.search(List.of(DOCS), "платёж", null, null, null, "vector"))
                .isInstanceOf(ModelUnavailableException.class);
    }
}
