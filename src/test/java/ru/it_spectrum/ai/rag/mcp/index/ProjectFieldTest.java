package ru.it_spectrum.ai.rag.mcp.index;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.TestKnowledge;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.IndexesLoader;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;
import ru.it_spectrum.ai.rag.mcp.search.SearchHit;
import ru.it_spectrum.ai.rag.mcp.search.SearchService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The field {@code project} and the values of fields in the manifest (docs/decisions.md D83, D89). */
class ProjectFieldTest {

    private static final String DOCS = "docs@billing";
    private static final String API = "api@billing";
    private static final String CODES = "codes@billing";

    @TempDir
    static Path dataDir;

    static IndexStore store;
    static SearchService search;

    @BeforeAll
    static void build() throws IOException {
        String root = TestKnowledge.root().toString().replace('\\', '/');
        StringBuilder codes = new StringBuilder("code\tname\tgroup\n");
        for (int i = 1; i <= Manifest.FEW_VALUES + 1; i++) {
            codes.append("C").append(i).append("\tКод ").append(i).append('\t').append(i % 2 == 0 ? "Even" : "odd")
                    .append('\n');
        }
        codes.append("C1\tКод 1 снова\tEVEN\n");
        Files.createDirectories(dataDir.resolve("src"));
        Files.writeString(dataDir.resolve("src/codes.tsv"), codes.toString());
        Path file = dataDir.resolve("indexes.json");
        Files.writeString(file, """
                {"indexes": {
                  "%s": {"project": "billing", "sources": [{"path": "%s/articles"}, {"path": "%s/playbooks"}]},
                  "%s": {"sources": [{"path": "%s/api"}]},
                  "%s": {"project": "billing", "sources": [{"path": "src/codes.tsv", "format": "tsv",
                          "map": {"id": "{column.code}~{row}", "text": "{column.name}",
                                  "fields": {"code": "{column.code}", "group": "{column.group}"}}}]}
                }}""".formatted(DOCS, root, root, API, root, CODES));
        for (IndexDefinition definition : IndexesLoader.load(file, name -> null)) {
            IndexBuilder.build(definition, StepRegistry.builtIn(), dataDir, "test");
        }
        store = new IndexStore(dataDir);
        search = new SearchService(store, new RagMcpProperties(dataDir.toString(), null));
    }

    @Test
    void theProjectIsAFieldOfEveryRecordAndAKeyOfTheManifest() throws IOException {
        Manifest docs = store.manifest(DOCS).orElseThrow();
        assertThat(docs.project()).isEqualTo("billing");
        assertThat(docs.fields()).contains("project");
        assertThat(docs.fieldValues().get("project").values())
                .containsExactly(new Manifest.Value("billing", docs.records()));

        Manifest api = store.manifest(API).orElseThrow();
        assertThat(api.project()).isNull();
        assertThat(api.fields()).doesNotContain("project");

        List<SearchHit> hits = search.search(List.of(DOCS), "платёж", null, null, null).hits();
        assertThat(hits).isNotEmpty().allSatisfy(hit -> assertThat(hit.fields()).containsEntry("project", List.of("billing")));
    }

    @Test
    void aSearchIsNarrowedToAProjectOrToIndexesWithoutOne() throws IOException {
        int all = search.search(List.of(DOCS, API), null, null, null, 50, null, null).total();
        int billing = search.search(List.of(DOCS, API), null, Map.of("project", "Billing"), null, 50, null, null).total();
        int none = search.search(List.of(DOCS, API), null, null, "project IS NULL", 50, null, null).total();

        assertThat(billing).isEqualTo(store.manifest(DOCS).orElseThrow().records());
        assertThat(none).isEqualTo(store.manifest(API).orElseThrow().records());
        assertThat(billing + none).isEqualTo(all);
        assertThat(search.search(List.of(DOCS, API), null, null, "project IS NULL", 50, null, null).hits())
                .allSatisfy(hit -> assertThat(hit.index()).isEqualTo(API));
    }

    @Test
    void fewValuesAreListedWithTheirRecordsAndManyAreCounted() {
        Manifest codes = store.manifest(CODES).orElseThrow();
        assertThat(codes.records()).isEqualTo(Manifest.FEW_VALUES + 2);

        Manifest.FieldValues group = codes.fieldValues().get("group");
        assertThat(group.distinct()).isEqualTo(2);
        assertThat(group.values()).as("compared as filters compare, written as the first record writes it")
                .containsExactly(new Manifest.Value("Even", 9), new Manifest.Value("odd", 8));

        Manifest.FieldValues code = codes.fieldValues().get("code");
        assertThat(code.distinct()).isEqualTo(Manifest.FEW_VALUES + 1);
        assertThat(code.values()).isNull();
    }

    @Test
    void aSourceMayNotNameAFieldProject(@TempDir Path other) throws IOException {
        String root = TestKnowledge.root().toString().replace('\\', '/');
        Path file = other.resolve("indexes.json");
        Files.writeString(file, """
                {"indexes": {"docs": {"sources": [{"path": "%s/articles",
                  "map": {"fields": {"project": "x"}}}]}}}""".formatted(root));
        IndexDefinition definition = IndexesLoader.load(file, name -> null).getFirst();

        assertThatThrownBy(() -> IndexBuilder.build(definition, StepRegistry.builtIn(), other, "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the field name 'project' is reserved for the project of the index")
                .hasMessageContaining("rename it in map.fields");
    }
}
