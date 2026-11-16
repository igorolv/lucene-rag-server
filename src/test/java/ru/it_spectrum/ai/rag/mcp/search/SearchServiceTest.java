package ru.it_spectrum.ai.rag.mcp.search;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.TestKnowledge;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.index.IndexStore;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.it_spectrum.ai.rag.mcp.TestKnowledge.API;
import static ru.it_spectrum.ai.rag.mcp.TestKnowledge.DOCS;

/** Builds the synthetic indexes once and searches them. */
class SearchServiceTest {

    @TempDir
    static Path dataDir;

    static RagMcpProperties properties;
    static IndexStore store;
    static SearchService search;

    @BeforeAll
    static void build() throws IOException {
        properties = TestKnowledge.build(dataDir);
        store = new IndexStore(dataDir);
        search = new SearchService(store, properties);
    }

    private static List<String> ids(SearchPage page) {
        return page.hits().stream().map(SearchHit::id).toList();
    }

    @Test
    void theManifestDescribesWhatWasBuilt() {
        assertThat(store.built()).extracting(Manifest::name).containsExactly(API, DOCS);
        Manifest docs = store.manifest(DOCS).orElseThrow();
        assertThat(docs.schema()).isEqualTo(Manifest.SCHEMA);
        assertThat(docs.description()).isEqualTo("Billing: articles and playbooks");
        assertThat(docs.files()).isEqualTo(3);
        assertThat(docs.documents()).isEqualTo(3);
        assertThat(docs.records()).isGreaterThanOrEqualTo(8);
        assertThat(docs.fields()).containsExactly("id", "kind", "subsystem", "tables", "tags");
        assertThat(docs.sources()).hasSize(2);
        assertThat(docs.builtAt()).endsWith("Z");
    }

    @Test
    void russianWordFormsMatch() throws IOException {
        // The article says "Отменить можно только платёж"; the query uses other forms of both words.
        SearchPage page = search.search(List.of(DOCS), "отмена платежей", null, null, null);

        assertThat(ids(page)).first().isEqualTo("article.billing.payments#отмена-платежа");
        assertThat(page.hits().getFirst().snippet()).contains("**");
        assertThat(page.hits().getFirst().text()).isNull();
    }

    @Test
    void yoMatchesYe() throws IOException {
        SearchPage withYe = search.search(List.of(DOCS), "отмененный", null, null, null);
        SearchPage withYo = search.search(List.of(DOCS), "отменённый", null, null, null);

        assertThat(ids(withYe)).isNotEmpty().isEqualTo(ids(withYo));
        assertThat(withYe.hits().getFirst().snippet()).contains("**Отменённый**");
    }

    @Test
    void aQuotedPhraseMustAppearAsWritten() throws IOException {
        assertThat(ids(search.search(List.of(DOCS), "\"очередь разбора\"", null, null, null)))
                .contains("article.billing.payments#привязка-к-счёту");
        assertThat(search.search(List.of(DOCS), "\"разбора очередь\"", null, null, null).total()).isZero();
    }

    @Test
    void wordsAreOptionalSoAQuestionStillFindsItsPassage() throws IOException {
        SearchPage page = search.search(List.of(DOCS),
                "почему банк отклонил возврат недостаточно средств", null, null, null);

        assertThat(ids(page)).first().isEqualTo("article.billing.refunds#ошибка-недостаточно-средств");
    }

    @Test
    void aMatchInTheTitleRanksAboveAMatchInTheTextOnly() throws IOException {
        SearchPage page = search.search(List.of(DOCS), "возвраты", null, null, null);

        assertThat(page.hits().getFirst().breadcrumb()).startsWith("Возвраты");
    }

    @Test
    void englishStemmingWorksInTheSameIndex() throws IOException {
        SearchPage page = search.search(List.of(API), "retrying deliveries", null, null, null);

        assertThat(ids(page)).first().isEqualTo("webhooks.md#delivery-and-retries");
        assertThat(page.hits().getFirst().source()).isEqualTo("webhooks.md");
    }

