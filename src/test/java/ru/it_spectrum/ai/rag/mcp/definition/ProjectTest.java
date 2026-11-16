package ru.it_spectrum.ai.rag.mcp.definition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The {@code project} key of an index and of an imported file (docs/decisions.md D83, D85). */
class ProjectTest {

    @TempDir
    Path dir;

    private Path write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private LoadedIndexes read(String main) throws IOException {
        return IndexesLoader.read(write("data/indexes.json", main), name -> null);
    }

    private static IndexDefinition named(LoadedIndexes loaded, String name) {
        return loaded.indexes().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void anIndexNamesItsProjectOrHasNone() throws IOException {
        LoadedIndexes loaded = read("""
                {"indexes": {
                  "docs@billing": {"project": "billing", "sources": [{"path": "docs"}]},
                  "notes": {"sources": [{"path": "notes"}]}}}""");

        assertThat(named(loaded, "docs@billing").project()).isEqualTo("billing");
        assertThat(named(loaded, "docs@billing").usable()).isTrue();
        assertThat(named(loaded, "notes").project()).isNull();
    }

    @Test
    void theTopOfAnImportedFileSetsTheProjectOfItsIndexesAndAnIndexOverridesIt() throws IOException {
        write("billing/rag-indexes.yaml", """
                project: billing
                indexes:
                  docs@billing:
                    sources: [{path: docs}]
                  shared@billing:
                    project: common
                    sources: [{path: shared}]
                """);

        LoadedIndexes loaded = read("{\"import\": [\"../billing/rag-indexes.yaml\"]}");

        assertThat(loaded.importProblems()).isEmpty();
        assertThat(named(loaded, "docs@billing").project()).isEqualTo("billing");
        assertThat(named(loaded, "shared@billing").project()).isEqualTo("common");
    }

    @Test
    void aWrongValueBreaksTheIndexesItAppliesTo() throws IOException {
        write("p/rag-indexes.json", """
                {"project": "bad name",
                 "indexes": {"a@p": {"sources": [{"path": "a"}]},
                             "b@p": {"project": "p", "sources": [{"path": "b"}]}}}""");

        LoadedIndexes loaded = read("""
                {"import": ["../p/rag-indexes.json"],
                 "indexes": {"c": {"project": 42, "sources": [{"path": "c"}]},
                             "d": {"project": "billing/x", "sources": [{"path": "d"}]}}}""");

        assertThat(named(loaded, "a@p").configError())
                .contains("\"project\" at the top of its file must be a string of letters", "\"bad name\"");
        assertThat(named(loaded, "b@p").usable()).isTrue();
        assertThat(named(loaded, "c").configError()).startsWith("\"project\" must be a string").contains("42");
        assertThat(named(loaded, "d").configError()).contains("\"billing/x\"");
    }

    @Test
    void theMainFileHasNoProjectAtItsTop() throws IOException {
        Path file = write("data/indexes.json", """
                {"project": "billing", "indexes": {"docs": {"sources": [{"path": "docs"}]}}}""");

        assertThatThrownBy(() -> IndexesLoader.read(file, name -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("\"project\" at the top of")
                .hasMessageContaining("set \"project\" on each index");
    }
}
