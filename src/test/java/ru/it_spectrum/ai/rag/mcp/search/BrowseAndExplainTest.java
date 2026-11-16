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
import ru.it_spectrum.ai.rag.mcp.index.IndexBuilder;
import ru.it_spectrum.ai.rag.mcp.index.IndexStore;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.it_spectrum.ai.rag.mcp.TestKnowledge.DOCS;

/**
 * The {@code browse} and {@code debug} groups (docs/decisions.md D72): values of a field, records
 * related to a record, and the explanation of a search. {@link TestKnowledge#DOCS} is built
 * lexically in one data directory and with the fake model in another; the catalog of
 * {@link LookupTestData} has rows, which stand alone.
 */
class BrowseAndExplainTest {

    static final String REFUND_STATUSES = "article.billing.refunds#статусы-возврата";

    @TempDir
    static Path lexicalDir;

    @TempDir
    static Path vectorDir;

    @TempDir
    static Path catalogDir;

    static SearchService lexical;
    static BrowseService browseLexical;
    static SearchService vectors;
    static BrowseService browseVectors;
    static BrowseService browseCatalog;

    @BeforeAll
    static void build() throws IOException {
        RagMcpProperties lexicalProperties = TestKnowledge.build(lexicalDir);
        lexical = new SearchService(new IndexStore(lexicalDir), lexicalProperties);
        browseLexical = new BrowseService(lexical, lexicalProperties);

        EmbeddingModels models = FakeEmbeddingModel.models(vectorDir, "fake");
        for (IndexDefinition definition : StepRegistry.builtIn().checked(IndexesLoader.load(
                TestKnowledge.writeIndexesFile(vectorDir, true), name -> null))) {
            IndexBuilder.build(definition, StepRegistry.builtIn(), models, vectorDir, "test");
        }
        RagMcpProperties vectorProperties = new RagMcpProperties(vectorDir.toString(), null);
        vectors = new SearchService(new IndexStore(vectorDir), vectorProperties, models);
        browseVectors = new BrowseService(vectors, vectorProperties);

        for (IndexDefinition definition : StepRegistry.builtIn().checked(IndexesLoader.load(
                LookupTestData.writeIndexesFile(catalogDir), name -> null))) {
            IndexBuilder.build(definition, StepRegistry.builtIn(), catalogDir, "test");
        }
        RagMcpProperties catalogProperties = new RagMcpProperties(catalogDir.toString(), null);
        browseCatalog = new BrowseService(new SearchService(new IndexStore(catalogDir), catalogProperties),
                catalogProperties);
    }

    @Test
    void theValuesOfAFieldComeMostFrequentFirstAsRecordsWriteThem() throws IOException {
        BrowseService.FieldValues tables = browseLexical.fieldValues(List.of(DOCS), "tables", null, null, null, null);

        assertThat(tables.values()).first().isEqualTo(new BrowseService.Value("PAYMENT",
                tables.values().stream().mapToInt(BrowseService.Value::count).max().orElseThrow()));
        assertThat(tables.values()).extracting(BrowseService.Value::value).contains("REFUND", "INVOICE");
        assertThat(tables.distinct()).isEqualTo(tables.values().size());

        BrowseService.FieldValues prefixed = browseLexical.fieldValues(List.of(DOCS), "tables", null, null, "re", 1);
        assertThat(prefixed.values()).extracting(BrowseService.Value::value).containsExactly("REFUND");

        // Each value filters search to the records it counts.
        BrowseService.Value refund = prefixed.values().getFirst();
        assertThat(lexical.search(List.of(DOCS), null, Map.of("tables", refund.value()), 50, null).total())
                .isEqualTo(refund.count());
    }

    @Test
    void withoutNamesValuesAreCountedOverEveryIndexAndFiltersNarrowThem() throws IOException {
        BrowseService.FieldValues all = browseLexical.fieldValues(null, "tables", null, null, null, null);
        assertThat(all.values()).isEqualTo(browseLexical.fieldValues(List.of(DOCS), "tables", null, null, null, null)
                .values());

        BrowseService.FieldValues refunds = browseLexical.fieldValues(null, "tables", Map.of("tags", "Refunds"),
                null, null, null);
        BrowseService.FieldValues payments = browseLexical.fieldValues(null, "tables", null, "tags == 'payments'",
                null, null);
        assertThat(all.values()).extracting(BrowseService.Value::value).contains("INVOICE");
        assertThat(refunds.values()).extracting(BrowseService.Value::value).contains("REFUND")
                .doesNotContain("INVOICE");
        assertThat(payments.values()).extracting(BrowseService.Value::value).contains("INVOICE");

        // Under a filter, each value still filters search to exactly the records it counts.
        for (BrowseService.Value value : refunds.values()) {
            assertThat(lexical.search(null, null, Map.of("tags", "Refunds", "tables", value.value()), 50, null)
                    .total()).as(value.value()).isEqualTo(value.count());
        }
        for (BrowseService.Value value : payments.values()) {
            assertThat(lexical.search(null, null, Map.of("tables", value.value()), "tags == 'payments'", 50, null,
                    null).total()).as(value.value()).isEqualTo(value.count());
        }
    }

