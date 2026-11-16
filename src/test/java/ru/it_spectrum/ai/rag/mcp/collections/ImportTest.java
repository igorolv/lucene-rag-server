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

/** A collections file that imports the collections files of projects ({@code import}). */
class ImportTest {

    private static final String MODELS = """
            "models": {"e5-small": {"type": "onnx", "queryPrefix": "query: ", "passagePrefix": "passage: "}}""";

    @TempDir
    Path dir;

    private Path write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private LoadedCollections read(String main) throws IOException {
        return read(main, Map.of());
    }

    private LoadedCollections read(String main, Map<String, String> env) throws IOException {
        Path file = write("data/collections.json", main);
        return CollectionsLoader.read(file, env::get);
    }

    private static CollectionDefinition named(LoadedCollections loaded, String name) {
        return loaded.collections().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void anImportedFileAddsItsCollectionsWithItsOwnPathsReadersAndTheModelsOfTheMainFile() throws IOException {
        write("billing/knowledge/rag-collections.json", """
                {"readers": {"articles": {"split": {"level": "h3"}}},
                 "collections": {
                   "docs@billing": {"vectors": {"model": "e5-small"},
                                    "sources": [{"path": "articles", "reader": "articles"}]},
                   "catalogs@billing": {"sources": [{"path": "catalogs/menu", "format": "tsv",
                                                     "map": {"text": "{column.name}"}}]}}}""");
        write("billing/openspec/rag-collections.json", """
                {"collections": {"specs@billing": {"sources": [{"path": "specs"}]}}}""");

        LoadedCollections loaded = read("{" + MODELS + """
                , "import": ["../billing/knowledge/rag-collections.json",
                             {"file": "${PROJECTS}/billing/openspec/rag-collections.json"}],
                 "collections": {"notes@home": {"sources": [{"path": "notes"}]}}}""",
                Map.of("PROJECTS", dir.toString()));

        assertThat(loaded.importProblems()).isEmpty();
        assertThat(loaded.collections()).extracting(CollectionDefinition::name)
                .containsExactly("notes@home", "docs@billing", "catalogs@billing", "specs@billing");
        assertThat(named(loaded, "notes@home").sources().getFirst().path()).isEqualTo(dir.resolve("data/notes"));
        CollectionDefinition docs = named(loaded, "docs@billing");
        assertThat(docs.sources().getFirst().path()).isEqualTo(dir.resolve("billing/knowledge/articles"));
        assertThat(docs.sources().getFirst().split().level()).isEqualTo(3);
        assertThat(docs.vectors().name()).isEqualTo("e5-small");
        assertThat(named(loaded, "specs@billing").sources().getFirst().path())
                .isEqualTo(dir.resolve("billing/openspec/specs"));
        assertThat(StepRegistry.builtIn().checked(loaded.collections())).allMatch(CollectionDefinition::usable);
        // load gives the same collections without the problems.
        assertThat(CollectionsLoader.load(dir.resolve("data/collections.json"), name -> dir.toString()))
                .hasSize(4);
    }

    @Test
    void aMissingOrWrongImportIsAProblemThatLeavesTheOtherCollections() throws IOException {
        write("broken/rag-collections.json", "{ not json");
        write("withmodels/rag-collections.json", "{" + MODELS + ", \"collections\": {}}");
        write("nested/rag-collections.json", "{\"import\": [\"x.json\"]}");
        write("good/rag-collections.json", "{\"collections\": {\"good@p\": {\"sources\": [{\"path\": \"a\"}]}}}");

        LoadedCollections loaded = read("""
                {"import": ["../absent/rag-collections.json", "../broken/rag-collections.json",
                            "../withmodels/rag-collections.json", "../nested/rag-collections.json",
                            42, {"commands": true}, "../good/rag-collections.json"]}""");

        assertThat(loaded.collections()).extracting(CollectionDefinition::name).containsExactly("good@p");
        assertThat(loaded.importProblems()).hasSize(6);
        assertThat(loaded.importProblems().get(0)).startsWith("import[0]: ").contains("does not exist");
        assertThat(loaded.importProblems().get(1)).startsWith("import[1]: ").contains("not valid JSON");
        assertThat(loaded.importProblems().get(2)).contains("has \"models\"", "only \"collections\" and \"readers\"");
        assertThat(loaded.importProblems().get(3)).contains("has \"import\"");
        assertThat(loaded.importProblems().get(4)).startsWith("import[4]: ").contains("a file path or");
        assertThat(loaded.importProblems().get(5)).startsWith("import[5]: ").contains("a file path or");
    }

    @Test
    void aNameTakenAlreadyLeavesTheLaterCollectionOut() throws IOException {
        write("a/rag-collections.json", "{\"collections\": {\"docs@p\": {\"sources\": [{\"path\": \"from-a\"}]},"
                + " \"only-a@p\": {\"sources\": [{\"path\": \"x\"}]}}}");
        write("b/rag-collections.json", "{\"collections\": {\"docs@p\": {\"sources\": [{\"path\": \"from-b\"}]}}}");

        LoadedCollections loaded = read("""
                {"import": ["../a/rag-collections.json", "../b/rag-collections.json"],
                 "collections": {"only-a@p": {"sources": [{"path": "main"}]}}}""");

        assertThat(loaded.collections()).extracting(CollectionDefinition::name).containsExactly("only-a@p", "docs@p");
        assertThat(named(loaded, "only-a@p").sources().getFirst().path()).isEqualTo(dir.resolve("data/main"));
        assertThat(named(loaded, "docs@p").sources().getFirst().path()).isEqualTo(dir.resolve("a/from-a"));
        assertThat(loaded.importProblems()).hasSize(2);
        assertThat(loaded.importProblems().get(0)).startsWith("import[0]: ")
                .contains("'only-a@p'", "is left out", "taken by a collection of");
        assertThat(loaded.importProblems().get(1)).startsWith("import[1]: ").contains("'docs@p'", "is left out");
    }

    @Test
    void theCommandsOfAnImportedFileRunOnlyWhenItsImportAllowsThem() throws IOException {
        write("p/rag-collections.json", """
                {"collections": {
                  "origin@p": {"sources": [{"command": ["python", "export.py"], "format": "jsonl",
                                            "map": {"text": "{record.text}"}}]},
                  "convert@p": {"sources": [{"path": "docs", "convert": [{"command": ["pandoc", "{file}"]}]}]},
                  "join@p": {"sources": [{"path": "docs", "join": {"menu": {"command": ["python", "menu.py"],
                              "format": "tsv", "key": "{column.1}", "on": "{path[0]}"}}}]},
                  "files@p": {"sources": [{"path": "docs"}]}}}""");

        LoadedCollections denied = read("{\"import\": [\"../p/rag-collections.json\"]}");

        assertThat(named(denied, "files@p").usable()).isTrue();
        for (String name : List.of("origin@p", "convert@p", "join@p")) {
            assertThat(named(denied, name).configError()).contains("runs a command", "\"commands\": true");
        }
        assertThat(denied.importProblems()).isEmpty();

        LoadedCollections allowed = read("{\"import\": [{\"file\": \"../p/rag-collections.json\", \"commands\": true}]}");

        assertThat(allowed.collections()).allMatch(CollectionDefinition::usable);
        CommandSettings command = named(allowed, "origin@p").sources().getFirst().command();
        assertThat(command.directory()).isEqualTo(dir.resolve("p"));
    }

    @Test
    void aWrongImportKeyOfTheMainFileFailsTheWholeFile() throws IOException {
        Path file = write("data/collections.json", "{\"import\": \"one.json\"}");

        assertThatThrownBy(() -> CollectionsLoader.read(file, name -> null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("\"import\"", "array");
    }
}
