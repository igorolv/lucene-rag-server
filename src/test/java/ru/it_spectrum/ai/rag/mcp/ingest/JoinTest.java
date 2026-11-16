package ru.it_spectrum.ai.rag.mcp.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.IndexesLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A source that takes values from the units of another file by a key ({@code join}). */
class JoinTest {

    private static final String SPLIT = """
            "split": {"level": "h3", "headingPattern": "^Requirement: (?<name>.+)$"}""";

    @TempDir
    Path dir;

    @BeforeEach
    void writeSources() throws IOException {
        write("specs/billing-payments/spec.md", """
                # billing-payments Specification
                ## Requirements
                ### Requirement: Columns of the table
                The table SHALL show the amount and the status.
                """);
        write("specs/billing-process-matching/spec.md", """
                # billing-process-matching Specification
                ## Requirements
                ### Requirement: Matching by amount
                The system SHALL match a payment to an invoice by amount.
                """);
        write("specs/billing-drafts/spec.md", """
                # billing-drafts Specification
                ## Requirements
                ### Requirement: Saving a draft
                The system SHALL save a draft.
                """);
        write("catalog.md", """
                # Capabilities

                ## Screens

                | Capability | SF | Menu | Help |
                |---|---|---|---|
                | `Billing-Payments` | 2010 | Payments → Incoming payments | payments.html |

                ## Processes

                | Capability | Where | Covers |
                |---|---|---|
                | `billing-process-matching` | `core/matching` | matching payments to invoices |
                """);
        write("menu.tsv", "capability\tmenu\nbilling-payments\tPayments → Incoming payments\n");
    }

    private void write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private IndexDefinition load(String source) throws IOException {
        Path file = dir.resolve("indexes.json");
        Files.writeString(file, "{\"indexes\": {\"c\": {\"sources\": [" + source + "]}}}");
        return StepRegistry.builtIn().checked(IndexesLoader.load(file, name -> null)).getFirst();
    }

    private List<ChunkRecord> records(String source) throws IOException {
        IndexDefinition definition = load(source);
        assertThat(definition.configError()).isNull();
        SourcePipeline pipeline = StepRegistry.builtIn().pipeline(definition.sources().getFirst());
        List<ChunkRecord> records = new ArrayList<>();
        for (SourceItem item : pipeline.items()) {
            records.addAll(pipeline.records(item));
        }
        return records;
    }

    private String errorOf(String source) throws IOException {
        IndexDefinition definition = load(source);
        assertThat(definition.usable()).isFalse();
        return definition.configError();
    }

    private static String specs(String join, String map) {
        return "{\"path\": \"specs\", \"include\": \"**/spec.md\", " + SPLIT + ", \"join\": " + join
                + ", \"map\": " + map + "}";
    }

    private static final String CATALOG = """
            {"cap": {"path": "catalog.md", "split": {"tableRows": true}, "key": "{column.1}",
                     "on": "`{path[0]}`"}}""";

    @Test
    void theRowOfATableInAnotherFileGivesTheBreadcrumbAndFields() throws IOException {
        List<ChunkRecord> records = records(specs(CATALOG, """
                {"document": "{path[0]}", "title": "{heading.name}",
                 "breadcrumb": ["{cap.column.3|path[0]}", "{title}"],
                 "fields": {"sf": "{cap.column.SF}", "help": "{cap.column.Help}", "area": "{cap.heading}"}}"""));

        assertThat(records).extracting(ChunkRecord::breadcrumb).containsExactly(
                // No row of the catalog names this capability: the alternative of the template is used.
                "billing-drafts › Saving a draft",
                // The key is compared without regard to case.
                "Payments → Incoming payments › Columns of the table",
                // A table with other columns: the third one is what it is there.
                "matching payments to invoices › Matching by amount");
        assertThat(records.get(0).fields()).isEmpty();
        assertThat(records.get(1).fields()).containsEntry("sf", List.of("2010"))
                .containsEntry("help", List.of("payments.html")).containsEntry("area", List.of("Screens"));
        assertThat(records.get(2).fields()).containsOnlyKeys("area");
    }

