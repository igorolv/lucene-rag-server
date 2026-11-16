package ru.it_spectrum.ai.rag.mcp.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.TestKnowledge;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.index.IndexStore;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.it_spectrum.ai.rag.mcp.TestKnowledge.API;
import static ru.it_spectrum.ai.rag.mcp.TestKnowledge.DOCS;

class CommandsTest {

    @TempDir
    Path dataDir;

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    private String output() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private RagMcpProperties properties() {
        return new RagMcpProperties(dataDir.toString(), null);
    }

    private int index(String... args) {
        return RagIndexCommand.run(args, properties(), StepRegistry.builtIn(), null, out, name -> null, "test");
    }

    private int search(String... args) {
        return RagSearchCommand.run(args, properties(), out);
    }

    @Test
    void indexBuildsOneCollectionOrAll() throws IOException {
        TestKnowledge.writeCollectionsFile(dataDir);

        assertThat(index(DOCS)).isZero();
        assertThat(output()).contains("Collection " + DOCS, "files 3, documents 3", "RESULT: OK (1 collection built)");
        assertThat(new IndexStore(dataDir).built()).extracting(Manifest::name).containsExactly(DOCS);

        assertThat(index("--all")).isZero();
        assertThat(output()).contains("RESULT: OK (2 collections built)");
        assertThat(new IndexStore(dataDir).built()).extracting(Manifest::name).containsExactly(API, DOCS);
    }

    @Test
    void anImportProblemIsReportedAndFailsABuildOfAll() throws IOException {
        Path file = TestKnowledge.writeCollectionsFile(dataDir);
        String json = Files.readString(file);
        Files.writeString(file, json.replaceFirst("\\{", "{\"import\": [\"absent/rag-collections.json\"],"));

        assertThat(index(DOCS)).isZero();
        assertThat(output()).contains("IMPORT: import[0]: ", "does not exist", "RESULT: OK (1 collection built)");

        assertThat(index("--all")).isEqualTo(2);
        assertThat(output()).contains("RESULT: FAILED (2 collections built; 1 import problem)");
        assertThat(new IndexStore(dataDir).built()).extracting(Manifest::name).containsExactly(API, DOCS);
    }

    @Test
    void rebuildingReplacesTheCollectionAndLeavesNoBuildDirectory() throws IOException {
        TestKnowledge.writeCollectionsFile(dataDir);
        assertThat(index(DOCS)).isZero();
        Files.writeString(dataDir.resolve(DOCS).resolve("stale.txt"), "left by the previous build");

        assertThat(index(DOCS)).isZero();

        assertThat(dataDir.resolve(DOCS).resolve("stale.txt")).doesNotExist();
        try (var children = Files.list(dataDir)) {
            assertThat(children.map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder("collections.json", DOCS);
        }
    }

    @Test
    void indexReportsUsageAndConfigurationProblems() throws IOException {
        assertThat(index()).isEqualTo(2);
        assertThat(output()).contains("Usage:");
        assertThat(index("--help")).isZero();

        assertThat(index("--all")).isEqualTo(2);
        assertThat(output()).contains("no collections in");

        TestKnowledge.writeCollectionsFile(dataDir);
        assertThat(index("unknown")).isEqualTo(2);
        assertThat(output()).contains("'unknown' is not in", DOCS);

        Files.writeString(dataDir.resolve("collections.json"),
                "{\"collections\": {\"gone\": {\"sources\": [{\"path\": \"missing-dir\"}]},"
                        + " \"odd\": {\"sources\": [{\"path\": \"a\", \"format\": \"docx\"}]}}}");
        assertThat(index("gone")).isEqualTo(4);
        assertThat(output()).contains("Source path does not exist");
        assertThat(dataDir.resolve("gone")).doesNotExist();
        assertThat(index("odd")).isEqualTo(2);
        assertThat(output()).contains("format \"docx\" is not supported");
    }

    @Test
    void searchPrintsHitsWithTheirSource() throws IOException {
        TestKnowledge.build(dataDir);

        assertThat(search(DOCS + "," + API, "ручная", "привязка", "--limit", "2")).isZero();

        assertThat(output())
                .contains("1. [" + DOCS + "] Платежи › Привязка к счёту")
                .contains("id: article.billing.payments#привязка-к-счёту   source: payments.md")
                .contains("### **Ручная** **привязка**")
                .containsPattern("RESULT: OK \\(\\d+ found, 2 shown, lexical\\)");

        assertThat(search(DOCS, "ручная", "привязка", "--per-document", "3", "--limit", "3")).isZero();
        assertThat(output()).containsPattern("RESULT: OK \\(\\d+ found, 3 shown, lexical\\)");
    }

    @Test
    void searchAcceptsFiltersWithoutAQuery() throws IOException {
        TestKnowledge.build(dataDir);

        assertThat(search(DOCS, "--filter", "kind=playbook")).isZero();

        assertThat(output()).contains("Платёж не привязался к счёту › Симптом").doesNotContain("Возвраты");

        assertThat(search(DOCS, "--filter-expression", "tags in ['refunds'] && kind == 'article'")).isZero();
        String second = output().substring(output().indexOf("RESULT: OK") + 1);
        assertThat(second).contains("Возвраты").doesNotContain("Платёж не привязался");
    }

    @Test
    void searchReportsUsageAndUnknownNames() throws IOException {
        assertThat(search()).isEqualTo(2);
        assertThat(search(DOCS)).isEqualTo(2);
        assertThat(search(DOCS, "x", "--limit", "many")).isEqualTo(2);
        assertThat(search(DOCS, "x", "--per-document", "many")).isEqualTo(2);
        assertThat(search(DOCS, "--lookup", "x", "--per-document", "2")).isEqualTo(2);
        assertThat(output()).contains("Usage:");

        TestKnowledge.build(dataDir);
        assertThat(search("nope", "x")).isEqualTo(2);
        assertThat(output()).contains("Unknown collection 'nope'");
        assertThat(search(DOCS, "x", "--filter", "colour=red")).isEqualTo(2);
        assertThat(output()).contains("Unknown filter field 'colour'");
        assertThat(search(DOCS, "--filter-expression", "kind ==")).isEqualTo(2);
        assertThat(output()).contains("not a filter expression");
        assertThat(search(DOCS, "--lookup", "x", "--filter-expression", "kind == 'a'")).isEqualTo(2);
    }
}
