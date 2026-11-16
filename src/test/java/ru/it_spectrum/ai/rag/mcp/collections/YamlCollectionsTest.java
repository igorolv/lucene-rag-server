package ru.it_spectrum.ai.rag.mcp.collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Collections files written in YAML. */
class YamlCollectionsTest {

    @TempDir
    Path dir;

    private Path write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static final String JSON_MAIN = """
            {"vars": {"root": "%s"},
             "models": {"e5-small": {"queryPrefix": "query: ", "maxTokens": 512, "default": true}},
             "import": [{"file": "${root}/p/knowledge/rag-collections.%s", "commands": true}]}""";

    private static final String JSON_PROJECT = """
            {"readers": {"catalog": {"join": {"cap": {"path": "catalog.md", "split": {"tableRows": true},
                                                      "key": "{column.1}", "on": "`{path[0]}`"}}}},
             "collections": {
               "specs@p": {"description": "Requirements", "vectors": true,
                           "sources": [{"path": "specs", "include": "**/spec.md", "reader": "catalog",
                                        "split": {"level": "h3", "headingPattern": "^Requirement: (?<name>.+)$",
                                                  "maxChars": 1000},
                                        "map": {"bodyPattern": "(?m)^\\\\*\\\\*ID:\\\\*\\\\* (?<id>\\\\S+)",
                                                "title": "{heading.name}",
                                                "breadcrumb": ["{cap.column.3|path[0]}", "{title}"],
                                                "text": "{breadcrumb}\\n\\n{body}",
                                                "fields": {"kind": "spec", "answer": "yes", "sf": "{cap.column.SF}"},
                                                "keys": ["sf"]}}]},
               "export@p": {"sources": [{"command": ["python", "-I", "export.py"], "timeoutSeconds": 300,
                                         "format": "jsonl", "map": {"text": "{record.text}"}}],
                            "vectors": false}}}""";

    /** The same files as {@link #JSON_MAIN} and {@link #JSON_PROJECT}, in YAML. */
    private static final String YAML_MAIN = """
            # The machine: where projects are and which model computes vectors.
            vars:
              root: %s
            models:
              e5-small: { queryPrefix: 'query: ', maxTokens: 512, default: true }
            import:
              - file: ${root}/p/knowledge/rag-collections.%s
                commands: true
            """;

    private static final String YAML_PROJECT = """
            readers:
              catalog:
                join:
                  cap:
                    path: catalog.md
                    split: { tableRows: true }
                    key: '{column.1}'
                    on: '`{path[0]}`'      # YAML 1.1 would read the key on as true

            collections:
              specs@p:
                description: Requirements
                vectors: true
                sources:
                  - path: specs
                    include: '**/spec.md'
                    reader: catalog
                    split:
                      level: h3
                      headingPattern: '^Requirement: (?<name>.+)$'
                      maxChars: 1000      # below the 512 tokens of the model
                    map:
                      bodyPattern: '(?m)^\\*\\*ID:\\*\\* (?<id>\\S+)'
                      title: '{heading.name}'
                      breadcrumb: ['{cap.column.3|path[0]}', '{title}']
                      text: |-
                        {breadcrumb}

                        {body}
                      fields: { kind: spec, answer: yes, sf: '{cap.column.SF}' }
                      keys: [sf]
              export@p:
                sources:
                  - command: [python, -I, export.py]
                    timeoutSeconds: 300
                    format: jsonl
                    map: { text: '{record.text}' }
                vectors: false
            """;

    private List<CollectionDefinition> load(String main, String project, String extension) throws IOException {
        write("p/knowledge/rag-collections." + extension, project);
        Path file = write("data/collections." + extension,
                main.formatted(dir.toString().replace('\\', '/'), extension));
        LoadedCollections loaded = CollectionsLoader.read(file, name -> null);
        assertThat(loaded.importProblems()).isEmpty();
        return StepRegistry.builtIn().checked(loaded.collections());
    }

