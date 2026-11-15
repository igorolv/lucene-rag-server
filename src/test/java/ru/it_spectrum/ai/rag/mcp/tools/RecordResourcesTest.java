package ru.it_spectrum.ai.rag.mcp.tools;

import io.modelcontextprotocol.spec.McpSchema.CompleteReference;
import io.modelcontextprotocol.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult.CompleteCompletion;
import io.modelcontextprotocol.spec.McpSchema.ResourceReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.TestKnowledge;
import ru.it_spectrum.ai.rag.mcp.index.CollectionStore;
import ru.it_spectrum.ai.rag.mcp.search.SearchService;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.it_spectrum.ai.rag.mcp.TestKnowledge.API;
import static ru.it_spectrum.ai.rag.mcp.TestKnowledge.DOCS;

/** A record as an MCP resource and completion of its template (docs/decisions.md D48). */
class RecordResourcesTest {

    private static final CompleteReference REF = new ResourceReference(RecordResources.URI);

    @TempDir
    static Path dataDir;

    static SearchService search;
    static RecordResources resources;

    @BeforeAll
    static void build() throws IOException {
        var properties = TestKnowledge.build(dataDir);
        var store = new CollectionStore(dataDir);
        search = new SearchService(store, properties);
        resources = new RecordResources(search, store);
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static CompleteCompletion complete(String argument, String value, Map<String, String> context) {
        return resources.complete(new CompleteRequest(REF, new CompleteRequest.CompleteArgument(argument, value),
                null, context == null ? null : new CompleteRequest.CompleteContext(context)));
    }

    @Test
    void aRecordIsReadByItsPercentEncodedUriValues() {
        String markdown = resources.record(encoded(DOCS), encoded("article.billing.refunds#статусы-возврата"));

        assertThat(markdown).startsWith("# Возвраты › Статусы возврата\n\n");
        assertThat(markdown).contains("- record: article.billing.refunds#статусы-возврата\n",
                "- source: refunds.md\n", "- fields:\n", "  - tables: REFUND, PAYMENT\n", "| Исполнен | банк подтвердил перевод |");
    }

    @Test
    void anUnknownRecordIsAnError() {
        assertThatThrownBy(() -> resources.record(DOCS, "no-such-id"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No record 'no-such-id'");
    }

    @Test
    void collectionsCompleteByPrefixIgnoringCase() {
        assertThat(complete("collection", "", null).values()).containsExactly(API, DOCS);
        assertThat(complete("collection", DOCS.substring(0, 2).toUpperCase(), null).values()).containsExactly(DOCS);
        assertThat(complete("collection", "zzz", null).values()).isEmpty();
    }

    @Test
    void idsCompleteWithinTheChosenCollection() {
        CompleteCompletion ids = complete("id", "article.billing.refunds#", Map.of("collection", encoded(DOCS)));

        assertThat(ids.values()).isNotEmpty().allMatch(id -> id.startsWith("article.billing.refunds#"))
                .contains("article.billing.refunds#статусы-возврата").isSorted();
        assertThat(ids.hasMore()).isFalse();
    }

    @Test
    void idsNeedAKnownCollection() {
        assertThat(complete("id", "article", null).values()).isEmpty();
        assertThat(complete("id", "article", Map.of("collection", "no-such")).values()).isEmpty();
    }

    @Test
    void idCompletionStopsAtTheLimit() throws IOException {
        var page = search.ids(DOCS, "", 2);

        assertThat(page.ids()).hasSize(2);
        assertThat(page.more()).isTrue();
    }
}
