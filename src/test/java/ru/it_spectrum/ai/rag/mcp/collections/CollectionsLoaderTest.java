package ru.it_spectrum.ai.rag.mcp.collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

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
        return StepRegistry.builtIn().checked(CollectionsLoader.load(file, env::get));
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
                         "format": "markdown",
                         "split": {"level": "h3", "headingPattern": "^Requirement: (?<name>.+)$",
                                   "maxChars": 1000, "minChars": 50},
                         "map": {"pathPattern": "^(?<area>[a-z]+)-", "document": "{path[0]}",
                                 "id": "{document.key}/{slug}",
                                 "title": "{heading.name}", "breadcrumb": ["{path[0]}", "{title}"],
                                 "text": "{breadcrumb}\\n\\n{body}",
                                 "fields": {"kind": "spec", "weight": 3, "owner": "{frontmatter.owner}",
                                            "area": "{path.area}"},
                                 "inheritFields": ["tables"]}}
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
        assertThat(docs.sources().get(0).split()).isEqualTo(SplitSettings.DEFAULT);
        assertThat(docs.sources().get(0).map()).isEqualTo(MapSettings.DEFAULT);
        assertThat(docs.sources().get(0).include()).isNull();
        assertThat(docs.sources().get(1).path()).isEqualTo(dir.resolve("kb").resolve("playbooks"));
        assertThat(docs.sources().get(1).exclude()).isEqualTo("**/draft-*");
        assertThat(docs.sources().get(1).split())
                .isEqualTo(new SplitSettings(3, "^Requirement: (?<name>.+)$", 1000, 50));
        MapSettings map = docs.sources().get(1).map();
        assertThat(map.pathPattern()).isEqualTo("^(?<area>[a-z]+)-");
        assertThat(map.document()).isEqualTo("{path[0]}");
        assertThat(map.id()).isEqualTo("{document.key}/{slug}");
        assertThat(map.title()).isEqualTo("{heading.name}");
        assertThat(map.breadcrumb()).containsExactly("{path[0]}", "{title}");
        assertThat(map.text()).isEqualTo("{breadcrumb}\n\n{body}");
        assertThat(map.fields()).containsExactly(Map.entry("kind", "spec"), Map.entry("weight", "3"),
                Map.entry("owner", "{frontmatter.owner}"), Map.entry("area", "{path.area}"));
        assertThat(map.inheritFields()).containsExactly("tables");
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
                  "bad-chunk": {"sources": [{"path": "a", "split": {"level": "h9"}}]},
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
        assertThat(collections.get(5).configError()).contains("split.level");
    }

    private String errorOf(String source) throws IOException {
        List<CollectionDefinition> collections = load(
                "{\"collections\": {\"c\": {\"sources\": [" + source + "]}}}", Map.of());
        assertThat(collections.getFirst().usable()).isFalse();
        return collections.getFirst().configError();
    }

    @Test
    void splitAndMapMistakesAreConfigurationErrors() throws IOException {
        assertThat(errorOf("{\"path\": \"a\", \"chunk\": {}}")).contains("renamed to \"split\"");
        assertThat(errorOf("{\"path\": \"a\", \"split\": {\"headingPattern\": \"(\"}}"))
                .contains("split.headingPattern", "regular expression");
        assertThat(errorOf("{\"path\": \"a\", \"split\": {\"level\": \"document\", \"headingPattern\": \"x\"}}"))
                .contains("needs a heading level");
        assertThat(errorOf("{\"path\": \"a\", \"map\": {\"title\": \"{headng}\"}}"))
                .contains("map.title", "{headng}", "not a value of a Markdown source");
        assertThat(errorOf("{\"path\": \"a\", \"map\": {\"document\": \"{title}\"}}"))
                .contains("map.document", "not available here");
        assertThat(errorOf("{\"path\": \"a\", \"map\": {\"title\": \"{heading.name}\"}}"))
                .contains("named group (?<name>");
        assertThat(errorOf("{\"path\": \"a\", \"map\": {\"text\": \"{body\"}}")).contains("map.text", "unclosed");
        assertThat(errorOf("{\"path\": \"a\", \"map\": {\"fields\": {\"k\": \"{path.area}\"}}}"))
                .contains("named group (?<area>", "map.pathPattern");
        assertThat(errorOf("{\"path\": \"a\", \"map\": {\"pathPattern\": \"(?<name>x)\"}}"))
                .contains("map.pathPattern", "taken by {path.name}");
        assertThat(errorOf("{\"path\": \"a\", \"map\": {\"pathPattern\": \"[\"}}"))
                .contains("map.pathPattern", "regular expression");
        assertThat(errorOf("{\"path\": \"a\", \"map\": {\"fields\": {\"bad name\": \"x\"}}}"))
                .contains("map.fields", "bad name");
        assertThat(errorOf("{\"path\": \"a\", \"map\": {\"fields\": {\"k\": [1]}}}")).contains("map.fields.k");
        assertThat(errorOf("{\"path\": \"a\", \"map\": {\"breadcrumb\": \"{title}\"}}"))
                .contains("map.breadcrumb", "array");
    }

    @Test
    void aSourceTakesTheKeysOfItsReaderAndReplacesTheOnesItSets() throws IOException {
        List<CollectionDefinition> collections = load("""
                {
                  "readers": {
                    "notes": {"format": "markdown",
                              "split": {"level": "document", "maxChars": 1000},
                              "map": {"title": "{document.title}", "fields": {"kind": "note"}},
                              "path": "ignored", "include": "**/*.txt"}
                  },
                  "collections": {
                    "a": {"sources": [
                      {"path": "notes", "reader": "notes"},
                      {"path": "more", "reader": "notes", "split": {"level": "h3"},
                       "map": {"fields": {"kind": "other"}}}
                    ]}
                  }
                }
                """, Map.of());

        CollectionDefinition a = collections.getFirst();
        assertThat(a.usable()).isTrue();
        SourceDefinition plain = a.sources().get(0);
        assertThat(plain.path()).isEqualTo(dir.resolve("notes"));
        assertThat(plain.include()).as("only reading keys come from a reader").isNull();
        assertThat(plain.format()).isEqualTo("markdown");
        assertThat(plain.split()).isEqualTo(new SplitSettings(SplitSettings.DOCUMENT, null, 1000, 0));
        assertThat(plain.map().title()).isEqualTo("{document.title}");
        assertThat(plain.map().fields()).containsExactly(Map.entry("kind", "note"));

        SourceDefinition overridden = a.sources().get(1);
        assertThat(overridden.format()).isEqualTo("markdown");
        assertThat(overridden.split()).as("split replaced whole, maxChars back to its default")
                .isEqualTo(new SplitSettings(3, null, SplitSettings.DEFAULT.maxChars(), 0));
        assertThat(overridden.map().title()).as("map replaced whole").isNull();
        assertThat(overridden.map().fields()).containsExactly(Map.entry("kind", "other"));
    }

    @Test
    void aReaderThatIsMissingOrWrongBreaksOnlyTheCollectionsThatNameIt() throws IOException {
        List<CollectionDefinition> collections = load("""
                {
                  "readers": {"broken": "markdown", "deep": {"split": {"level": "h9"}},
                              "rows": {"format": "docbook"}},
                  "collections": {
                    "unknown": {"sources": [{"path": "a", "reader": "nothing"}]},
                    "not-object": {"sources": [{"path": "a", "reader": "broken"}]},
                    "bad-split": {"sources": [{"path": "a", "reader": "deep"}]},
                    "bad-format": {"sources": [{"path": "a", "reader": "rows"}]},
                    "not-a-name": {"sources": [{"path": "a", "reader": ["x"]}]},
                    "good": {"sources": [{"path": "a"}]}
                  }
                }
                """, Map.of());

        assertThat(collections).extracting(CollectionDefinition::usable)
                .containsExactly(false, false, false, false, false, true);
        assertThat(collections.get(0).configError()).contains("sources[0]", "reader \"nothing\"", "not defined");
        assertThat(collections.get(1).configError()).contains("readers.broken", "JSON object");
        assertThat(collections.get(2).configError()).contains("split.level");
        assertThat(collections.get(3).configError()).contains("docbook", "not supported");
        assertThat(collections.get(4).configError()).contains("\"reader\" must be");
    }

    @Test
    void readersThatAreNotAnObjectAreAnErrorOfTheFile() {
        assertThatThrownBy(() -> load("{\"readers\": [], \"collections\": {}}", Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("\"readers\"");
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

    @Test
    void aCollectionNamesItsModelInVectors() throws IOException {
        List<CollectionDefinition> collections = load("""
                {"models": {"e5-small": {"type": "onnx", "queryPrefix": "query: ", "passagePrefix": "passage: ",
                                         "maxTokens": 256},
                            "plain": {}},
                 "collections": {
                   "docs@billing": {"sources": [{"path": "kb"}], "vectors": {"model": "e5-small"}},
                   "notes@billing": {"sources": [{"path": "kb"}], "vectors": {"model": "plain"}},
                   "catalogs@billing": {"sources": [{"path": "kb"}]}
                 }}
                """, Map.of());

        assertThat(collections.get(0).vectors())
                .isEqualTo(new ModelDefinition("e5-small", "onnx", "query: ", "passage: ", 256));
        assertThat(collections.get(1).vectors()).isEqualTo(new ModelDefinition("plain", "onnx", "", "", 512));
        assertThat(collections.get(2).vectors()).isNull();
        assertThat(collections).allMatch(CollectionDefinition::usable);
    }

    @Test
    void aModelNamesItsPooling() throws IOException {
        List<CollectionDefinition> collections = load("""
                {"models": {"bge": {"pooling": "cls"}, "odd": {"pooling": "max"}},
                 "collections": {
                   "docs@billing": {"sources": [{"path": "kb"}], "vectors": {"model": "bge"}},
                   "notes@billing": {"sources": [{"path": "kb"}], "vectors": {"model": "odd"}}
                 }}
                """, Map.of());

        assertThat(collections.get(0).vectors().pooling()).isEqualTo("cls");
        assertThat(collections.get(1).usable()).isFalse();
        assertThat(collections.get(1).configError()).contains("pooling must be mean or cls, not 'max'");
    }

    @Test
    void aWrongModelBreaksOnlyTheCollectionsThatNameIt() throws IOException {
        List<CollectionDefinition> collections = load("""
                {"models": {"bad": {"maxTokens": 0}, "../up": {}},
                 "collections": {
                   "a@x": {"sources": [{"path": "kb"}], "vectors": {"model": "absent"}},
                   "b@x": {"sources": [{"path": "kb"}], "vectors": {"model": "bad"}},
                   "c@x": {"sources": [{"path": "kb"}], "vectors": {"model": "../up"}},
                   "d@x": {"sources": [{"path": "kb"}], "vectors": "bad"},
                   "e@x": {"sources": [{"path": "kb"}]}
                 }}
                """, Map.of());

        assertThat(collections.get(0).configError()).contains("vectors.model \"absent\" is not defined")
                .contains("bad");
        assertThat(collections.get(1).configError()).contains("models.bad: maxTokens must be at least 1");
        assertThat(collections.get(2).configError()).contains("directory of the model files");
        assertThat(collections.get(3).configError()).contains("\"vectors\" must be an object");
        assertThat(collections.get(4).usable()).isTrue();
        assertThatThrownBy(() -> load("{\"models\": [], \"collections\": {}}", Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("\"models\"");
    }
}