    @Test
    void aLookupMayBeOfARowFormat() throws IOException {
        List<ChunkRecord> records = records(specs("""
                {"menu": {"path": "menu.tsv", "key": "{column.capability}", "on": "{path[0]}"}}""", """
                {"title": "{heading.name}", "breadcrumb": ["{menu.column.menu}", "{title}"]}"""));

        assertThat(records).extracting(ChunkRecord::breadcrumb).contains(
                "Payments → Incoming payments › Columns of the table", "Saving a draft");
    }

    @Test
    void aReaderMayHoldTheJoinOfSeveralSources() throws IOException {
        Path file = dir.resolve("indexes.json");
        Files.writeString(file, """
                {"readers": {"catalog": {"join": %s}},
                 "indexes": {"c": {"sources": [
                   {"path": "specs", "include": "**/spec.md", "reader": "catalog", %s,
                    "map": {"title": "{heading.name}", "breadcrumb": ["{cap.column.3|path[0]}", "{title}"]}}]}}}"""
                .formatted(CATALOG, SPLIT));
        IndexDefinition definition = StepRegistry.builtIn().checked(IndexesLoader.load(file, n -> null))
                .getFirst();
        assertThat(definition.configError()).isNull();
        SourcePipeline pipeline = StepRegistry.builtIn().pipeline(definition.sources().getFirst());
        List<ChunkRecord> records = new ArrayList<>();
        for (SourceItem item : pipeline.items()) {
            records.addAll(pipeline.records(item));
        }

        assertThat(records).extracting(ChunkRecord::breadcrumb)
                .contains("Payments → Incoming payments › Columns of the table");
    }

    @Test
    void twoUnitsOfALookupWithOneKeyFailTheBuild() throws IOException {
        write("catalog.md", """
                | Capability | Menu |
                |---|---|
                | `billing-payments` | Payments |
                | `billing-payments` | Payments again |
                """);
        IndexDefinition definition = load(specs(CATALOG, "{\"title\": \"{cap.column.2}\"}"));
        SourcePipeline pipeline = StepRegistry.builtIn().pipeline(definition.sources().getFirst());

        assertThatThrownBy(() -> pipeline.records(pipeline.items().getFirst()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("join.cap").hasMessageContaining("catalog.md")
                .hasMessageContaining("two units have the key \"`billing-payments`\"");
    }

    @Test
    void mistakesInAJoinAreConfigurationErrors() throws IOException {
        assertThat(errorOf(specs(CATALOG, "{\"title\": \"{cap.nope}\"}")))
                .contains("map.title", "{cap.nope}", "not a value of the lookup \"cap\"");
        assertThat(errorOf(specs(CATALOG, "{\"document\": \"{cap.column.1}\"}")))
                .contains("map.document", "not available here");
        assertThat(errorOf(specs(CATALOG.replace("`{path[0]}`", "{title}"), "{}")))
                .contains("join.cap.on", "{title}", "not available here");
        assertThat(errorOf(specs(CATALOG.replace("{column.1}", "{path}"), "{}")))
                .contains("join.cap.key", "{path}", "not a value of a unit");
        assertThat(errorOf(specs(CATALOG.replace("\"cap\"", "\"path\""), "{}")))
                .contains("join.path", "taken by the values");
        assertThat(errorOf(specs(CATALOG.replace("\"cap\"", "\"my-cap\""), "{}")))
                .contains("join.my-cap", "letters and digits");
        assertThat(errorOf(specs(CATALOG.replace("\"key\": \"{column.1}\",", ""), "{}")))
                .contains("join.cap", "\"key\" and \"on\" are required");
        assertThat(errorOf(specs(CATALOG.replace("\"split\"", "\"map\": {}, \"split\""), "{}")))
                .contains("join.cap", "takes no \"map\"");
        assertThat(errorOf(specs(CATALOG.replace("\"split\"", "\"format\": \"docbook\", \"split\""), "{}")))
                .contains("join.cap", "docbook", "not supported");
        assertThat(errorOf(specs("[]", "{}"))).contains("\"join\" must be a JSON object");
    }
}