    @Test
    void anUnknownFieldNamesTheFieldsThereAre() {
        assertThatThrownBy(() -> browseLexical.fieldValues(null, "colour", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No index has the field 'colour'")
                .hasMessageContaining("tables");
    }

    @Test
    void aSectionIsRelatedToTheOtherSectionsOfItsDocumentAndToRecordsLikeIt() throws IOException {
        BrowseService.Related related = browseLexical.related(DOCS + "/" + REFUND_STATUSES, 5);

        assertThat(related.record().id()).isEqualTo(REFUND_STATUSES);
        assertThat(related.sections()).isNotEmpty().allMatch(h -> h.document().equals("article.billing.refunds"))
                .noneMatch(h -> h.id().equals(REFUND_STATUSES));
        assertThat(related.sections()).extracting(SearchHit::ordinal).isSorted();
        assertThat(related.similarBy()).isEqualTo("words");
        assertThat(related.similar()).isNotEmpty().noneMatch(h -> h.document().equals("article.billing.refunds"))
                .allMatch(h -> !h.snippet().isEmpty());
    }

    @Test
    void inAnIndexWithVectorsLikenessIsByVector() throws IOException {
        BrowseService.Related related = browseVectors.related(DOCS + "/" + REFUND_STATUSES, 3);

        assertThat(related.similarBy()).isEqualTo("vector");
        assertThat(related.similar()).hasSizeLessThanOrEqualTo(3).isNotEmpty()
                .noneMatch(h -> h.document().equals("article.billing.refunds"));
    }

    @Test
    void aRowHasNoSectionsAndIsLikeOtherRowsOfItsFile() throws IOException {
        String row = browseCatalog.fieldValues(List.of(LookupTestData.CATALOGS), "method", null, null, null, 1).values().getFirst().value();
        SearchHit someRow = new SearchService(new IndexStore(catalogDir),
                new RagMcpProperties(catalogDir.toString(), null))
                .search(List.of(LookupTestData.CATALOGS), null, Map.of("method", row), 1, null).hits().getFirst();

        BrowseService.Related related = browseCatalog.related(someRow.address(), 5);

        assertThat(related.sections()).isEmpty();
        assertThat(related.similar()).noneMatch(h -> h.id().equals(someRow.id()));
    }

    @Test
    void aRowIsReadAloneAsAWholeDocument() throws IOException {
        String method = browseCatalog.fieldValues(List.of(LookupTestData.CATALOGS), "method", null, null, null, 1)
                .values().getFirst().value();
        SearchService catalog = new SearchService(new IndexStore(catalogDir),
                new RagMcpProperties(catalogDir.toString(), null));
        SearchHit someRow = catalog.search(List.of(LookupTestData.CATALOGS), null, Map.of("method", method), 1, null)
                .hits().getFirst();

        SearchService.DocumentText read = catalog.document(someRow.address(), true).orElseThrow();

        assertThat(read.sections()).isEqualTo(read.total()).isEqualTo(1);
        assertThat(read.nextId()).isNull();
        assertThat(read.record().id()).isEqualTo(someRow.id());
    }

    @Test
    void anExplanationShowsTheWordsAndWhereRecordsRankInEachList() throws IOException {
        SearchService.Explanation explanation = vectors.explain(List.of(DOCS), "отмена платежей", null, null, null,
                DOCS + "/article.billing.refunds#когда-возможен-возврат", 5);

        assertThat(explanation.mode()).isEqualTo(SearchMode.HYBRID);
        assertThat(explanation.terms()).containsExactly("отм", "платеж");
        assertThat(explanation.englishTerms()).isNotEmpty();
        assertThat(explanation.ranked()).hasSizeLessThanOrEqualTo(5).isNotEmpty();
        assertThat(explanation.ranked()).extracting(SearchService.Ranked::rank).containsExactly(
                java.util.stream.IntStream.rangeClosed(1, explanation.ranked().size()).boxed().toArray(Integer[]::new));
        assertThat(explanation.ranked()).allMatch(r -> r.vectorRank() != null);
        assertThat(explanation.ranked().stream().filter(r -> r.hit().id().equals("article.billing.payments#отмена-платежа"))
                .findFirst().orElseThrow().lexicalRank()).isEqualTo(1);
        assertThat(explanation.target().hit().id()).isEqualTo("article.billing.refunds#когда-возможен-возврат");
        assertThat(explanation.passesFilters()).isTrue();
        assertThat(explanation.notice()).isNull();
    }

    @Test
    void aTargetTheFiltersExcludeIsSaidToBeExcluded() throws IOException {
        SearchService.Explanation explanation = lexical.explain(List.of(DOCS), "отмена платежей",
                Map.of("kind", "playbook"), null, null, DOCS + "/article.billing.payments#отмена-платежа", null);

        assertThat(explanation.mode()).isEqualTo(SearchMode.LEXICAL);
        assertThat(explanation.target().rank()).isNull();
        assertThat(explanation.target().vectorRank()).isNull();
        assertThat(explanation.matchesWords()).isTrue();
        assertThat(explanation.passesFilters()).isFalse();
        assertThatThrownBy(() -> lexical.explain(List.of(DOCS), "платёж", null, null, null, DOCS + "/no-such", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No record 'no-such'");
        assertThatThrownBy(() -> lexical.explain(List.of(DOCS), "платёж", null, null, null, "no-such", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("<index>/<record id>");
        assertThatThrownBy(() -> lexical.explain(List.of(DOCS), "платёж", null, null, null, "other/no-such", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not cover");
    }
}