    @Test
    void filtersAreExactCaseInsensitiveAndCombine() throws IOException {
        SearchPage playbooks = search.search(List.of(DOCS), "платёж", Map.of("kind", "Playbook"), null, null);
        assertThat(playbooks.hits()).isNotEmpty()
                .allSatisfy(hit -> assertThat(hit.id()).startsWith("playbook.billing.payment-not-matched#"));

        SearchPage byTable = search.search(List.of(DOCS), null, Map.of("tables", "refund"), null, 50, null, null, 50);
        assertThat(byTable.hits()).isNotEmpty()
                .allSatisfy(hit -> assertThat(hit.document()).isEqualTo("article.billing.refunds"));
        assertThat(byTable.total()).isEqualTo(byTable.hits().size());

        assertThat(search.search(List.of(DOCS), "платёж", Map.of("kind", "article", "tags", "refunds"),
                null, null).hits()).allSatisfy(hit -> assertThat(hit.document()).isEqualTo("article.billing.refunds"));
    }

    @Test
    void anUnknownFilterFieldNamesTheKnownOnes() {
        assertThatThrownBy(() -> search.search(List.of(DOCS), "платёж", Map.of("colour", "red"), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("colour").hasMessageContaining("kind, subsystem");
    }

    private static Set<String> documents(String expression) throws IOException {
        return documents(null, expression);
    }

    private static Set<String> documents(Map<String, String> filters, String expression) throws IOException {
        return search.search(List.of(DOCS), null, filters, expression, 50, null, null).hits().stream()
                .map(SearchHit::document).collect(Collectors.toSet());
    }

    private static final String PAYMENTS = "article.billing.payments";
    private static final String REFUNDS = "article.billing.refunds";
    private static final String PLAYBOOK = "playbook.billing.payment-not-matched";

    @Test
    void aFilterExpressionCombinesConditionsWithOrAndLists() throws IOException {
        assertThat(documents("kind == 'Playbook' || tags in ['refunds']")).containsExactlyInAnyOrder(PLAYBOOK, REFUNDS);
        assertThat(documents("kind == 'article' && (tags == 'payments' || tables == 'refund')"))
                .containsExactlyInAnyOrder(PAYMENTS, REFUNDS);
        assertThat(documents("kind == 'article' AND tags == 'refunds'")).containsExactly(REFUNDS);
    }

    @Test
    void negationsIncludeRecordsWithoutTheField() throws IOException {
        assertThat(documents("kind != 'article'")).containsExactly(PLAYBOOK);
        assertThat(documents("NOT (tags == 'payments')")).containsExactly(REFUNDS);
        assertThat(documents("tables nin ['invoice']")).containsExactlyInAnyOrder(REFUNDS, PLAYBOOK);
        assertThat(documents("tables IS NULL")).containsExactly(PLAYBOOK);
        assertThat(documents("tables IS NOT NULL")).containsExactlyInAnyOrder(PAYMENTS, REFUNDS);
    }

    @Test
    void rangesCompareTextAndRejectNumbers() throws IOException {
        assertThat(documents("id >= 'playbook'")).containsExactly(PLAYBOOK);
        assertThat(documents("id < 'article.billing.r'")).containsExactly(PAYMENTS);
        assertThatThrownBy(() -> documents("id > 5"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("in quotes");
    }

    @Test
    void anExpressionMustHoldTogetherWithTheFiltersMap() throws IOException {
        assertThat(documents(Map.of("kind", "article"), "tags == 'refunds' || tags == 'payments'"))
                .containsExactlyInAnyOrder(PAYMENTS, REFUNDS);
        assertThat(documents(Map.of("kind", "playbook"), "tags == 'refunds'")).isEmpty();
        assertThat(search.search(List.of(DOCS), "платёж", null, "kind == 'playbook'", 50, null, null).hits())
                .isNotEmpty().allSatisfy(hit -> assertThat(hit.document()).isEqualTo(PLAYBOOK));
    }

    @Test
    void aBadExpressionIsReported() {
        assertThatThrownBy(() -> documents("colour == 'red'"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown filter field 'colour'").hasMessageContaining("kind, subsystem");
        assertThatThrownBy(() -> documents("kind = = 'article'"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a filter expression");
        assertThatThrownBy(() -> documents("kind == 'x' || ".repeat(100)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("longer than");
    }

    @Test
    void aPageHoldsTheBestSectionOfEachDocumentUnlessAskedForMore() throws IOException {
        SearchPage one = search.search(List.of(DOCS), "платёж", null, null, 50, null, null, null);
        SearchPage two = search.search(List.of(DOCS), "платёж", null, null, 50, null, null, 2);
        SearchPage all = search.search(List.of(DOCS), "платёж", null, null, 50, null, null, 50);

        assertThat(one.hits()).extracting(SearchHit::document).doesNotHaveDuplicates().hasSize(3);
        assertThat(one.total()).as("total counts matching records").isEqualTo(all.total());
        assertThat(one.nextOffset()).isNull();
        assertThat(ids(one).getFirst()).isEqualTo(ids(all).getFirst());
        assertThat(two.hits()).hasSizeBetween(4, 6);
        assertThat(two.hits().stream().collect(Collectors.groupingBy(SearchHit::document, Collectors.counting()))
                .values()).allMatch(count -> count <= 2);
        assertThat(all.hits()).hasSize(all.total());
        assertThatThrownBy(() -> search.search(List.of(DOCS), "платёж", null, null, 50, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("perDocument");
    }

    @Test
    void pagesOfACollapsedListFollowNextOffset() throws IOException {
        SearchPage first = search.search(List.of(DOCS), "платёж", null, null, 2, null, null, null);
        SearchPage second = search.search(List.of(DOCS), "платёж", null, null, 2, first.nextOffset(), null, null);

        assertThat(first.hits()).hasSize(2);
        assertThat(first.nextOffset()).isEqualTo(2);
        assertThat(second.hits()).hasSize(1);
        assertThat(second.nextOffset()).isNull();
        assertThat(second.hits().getFirst().document()).isNotIn(
                first.hits().stream().map(SearchHit::document).toList());
    }

    @Test
    void severalIndexesAreSearchedTogether() throws IOException {
        SearchPage page = search.search(List.of(DOCS, API), "payment платёж", null, 50, null);

        assertThat(page.hits()).extracting(SearchHit::index).contains(DOCS, API);
    }

    @Test
    void anUnknownIndexNamesTheBuiltOnes() {
        assertThatThrownBy(() -> search.search(List.of("nope"), "x", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope").hasMessageContaining(API).hasMessageContaining(DOCS);
        assertThatThrownBy(() -> search.search(List.of(), "x", null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one");
    }

    @Test
    void pagesFollowNextOffsetAndTheServerCapsTheLimit() throws IOException {
        var capped = new SearchService(store, new RagMcpProperties(dataDir.toString(), null,
                new RagMcpProperties.Search(2, 3), null));

        SearchPage first = capped.search(List.of(DOCS), "платёж", null, null, null, null, null, 50);
        assertThat(first.hits()).hasSize(2);
        assertThat(first.total()).isGreaterThan(3);
        assertThat(first.nextOffset()).isEqualTo(2);

        SearchPage asked = capped.search(List.of(DOCS), "платёж", null, null, 100, first.nextOffset(), null, 50);
        assertThat(asked.hits()).hasSize(3);
        assertThat(ids(asked)).doesNotContainAnyElementsOf(ids(first));
    }

    @Test
    void theResponseSizeCapShortensThePage() throws IOException {
        var tight = new SearchService(store, new RagMcpProperties(dataDir.toString(), null, null,
                new RagMcpProperties.Response(500)));

        SearchPage page = tight.search(List.of(DOCS), "платёж", null, null, 50, null, null, 50);

        assertThat(page.truncated()).isTrue();
        assertThat(page.hits()).isNotEmpty();
        assertThat(page.hits().size()).isLessThan(page.total());
        assertThat(page.nextOffset()).isEqualTo(page.hits().size());
    }

    @Test
    void aRecordIsReadInFullById() throws IOException {
        SearchHit hit = search.get(DOCS + "/article.billing.refunds#статусы-возврата").orElseThrow();

        assertThat(hit.text()).contains("| Исполнен | банк подтвердил перевод |");
        assertThat(hit.snippet()).isNull();
        assertThat(hit.breadcrumb()).isEqualTo("Возвраты › Статусы возврата");
        assertThat(hit.source()).isEqualTo("refunds.md");
        assertThat(hit.fields().get("tables")).containsExactly("REFUND", "PAYMENT");
        assertThat(hit.address()).isEqualTo(DOCS + "/article.billing.refunds#статусы-возврата");
        assertThat(search.get(DOCS + "/no-such-id")).isEmpty();
    }

    @Test
    void anIdWithoutAKnownIndexIsAnErrorWithAnExample() {
        assertThatThrownBy(() -> search.get("article.billing.refunds#статусы-возврата"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("an id is <index>/<record id>, for example docs@billing/");
        assertThatThrownBy(() -> search.get("nope@billing/article.billing.refunds"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names the index 'nope@billing', which is not built here")
                .hasMessageContaining(DOCS);
        assertThatThrownBy(() -> search.get(" "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("id is required");
    }

    @Test
    void aQueryOfStopWordsFindsNothing() throws IOException {
        assertThat(search.search(List.of(DOCS), "и в на", null, null, null).total()).isZero();
    }
}
