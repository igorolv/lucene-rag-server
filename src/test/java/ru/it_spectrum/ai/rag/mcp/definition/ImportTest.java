package ru.it_spectrum.ai.rag.mcp.definition;

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

/** An indexes file that imports the indexes files of projects ({@code import}). */
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

    private LoadedIndexes read(String main) throws IOException {
        return read(main, Map.of());
    }

    private LoadedIndexes read(String main, Map<String, String> env) throws IOException {
        Path file = write("data/indexes.json", main);
        return IndexesLoader.read(file, env::get);
    }

    private static IndexDefinition named(LoadedIndexes loaded, String name) {
        return loaded.indexes().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void anImportedFileAddsItsIndexesWithItsOwnPathsReadersAndTheModelsOfTheMainFile() throws IOException {
        write("billing/knowledge/rag-indexes.json", """
                {"readers": {"articles": {"split": {"level": "h3"}}},
                 "indexes": {
                   "docs@billing": {"vectors": {"model": "e5-small"},
                                    "sources": [{"path": "articles", "reader": "articles"}]},
                   "catalogs@billing": {"sources": [{"path": "catalogs/menu", "format": "tsv",
                                                     "map": {"text": "{column.name}"}}]}}}""");
        write("billing/openspec/rag-indexes.json", """
                {"indexes": {"specs@billing": {"sources": [{"path": "specs"}]}}}""");

        LoadedIndexes loaded = read("{" + MODELS + """
                , "import": ["../billing/knowledge/rag-indexes.json",
                             {"file": "${PROJECTS}/billing/openspec/rag-indexes.json"}],
                 "indexes": {"notes@home": {"sources": [{"path": "notes"}]}}}""",
                Map.of("PROJECTS", dir.toString()));

        assertThat(loaded.importProblems()).isEmpty();
        assertThat(loaded.indexes()).extracting(IndexDefinition::name)
                .containsExactly("notes@home", "docs@billing", "catalogs@billing", "specs@billing");
        assertThat(named(loaded, "notes@home").sources().getFirst().path()).isEqualTo(dir.resolve("data/notes"));
        IndexDefinition docs = named(loaded, "docs@billing");
        assertThat(docs.sources().getFirst().path()).isEqualTo(dir.resolve("billing/knowledge/articles"));
        assertThat(docs.sources().getFirst().split().level()).isEqualTo(3);
        assertThat(docs.vectors().name()).isEqualTo("e5-small");
        assertThat(named(loaded, "specs@billing").sources().getFirst().path())
                .isEqualTo(dir.resolve("billing/openspec/specs"));
        assertThat(StepRegistry.builtIn().checked(loaded.indexes())).allMatch(IndexDefinition::usable);
        // load gives the same indexes without the problems.
        assertThat(IndexesLoader.load(dir.resolve("data/indexes.json"), name -> dir.toString()))
                .hasSize(4);
    }

    @Test
    void aMissingOrWrongImportIsAProblemThatLeavesTheOtherIndexes() throws IOException {
        write("broken/rag-indexes.json", "{ not json");
        write("withmodels/rag-indexes.json", "{" + MODELS + ", \"indexes\": {}}");
        write("nested/rag-indexes.json", "{\"import\": [\"x.json\"]}");
        write("good/rag-indexes.json", "{\"indexes\": {\"good@p\": {\"sources\": [{\"path\": \"a\"}]}}}");

        LoadedIndexes loaded = read("""
                {"import": ["../absent/rag-indexes.json", "../broken/rag-indexes.json",
                            "../withmodels/rag-indexes.json", "../nested/rag-indexes.json",
                            42, {"commands": true}, "../good/rag-indexes.json"]}""");

        assertThat(loaded.indexes()).extracting(IndexDefinition::name).containsExactly("good@p");
        assertThat(loaded.importProblems()).hasSize(6);
        assertThat(loaded.importProblems().get(0)).startsWith("import[0]: ").contains("does not exist");
        assertThat(loaded.importProblems().get(1)).startsWith("import[1]: ").contains("not valid JSON");
        assertThat(loaded.importProblems().get(2)).contains("has \"models\"", "only \"indexes\", \"readers\" and \"project\"");
        assertThat(loaded.importProblems().get(3)).contains("has \"import\"");
        assertThat(loaded.importProblems().get(4)).startsWith("import[4]: ").contains("a file path or");
        assertThat(loaded.importProblems().get(5)).startsWith("import[5]: ").contains("a file path or");
    }

    @Test
    void aNameTakenAlreadyLeavesTheLaterIndexOut() throws IOException {
        write("a/rag-indexes.json", "{\"indexes\": {\"docs@p\": {\"sources\": [{\"path\": \"from-a\"}]},"
                + " \"only-a@p\": {\"sources\": [{\"path\": \"x\"}]}}}");
        write("b/rag-indexes.json", "{\"indexes\": {\"docs@p\": {\"sources\": [{\"path\": \"from-b\"}]}}}");

        LoadedIndexes loaded = read("""
                {"import": ["../a/rag-indexes.json", "../b/rag-indexes.json"],
                 "indexes": {"only-a@p": {"sources": [{"path": "main"}]}}}""");

        assertThat(loaded.indexes()).extracting(IndexDefinition::name).containsExactly("only-a@p", "docs@p");
        assertThat(named(loaded, "only-a@p").sources().getFirst().path()).isEqualTo(dir.resolve("data/main"));
        assertThat(named(loaded, "docs@p").sources().getFirst().path()).isEqualTo(dir.resolve("a/from-a"));
        assertThat(loaded.importProblems()).hasSize(2);
        assertThat(loaded.importProblems().get(0)).startsWith("import[0]: ")
                .contains("'only-a@p'", "is left out", "taken by an index of");
        assertThat(loaded.importProblems().get(1)).startsWith("import[1]: ").contains("'docs@p'", "is left out");
    }

    @Test
    void theCommandsOfAnImportedFileRunOnlyWhenItsImportAllowsThem() throws IOException {
        write("p/rag-indexes.json", """
                {"indexes": {
                  "origin@p": {"sources": [{"command": ["python", "export.py"], "format": "jsonl",
                                            "map": {"text": "{record.text}"}}]},
                  "convert@p": {"sources": [{"path": "docs", "convert": [{"command": ["pandoc", "{file}"]}]}]},
                  "join@p": {"sources": [{"path": "docs", "join": {"menu": {"command": ["python", "menu.py"],
                              "format": "tsv", "key": "{column.1}", "on": "{path[0]}"}}}]},
                  "files@p": {"sources": [{"path": "docs"}]}}}""");

        LoadedIndexes denied = read("{\"import\": [\"../p/rag-indexes.json\"]}");

        assertThat(named(denied, "files@p").usable()).isTrue();
        for (String name : List.of("origin@p", "convert@p", "join@p")) {
            assertThat(named(denied, name).configError()).contains("runs a command", "\"commands\": true");
        }
        assertThat(denied.importProblems()).isEmpty();

        LoadedIndexes allowed = read("{\"import\": [{\"file\": \"../p/rag-indexes.json\", \"commands\": true}]}");

        assertThat(allowed.indexes()).allMatch(IndexDefinition::usable);
        CommandSettings command = named(allowed, "origin@p").sources().getFirst().command();
        assertThat(command.directory()).isEqualTo(dir.resolve("p"));
    }

    @Test
    void varsOfTheMainFileAreLookedUpBeforeTheEnvironmentInItAndInTheFilesItImports() throws IOException {
        write("projects/billing/knowledge/rag-indexes.json", """
                {"indexes": {"docs@billing": {"sources": [{"path": "articles"},
                                                              {"path": "${shared}/glossary"}]}}}""");

        LoadedIndexes loaded = read("""
                {"vars": {"projects": "${ROOT}/projects", "shared": "${projects}-not-a-var", "HOME": "overridden"},
                 "import": ["${projects}/billing/knowledge/rag-indexes.json"],
                 "indexes": {"notes@home": {"sources": [{"path": "${HOME}/notes"}]}}}""",
                Map.of("ROOT", dir.toString(), "HOME", "from-environment", "projects", "env-projects"));

        assertThat(loaded.importProblems()).isEmpty();
        assertThat(named(loaded, "notes@home").sources().getFirst().path()).isEqualTo(dir.resolve("data/overridden/notes"));
        List<SourceDefinition> docs = named(loaded, "docs@billing").sources();
        assertThat(docs.get(0).path()).isEqualTo(dir.resolve("projects/billing/knowledge/articles"));
        // A value of vars names environment variables only, not other entries of vars.
        assertThat(docs.get(1).path().toString()).endsWith("env-projects-not-a-var" + java.io.File.separator + "glossary");
    }

    @Test
    void wrongVarsFailTheFileAndVarsInAnImportedFileAreAProblem() throws IOException {
        for (String vars : List.of("\"vars\": [\"a\"]", "\"vars\": {\"1a\": \"x\"}", "\"vars\": {\"a\": 1}",
                "\"vars\": {\"a\": \"${UNSET}\"}")) {
            Path file = write("data/indexes.json", "{" + vars + "}");
            assertThatThrownBy(() -> IndexesLoader.read(file, name -> null))
                    .as(vars).isInstanceOf(IllegalStateException.class).hasMessageContaining("vars");
        }
        Path file = write("data/indexes.json", "{\"indexes\": {\"c\": {\"sources\": [{\"path\": \"${nope}\"}]}}}");
        assertThat(IndexesLoader.read(file, name -> null).indexes().getFirst().configError())
                .contains("'nope'", "neither in \"vars\" nor in the environment");

        write("p/rag-indexes.json", "{\"vars\": {\"a\": \"b\"}, \"indexes\": {}}");
        assertThat(read("{\"import\": [\"../p/rag-indexes.json\"]}").importProblems().getFirst())
                .contains("has \"vars\"");
    }

    @Test
    void vectorsTrueTakesTheDefaultModelOfTheMainFile() throws IOException {
        write("p/rag-indexes.json", """
                {"indexes": {
                  "docs@p": {"vectors": true, "sources": [{"path": "a"}]},
                  "named@p": {"vectors": {"model": "e5-base"}, "sources": [{"path": "a"}]},
                  "lexical@p": {"vectors": false, "sources": [{"path": "a"}]},
                  "wrong@p": {"vectors": "e5-small", "sources": [{"path": "a"}]}}}""");

        LoadedIndexes loaded = read("""
                {"models": {"e5-small": {"queryPrefix": "query: ", "default": true}, "e5-base": {}},
                 "import": ["../p/rag-indexes.json"]}""");

        assertThat(named(loaded, "docs@p").vectors().name()).isEqualTo("e5-small");
        assertThat(named(loaded, "docs@p").vectors().queryPrefix()).isEqualTo("query: ");
        assertThat(named(loaded, "named@p").vectors().name()).isEqualTo("e5-base");
        assertThat(named(loaded, "lexical@p").vectors()).isNull();
        assertThat(named(loaded, "lexical@p").usable()).isTrue();
        assertThat(named(loaded, "wrong@p").configError()).contains("must be true, false or an object");

        LoadedIndexes noDefault = read("""
                {"models": {"e5-small": {}}, "import": ["../p/rag-indexes.json"]}""");

        assertThat(named(noDefault, "docs@p").configError())
                .contains("\"vectors\": true needs a model marked \"default\": true", "defined: e5-small");
        assertThat(named(noDefault, "named@p").configError()).contains("\"e5-base\" is not defined");
    }

    @Test
    void twoDefaultModelsOrAWrongFlagFailTheFile() throws IOException {
        for (String models : List.of("{\"a\": {\"default\": true}, \"b\": {\"default\": true}}",
                "{\"a\": {\"default\": \"yes\"}}")) {
            Path file = write("data/indexes.json", "{\"models\": " + models + "}");
            assertThatThrownBy(() -> IndexesLoader.read(file, name -> null))
                    .as(models).isInstanceOf(IllegalStateException.class).hasMessageContaining("default");
        }
    }

    @Test
    void aWrongImportKeyOfTheMainFileFailsTheWholeFile() throws IOException {
        Path file = write("data/indexes.json", "{\"import\": \"one.json\"}");

        assertThatThrownBy(() -> IndexesLoader.read(file, name -> null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("\"import\"", "array");
    }
}
