package ru.it_spectrum.ai.rag.mcp.tools;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.TestKnowledge;
import ru.it_spectrum.ai.rag.mcp.api.FieldValuesResult;
import ru.it_spectrum.ai.rag.mcp.api.KnowledgeResult;
import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.IndexesLoader;
import ru.it_spectrum.ai.rag.mcp.index.IndexBuilder;
import ru.it_spectrum.ai.rag.mcp.index.IndexStore;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code describeKnowledge} tells the projects and the fields to narrow by, from the manifests alone. */
class KnowledgeToolsTest {

    private static final String DOCS = TestKnowledge.DOCS;
    private static final String API = TestKnowledge.API;

    @TempDir
    static Path dataDir;

    static KnowledgeResult result;

    @BeforeAll
    static void build() throws IOException {
        String root = TestKnowledge.root().toString().replace('\\', '/');
        Path file = dataDir.resolve("indexes.json");
        Files.writeString(file, """
                {"indexes": {
                  "%s": {"project": "billing", "description": "Billing: articles and playbooks",
                         "sources": [{"path": "%s/articles"}, {"path": "%s/playbooks"}]},
                  "%s": {"sources": [{"path": "%s/api"}]}
                }}""".formatted(DOCS, root, root, API, root));
        for (IndexDefinition definition : IndexesLoader.load(file, name -> null)) {
            IndexBuilder.build(definition, StepRegistry.builtIn(), dataDir, "test");
        }
        // Neither the indexes file nor an index is read: only the manifests.
        Files.delete(file);
        result = new KnowledgeTools(new IndexStore(dataDir)).describeKnowledge();
    }

    private static KnowledgeResult.Section section(String label) {
        return result.sections().stream().filter(s -> s.label().equals(label)).findFirst().orElseThrow();
    }

    @Test
    void projectsComeWithTheirRecords() {
        Manifest docs = new IndexStore(dataDir).manifest(DOCS).orElseThrow();
        assertThat(result.projects()).containsExactly(new KnowledgeResult.ProjectInfo("billing", docs.records()));
    }

    @Test
    void eachSectionTellsWhatItHoldsAndItsFieldsWithFewValuesListed() {
        assertThat(result.sections()).extracting(KnowledgeResult.Section::label).containsExactly(API, DOCS);

        KnowledgeResult.Section docs = section(DOCS);
        assertThat(docs.project()).isEqualTo("billing");
        assertThat(docs.description()).isEqualTo("Billing: articles and playbooks");
        assertThat(docs.records()).isPositive();
        assertThat(docs.fields()).extracting(KnowledgeResult.FieldInfo::name)
                .contains("kind", "tables").doesNotContain("project");
        KnowledgeResult.FieldInfo kind = docs.fields().stream().filter(f -> f.name().equals("kind")).findFirst()
                .orElseThrow();
        assertThat(kind.distinct()).isEqualTo(kind.values().size());
        assertThat(kind.values()).extracting(FieldValuesResult.FieldValue::value).contains("article", "playbook");

        assertThat(section(API).project()).isNull();
    }
}
