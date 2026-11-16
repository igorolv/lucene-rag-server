package ru.it_spectrum.ai.rag.mcp.definition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Keys of an indexes file that are not read are warnings, not errors (docs/decisions.md D95). */
class UnknownKeysTest {

    @TempDir
    Path dir;

    @Test
    void misspelledKeysAtEveryLevelAreWarnedAboutAndChangeNothing() throws IOException {
        Files.createDirectories(dir.resolve("articles"));
        Path file = dir.resolve("indexes.yaml");
        Files.writeString(file, """
                vars: { projects: /any }
                modles: {}
                models:
                  e5: { queryprefix: 'query: ', default: true }
                readers:
                  notes: { spilt: { level: h3 }, map: { title: '{heading}' } }
                indexes:
                  docs@billing:
                    descripton: Billing articles
                    vectors: { model: e5, modle: e5 }
                    sources:
                      - path: articles
                        exlude: '**/draft-*.md'
                        split: { maxChar: 1500 }
                        map:
                          titel: '{heading}'
                          fields: { anyName: constant, kind: article }
                        where: { anyField: x }
                        join:
                          cap: { path: articles, kye: '{column.1}', key: '{column.1}', on: '{path[0]}',
                                 split: { tableRow: true } }
                      - path: articles
                        reader: notes
                """);

        LoadedIndexes loaded = IndexesLoader.read(file, name -> null);

        assertThat(loaded.indexes()).singleElement().satisfies(index -> {
            assertThat(index.usable()).isTrue();
            assertThat(index.sources().getFirst().exclude()).isNull();
        });
        assertThat(loaded.warnings()).allSatisfy(warning -> assertThat(warning).startsWith(file + ": "))
                .extracting(warning -> warning.substring(file.toString().length() + 2))
                .containsExactly(
                        "unknown key \"modles\" is ignored; did you mean \"models\"?",
                        "models.e5: unknown key \"queryprefix\" is ignored; did you mean \"queryPrefix\"?",
                        "readers.notes: unknown key \"spilt\" is ignored; did you mean \"split\"?",
                        "indexes.docs@billing: unknown key \"descripton\" is ignored; did you mean \"description\"?",
                        "indexes.docs@billing.vectors: unknown key \"modle\" is ignored; did you mean \"model\"?",
                        "indexes.docs@billing.sources[0]: unknown key \"exlude\" is ignored; did you mean \"exclude\"?",
                        "indexes.docs@billing.sources[0].split: unknown key \"maxChar\" is ignored; "
                                + "did you mean \"maxChars\"?",
                        "indexes.docs@billing.sources[0].map: unknown key \"titel\" is ignored; did you mean \"title\"?",
                        "indexes.docs@billing.sources[0].join.cap: unknown key \"kye\" is ignored; did you mean \"key\"?",
                        "indexes.docs@billing.sources[0].join.cap.split: unknown key \"tableRow\" is ignored; "
                                + "did you mean \"tableRows\"?");
    }

    @Test
    void theKeysOfAnImportedFileAndOfAnImportEntryAreChecked() throws IOException {
        Path project = dir.resolve("billing");
        Files.createDirectories(project.resolve("articles"));
        Files.writeString(project.resolve("rag-indexes.json"), """
                {"projekt": "billing", "something": 1,
                 "indexes": {"docs@billing": {"sources": [{"path": "articles"}]}}}
                """);
        Path file = dir.resolve("indexes.json");
        Files.writeString(file, """
                {"import": [{"file": "billing/rag-indexes.json", "comands": true}]}
                """);

        LoadedIndexes loaded = IndexesLoader.read(file, name -> null);

        assertThat(loaded.importProblems()).isEmpty();
        assertThat(loaded.indexes()).hasSize(1);
        assertThat(loaded.warnings()).containsExactly(
                file + ": import[0]: unknown key \"comands\" is ignored; did you mean \"commands\"?",
                project.resolve("rag-indexes.json").normalize()
                        + ": unknown key \"projekt\" is ignored; did you mean \"project\"?",
                project.resolve("rag-indexes.json").normalize() + ": unknown key \"something\" is ignored");
    }

    @Test
    void aFileWithKnownKeysOnlyHasNoWarnings() throws IOException {
        Files.createDirectories(dir.resolve("articles"));
        Path file = dir.resolve("indexes.json");
        Files.writeString(file, """
                {"indexes": {"docs@billing": {"description": "d", "project": "billing",
                  "sources": [{"path": "articles", "include": "**/*.md", "split": {"level": "h2", "maxChars": 1500},
                               "map": {"title": "{heading}", "fields": {"kind": "article"}, "keys": ["kind"]},
                               "convert": [{"command": ["cat"], "timeoutSeconds": 5}]}]}}}
                """);

        assertThat(IndexesLoader.read(file, name -> null).warnings()).isEmpty();
    }

    @Test
    void theNearestKeyIsOneThatDiffersInCaseOrByTwoEditsAtMost() {
        Set<String> known = Set.of("include", "exclude", "maxChars");
        assertThat(UnknownKeys.nearest("Include", known)).isEqualTo("include");
        assertThat(UnknownKeys.nearest("exlcude", known)).isEqualTo("exclude");
        assertThat(UnknownKeys.nearest("max_chars", known)).isEqualTo("maxChars");
        assertThat(UnknownKeys.nearest("anything", known)).isNull();
    }
}