    @Test
    void aYamlFileGivesTheCollectionsOfTheSameJsonFile() throws IOException {
        List<CollectionDefinition> fromJson = load(JSON_MAIN, JSON_PROJECT, "json");
        List<CollectionDefinition> fromYaml = load(YAML_MAIN, YAML_PROJECT, "yaml");

        assertThat(fromYaml).allMatch(CollectionDefinition::usable);
        assertThat(fromYaml).isEqualTo(fromJson);
        SourceDefinition specs = fromYaml.getFirst().sources().getFirst();
        assertThat(specs.joins().getFirst().on()).isEqualTo("`{path[0]}`");
        assertThat(specs.map().fields()).containsEntry("answer", "yes");
        assertThat(specs.map().text()).isEqualTo("{breadcrumb}\n\n{body}");
        assertThat(specs.map().bodyPattern()).isEqualTo("(?m)^\\*\\*ID:\\*\\* (?<id>\\S+)");
        assertThat(fromYaml.getFirst().vectors().name()).isEqualTo("e5-small");
        assertThat(fromYaml.get(1).sources().getFirst().command().timeoutSeconds()).isEqualTo(300);
    }

    @Test
    void anUnquotedTemplateOrGlobIsExplained() throws IOException {
        Path template = write("data/a.yaml", """
                collections:
                  c:
                    sources:
                      - path: kb
                        map:
                          title: {heading.name}
                """);
        assertThatThrownBy(() -> CollectionsLoader.read(template, name -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("line 6").hasMessageContaining("put it in quotes");

        Path longer = write("data/b.yaml", """
                collections:
                  c:
                    sources:
                      - path: kb
                        map:
                          text: {column.text} in {column.file}
                """);
        assertThatThrownBy(() -> CollectionsLoader.read(longer, name -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid YAML").hasMessageContaining("must be in quotes");

        Path glob = write("data/c.yaml", """
                collections:
                  c:
                    sources:
                      - path: kb
                        include: **/spec.md
                """);
        assertThatThrownBy(() -> CollectionsLoader.read(glob, name -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid YAML").hasMessageContaining("alias");
    }

    @Test
    void aRepeatedKeyOrATopLevelThatIsNotAMappingIsAnError() throws IOException {
        Path repeated = write("data/a.yml", """
                collections:
                  c: { sources: [ { path: a } ] }
                  c: { sources: [ { path: b } ] }
                """);
        assertThatThrownBy(() -> CollectionsLoader.read(repeated, name -> null))
                .hasMessageContaining("line 3").hasMessageContaining("\"c\" is repeated");

        Path list = write("data/b.yaml", "- a\n- b\n");
        assertThatThrownBy(() -> CollectionsLoader.read(list, name -> null)).hasMessageContaining("a YAML mapping");

        Path empty = write("data/c.yaml", "# nothing yet\n");
        assertThat(CollectionsLoader.read(empty, name -> null).collections()).isEmpty();
    }

    @Test
    void theDataDirectoryMayHoldTheCollectionsFileInYamlButNotBoth() throws IOException {
        RagMcpProperties properties = new RagMcpProperties(dir.toString(), null);
        assertThat(properties.resolvedCollectionsFile()).isEqualTo(dir.resolve("collections.json"));

        write("collections.yaml", "collections: {}\n");
        assertThat(properties.resolvedCollectionsFile()).isEqualTo(dir.resolve("collections.yaml"));

        write("collections.json", "{}");
        assertThatThrownBy(properties::resolvedCollectionsFile).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collections.json and collections.yaml");
    }

    @Test
    void plainOnYesAndNoStayStrings() throws IOException {
        Path file = write("data/collections.yaml", """
                collections:
                  c:
                    sources:
                      - path: kb
                        map: { fields: { a: on, b: no, c: 'true', d: 0123, e: 42, f: +7 } }
                """);
        Map<String, String> fields = CollectionsLoader.load(file, name -> null).getFirst().sources().getFirst()
                .map().fields();

        assertThat(fields).containsEntry("a", "on").containsEntry("b", "no").containsEntry("c", "true");
        // A code keeps its leading zero and its sign; a number as JSON writes it stays as written.
        assertThat(fields).containsEntry("d", "0123").containsEntry("e", "42").containsEntry("f", "+7");

        Path nullTemplate = write("data/collections.yaml", """
                collections:
                  c:
                    sources:
                      - path: kb
                        map: { fields: { d: ~ } }
                """);
        assertThat(CollectionsLoader.load(nullTemplate, name -> null).getFirst().configError())
                .contains("map.fields.d must be a string");
    }
}
