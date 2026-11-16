package ru.it_spectrum.ai.rag.mcp.search;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.IndexesLoader;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.index.IndexBuilder;
import ru.it_spectrum.ai.rag.mcp.index.IndexStore;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exact lookups by the key fields of a catalog collection built from the synthetic catalogs. */
class LookupTest {

    static final String CATALOGS = LookupTestData.CATALOGS;
    static final String NOTES = LookupTestData.NOTES;

    @TempDir
    static Path dataDir;

    static IndexStore store;
    static SearchService search;

    @BeforeAll
    static void build() throws IOException {
        Path file = LookupTestData.writeCollectionsFile(dataDir);
        StepRegistry registry = StepRegistry.builtIn();
        for (IndexDefinition definition : registry.checked(IndexesLoader.load(file, name -> null))) {
            assertThat(definition.configError()).isNull();
            IndexBuilder.build(definition, registry, dataDir, "test");
        }
        store = new IndexStore(dataDir);
        search = new SearchService(store, new RagMcpProperties(dataDir.toString(), null));
    }

    private static List<String> titles(SearchPage page) {
        return page.hits().stream().map(SearchHit::title).toList();
    }

    @Test
    void rowsOfOneCatalogAreNotLimitedPerDocument() throws IOException {
        SearchPage page = search.search(List.of(CATALOGS), "платежа", null, null, 50, null, null, null);

        assertThat(page.hits()).as("several rows of the same file").hasSizeGreaterThan(1);
        assertThat(page.hits().stream().filter(hit -> hit.document().equals(page.hits().getFirst().document())))
                .hasSizeGreaterThan(1);
    }

    @Test
    void theManifestNamesTheKeysThatRecordsHave() {
        Manifest catalogs = store.manifest(CATALOGS).orElseThrow();
        assertThat(catalogs.keys()).as("a key no record has is left out")
                .containsExactly("constants", "id", "method", "text", "view");
        assertThat(store.manifest(NOTES).orElseThrow().keys()).isEmpty();
    }

    @Test
    void aValueIsFoundInWhicheverKeyFieldHasIt() throws IOException {
        assertThat(titles(search.lookup(List.of(CATALOGS), "2003", null, null))).containsExactly("Очередь разбора");
        assertThat(titles(search.lookup(List.of(CATALOGS), "checkAmount", null, null)))
                .containsExactly("Сумма платежа должна быть больше нуля");
        assertThat(titles(search.lookup(List.of(CATALOGS), "unrecognized", null, null)))
                .as("case is ignored").containsExactly("не распознан");
        assertThat(titles(search.lookup(List.of(CATALOGS), "  Сумма платежа должна быть больше нуля ", null, null)))
                .as("a whole text, blanks around it ignored").hasSize(1);
    }

    @Test
    void onlyWholeValuesOfKeyFieldsMatch() throws IOException {
        assertThat(search.lookup(List.of(CATALOGS), "Сумма платежа", null, null).total())
                .as("a part of a value").isZero();
        assertThat(search.lookup(List.of(CATALOGS), "error", null, null).total())
                .as("kind is a field but not a key").isZero();
    }

    @Test
    void collectionsWithoutKeysAreSkippedOrRefused() throws IOException {
        assertThat(search.lookup(List.of(CATALOGS, NOTES), "2003", null, null).total()).isEqualTo(1);
        assertThatThrownBy(() -> search.lookup(List.of(NOTES), "2003", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no key fields", "Collections with keys: " + CATALOGS);
        assertThatThrownBy(() -> search.lookup(List.of(CATALOGS), " ", null, null))
                .hasMessageContaining("value is required");
        assertThatThrownBy(() -> search.lookup(List.of("absent@billing"), "x", null, null))
                .hasMessageContaining("Unknown collection");
    }

    @Test
    void aPageHoldsAtMostTheLimit() throws IOException {
        SearchPage page = search.lookup(List.of(CATALOGS), "ValidationException", 1, null);
        assertThat(page.total()).as("call is not a key").isZero();
        SearchPage menu = search.lookup(List.of(CATALOGS), "billing-payments", 1, null);
        assertThat(menu.hits()).extracting(SearchHit::title).containsExactly("Платежи");
        assertThat(menu.nextOffset()).isNull();
    }

    @Test
    void aKeyThatIsNotAFieldNameIsAConfigurationError(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("collections.json");
        Files.writeString(file, "{\"collections\": {\"c\": {\"sources\": [{\"path\": \"a\","
                + " \"map\": {\"keys\": [\"bad name\"]}}]}}}");

        assertThat(StepRegistry.builtIn().checked(IndexesLoader.load(file, n -> null)).getFirst().configError())
                .contains("map.keys", "\"bad name\" is not a field name");
    }
}
