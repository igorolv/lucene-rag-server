package ru.it_spectrum.ai.rag.mcp.ingest.split;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.TestKnowledge;
import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.IndexesLoader;
import ru.it_spectrum.ai.rag.mcp.ingest.ChunkRecord;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceItem;
import ru.it_spectrum.ai.rag.mcp.ingest.SourcePipeline;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code split.tableRows}: the rows of Markdown tables as records, on the synthetic symptoms list. */
class TableRowsTest {

    @TempDir
    Path dir;

    private static final String SYMPTOMS = TestKnowledge.root().resolve("queue/symptoms.md").toString()
            .replace('\\', '/');

    private List<IndexDefinition> load(String source) throws IOException {
        Path file = dir.resolve("collections.json");
        Files.writeString(file, "{\"collections\": {\"queue@billing\": {\"sources\": [" + source + "]}}}");
        return StepRegistry.builtIn().checked(IndexesLoader.load(file, name -> null));
    }

    private List<ChunkRecord> records(String source) throws IOException {
        IndexDefinition definition = load(source).getFirst();
        assertThat(definition.configError()).isNull();
        SourcePipeline pipeline = StepRegistry.builtIn().pipeline(definition.sources().getFirst());
        List<ChunkRecord> records = new ArrayList<>();
        for (SourceItem item : pipeline.items()) {
            records.addAll(pipeline.records(item));
        }
        return records;
    }

    @Test
    void everyRowOfEveryTableIsARecord() throws IOException {
        List<ChunkRecord> records = records("""
                {"path": "%s", "split": {"tableRows": true},
                 "map": {"title": "{column.2}", "id": "{document.key}#{column.1}",
                         "fields": {"symptom": "{column.1}", "where": "{column.3}", "position": "{row}"}}}
                """.formatted(SYMPTOMS));

        assertThat(records).extracting(ChunkRecord::id)
                .containsExactly("symptoms.md#1", "symptoms.md#2", "symptoms.md#3", "symptoms.md#4");
        ChunkRecord first = records.getFirst();
        assertThat(first.title()).isEqualTo("Деньги пришли, а счёт остался неоплаченным");
        assertThat(first.breadcrumb()).isEqualTo("Симптомы обращений пользователей биллинга › "
                + "Сценарии диагностики (задания PLAYBOOK 1–3) › Деньги пришли, а счёт остался неоплаченным");
        assertThat(first.text()).startsWith("| № | Симптом словами пользователя |")
                .contains("|---|").endsWith("playbook.billing.payment-not-matched |")
                .doesNotContain("Симптомы записаны словами пользователя");
        assertThat(first.fields()).containsEntry("symptom", List.of("1"))
                .containsEntry("where", List.of("billing-payments, billing-invoices"))
                .containsEntry("position", List.of("1"));
        assertThat(records.get(3).fields()).containsEntry("position", List.of("1"));
        assertThat(records.get(3).breadcrumb()).contains("Остальные симптомы");
    }

    @Test
    void cellsAreNamedByHeaderWhenTheHeaderIsANameAndEscapedPipesStayInTheCell() throws IOException {
        Path file = dir.resolve("codes.md");
        Files.writeString(file, """
                # Codes

                ## Errors

                Text that is not a table is left out.

                | code | text | 3 |
                |:-----|-----:|---|
                | E1 | Bad \\| input | x |
                | E2 | Short |

                ```
                | code | text |
                |---|---|
                | E9 | in a fence |
                ```
                """);

        List<ChunkRecord> records = records("""
                {"path": "%s", "split": {"tableRows": true},
                 "map": {"title": "{column.code}", "text": "{column.text}", "fields": {"third": "{column.3}"}}}
                """.formatted(file.toString().replace('\\', '/')));

        assertThat(records).extracting(ChunkRecord::title).containsExactly("E1", "E2");
        assertThat(records).extracting(ChunkRecord::text).containsExactly("Bad | input", "Short");
        assertThat(records.getFirst().fields()).containsEntry("third", List.of("x"));
        assertThat(records.get(1).fields()).doesNotContainKey("third");
    }

    @Test
    void theSettingIsCheckedAgainstTheFormatAndTheTemplates() throws IOException {
        assertThat(load("""
                {"path": "%s", "map": {"title": "{column.2}"}}
                """.formatted(SYMPTOMS)).getFirst().configError()).contains("{column.2}");
        assertThat(load("""
                {"path": "%s", "format": "tsv", "split": {"tableRows": true}, "map": {"text": "{body}"}}
                """.formatted(SYMPTOMS)).getFirst().configError()).contains("split.tableRows is not a setting");
        assertThat(load("""
                {"path": "%s", "split": {"tableRows": "yes"}}
                """.formatted(SYMPTOMS)).getFirst().configError()).contains("split.tableRows must be true or false");
    }

    @Test
    void cellsAreSplitAtUnescapedPipes() {
        assertThat(TableRows.cells("| a | b \\| c |  |")).containsExactly("a", "b | c", "");
        assertThat(TableRows.cells("a|b")).containsExactly("a", "b");
        assertThat(TableRows.of("| a |\nno delimiter\n| b |")).isEmpty();
    }
}
