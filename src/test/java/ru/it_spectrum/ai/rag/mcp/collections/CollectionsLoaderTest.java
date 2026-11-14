package ru.it_spectrum.ai.rag.mcp.collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectionsLoaderTest {

    @TempDir
    Path dir;

    private List<CollectionDefinition> load(String json, Map<String, String> env) throws IOException {
        Path file = dir.resolve("collections.json");
        Files.writeString(file, json);
        return CollectionsLoader.load(file, env::get);
    }

    @Test
    void missingOrEmptyFileMeansNoCollections() throws IOException {
        assertThat(CollectionsLoader.load(dir.resolve("absent.json"), k -> null)).isEmpty();
        assertThat(load("  ", Map.of())).isEmpty();
        assertThat(load("{}", Map.of())).isEmpty();
    }

    @Test
    void readsSourcesWithDefaultsAndResolvesPathsAgainstTheFile() throws IOException {
        List<CollectionDefinition> collections = load("""
                {
                  "models": {"ignored": {}},
                  "collections": {
                    "docs@billing": {
                      "description": "Billing articles",
                      "sources": [
                        {"path": "knowledge/articles"},
                        {"path": "${KB}/playbooks", "include": "**/*.md", "exclude": "**/draft-*",
                         "format": "markdown", "chunk": {"splitBy": "h3", "maxChars": 1000, "minChars": 50}}
                      ],
                      "vectors": {"model": "ignored"}
                    }
                  }
                }
                """, Map.of("KB", dir.resolve("kb").toString()));

        assertThat(collections).hasSize(1);
        CollectionDefinition docs = collections.getFirst();
        assertThat(docs.usable()).isTrue();
        assertThat(docs.name()).isEqualTo("docs@billing");
        assertThat(docs.description()).isEqualTo("Billing articles");
        assertThat(docs.sources()).hasSize(2);
        assertThat(docs.sources().get(0).path()).isEqualTo(dir.resolve("knowledge").resolve("articles"));
        assertThat(docs.sources().get(0).chunk()).isEqualTo(ChunkSettings.DEFAULT);
        assertThat(docs.sources().get(0).include()).isNull();
        assertThat(docs.sources().get(1).path()).isEqualTo(dir.resolve("kb").resolve("playbooks"));
        assertThat(docs.sources().get(1).exclude()).isEqualTo("**/draft-*");
        assertThat(docs.sources().get(1).chunk()).isEqualTo(new ChunkSettings(3, 1000, 50));
    }

    @Test
    void aBrokenEntryIsReportedAndLeavesTheOthersUsable() throws IOException {
        List<CollectionDefinition> collections = load("""
                {"collections": {
                  "bad name": {"sources": [{"path": "a"}]},
                  "logs": {"sources": [{"path": "a"}]},
                  "no-sources": {"description": "d"},
                  "docbook": {"sources": [{"path": "a", "format": "docbook"}]},
                  "no-var": {"sources": [{"path": "${MISSING}/a"}]},
                  "bad-chunk": {"sources": [{"path": "a", "chunk": {"splitBy": "h9"}}]},
                  "good": {"sources": [{"path": "a"}]}
                }}
                """, Map.of());

        assertThat(collections).extracting(CollectionDefinition::name, CollectionDefinition::usable).containsExactly(
                org.assertj.core.groups.Tuple.tuple("bad name", false),
                org.assertj.core.groups.Tuple.tuple("logs", false),
                org.assertj.core.groups.Tuple.tuple("no-sources", false),
                org.assertj.core.groups.Tuple.tuple("docbook", false),
                org.assertj.core.groups.Tuple.tuple("no-var", false),
                org.assertj.core.groups.Tuple.tuple("bad-chunk", false),
                org.assertj.core.groups.Tuple.tuple("good", true));
        assertThat(collections.get(2).description()).isEqualTo("d");
        assertThat(collections.get(3).configError()).contains("docbook", "not supported");
        assertThat(collections.get(4).configError()).contains("MISSING", "sources[0].path");
        assertThat(collections.get(5).configError()).contains("splitBy");
    }

    @Test
    void aFileThatIsNotAJsonObjectIsAnError() {
        assertThatThrownBy(() -> load("{ not json", Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> load("[]", Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be a JSON object");
        assertThatThrownBy(() -> load("{\"collections\": []}", Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("\"collections\"");
    }
}
