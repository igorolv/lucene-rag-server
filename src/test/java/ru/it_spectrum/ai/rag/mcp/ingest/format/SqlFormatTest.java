package ru.it_spectrum.ai.rag.mcp.ingest.format;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.rag.mcp.TestKnowledge;
import ru.it_spectrum.ai.rag.mcp.collections.MapSettings;
import ru.it_spectrum.ai.rag.mcp.collections.SourceDefinition;
import ru.it_spectrum.ai.rag.mcp.collections.SplitSettings;
import ru.it_spectrum.ai.rag.mcp.ingest.ChunkRecord;
import ru.it_spectrum.ai.rag.mcp.ingest.Rows;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceItem;
import ru.it_spectrum.ai.rag.mcp.ingest.SourcePipeline;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceText;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlFormatTest {

    private static Rows parse(String text) {
        return (Rows) new SqlFormat().parse(new SourceText("q/a.sql", text), SplitSettings.DEFAULT);
    }

    private static List<ChunkRecord> records(String path, String exclude, MapSettings map) throws IOException {
        SourceDefinition source = new SourceDefinition(TestKnowledge.root().resolve(path), null, exclude, "sql",
                SplitSettings.DEFAULT, map);
        SourcePipeline pipeline = StepRegistry.builtIn().pipeline(source);
        List<ChunkRecord> records = new ArrayList<>();
        for (SourceItem item : pipeline.items()) {
            records.addAll(pipeline.records(item));
        }
        return records;
    }

    @Test
    void theHeaderLinesAreValuesAndTheStatementIsTheRest() throws IOException {
        String file = Files.readString(TestKnowledge.root().resolve("queries/payments/payment-status-history.sql"));
        Rows rows = (Rows) new SqlFormat().parse(new SourceText("payments/payment-status-history.sql", file),
                SplitSettings.DEFAULT);

        assertThat(rows.values()).containsEntry("header.id", List.of("payments.payment-status-history"))
                .containsEntry("header.tables", List.of("PAYMENT, PAYMENT_STATUS_HIST, USERS"))
                .containsEntry("header.params", List.of(":payment_id — ID платежа (экран «Платежи»)"));
        assertThat(rows.values().get("comment").getFirst())
                .startsWith("id: payments.payment-status-history\npurpose: история статусов платежа")
                .endsWith("verified: billing@test, 01.11.2026");
        assertThat(rows.values().get("sql").getFirst()).startsWith("SELECT h.psh_time").endsWith("ORDER BY h.psh_time");
        assertThat(rows.units()).hasSize(1);
        assertThat(rows.units().getFirst().text()).startsWith("-- id: payments").endsWith("ORDER BY h.psh_time");
        assertThat(rows.fields()).isEmpty();
    }

    @Test
    void aFreeCommentHasNoHeaderValues() {
        Rows rows = parse("""

                -- Catalog of messages: catalogs/messages.tsv
                --Read only
                --   indented: kept
                SELECT 1 FROM dual
                -- a trailing comment is part of the statement
                """);

        assertThat(rows.values()).containsOnlyKeys("comment", "sql", "header.indented");
        assertThat(rows.values().get("comment"))
                .containsExactly("Catalog of messages: catalogs/messages.tsv\nRead only\n  indented: kept");
        assertThat(rows.values().get("sql"))
                .containsExactly("SELECT 1 FROM dual\n-- a trailing comment is part of the statement");
    }

    @Test
    void aRepeatedKeyGivesSeveralValuesAndAnEmptyValueNone() {
        Rows rows = parse("-- tables: A\n-- tables: B\n-- pii:\nSELECT 1\n");

        assertThat(rows.values()).containsEntry("header.tables", List.of("A", "B")).doesNotContainKey("header.pii");
    }

    @Test
    void aFileWithoutHeaderOrStatementStillParses() {
        assertThat(parse("SELECT 1\n").values()).containsOnlyKeys("sql");
        assertThat(parse("-- id: x\n").values()).containsOnlyKeys("header.id", "comment");
        assertThat(parse("").units().getFirst().values()).isEmpty();
    }

    @Test
    void byDefaultAQueryIsFoundByItsIdAndPurpose() throws IOException {
        List<ChunkRecord> records = records("queries", null, MapSettings.DEFAULT);

        assertThat(records).extracting(ChunkRecord::id)
                .containsExactly("common.statement-import-errors", "payments.payment-status-history");
        ChunkRecord history = records.get(1);
        assertThat(history.document()).isEqualTo("payments.payment-status-history");
        assertThat(history.title()).startsWith("история статусов платежа");
        assertThat(history.breadcrumb()).startsWith("payment-status-history › история статусов платежа");
        assertThat(history.text()).startsWith("-- id: payments.payment-status-history").contains("FROM payment_status_hist");
    }

    @Test
    void theMapBlockMayUseTheHeaderTheCommentAndTheStatement() throws IOException {
        List<ChunkRecord> records = records("queries", null, new MapSettings(null, null, null, null,
                List.of("{path[0]}", "{title}"), "{comment}\n\n{sql}",
                Map.of("tables", "{header.tables}", "area", "{path[0]}"), null));

        ChunkRecord history = records.get(1);
        assertThat(history.breadcrumb()).startsWith("payments › история");
        assertThat(history.fields()).containsEntry("area", List.of("payments"))
                .containsEntry("tables", List.of("PAYMENT, PAYMENT_STATUS_HIST, USERS"));
        assertThat(history.text()).startsWith("id: payments.payment-status-history").doesNotContain("-- ");
    }

    @Test
    void catalogGeneratorsAreReadWithoutHeaderValuesOrExcluded() throws IOException {
        List<ChunkRecord> generators = records("catalogs", null, MapSettings.DEFAULT);
        assertThat(generators).extracting(ChunkRecord::id).containsExactly("messages/messages.sql");
        assertThat(generators.getFirst().title()).isEqualTo("messages");

        assertThat(records("catalogs", "**/messages.sql", MapSettings.DEFAULT)).isEmpty();
    }

    @Test
    void unknownValuesOfAnSqlSourceAreRefused() {
        SourceDefinition source = new SourceDefinition(TestKnowledge.root().resolve("queries"), null, null, "sql",
                SplitSettings.DEFAULT, new MapSettings(null, "{body}", null, null, null, null, null, null));

        assertThatThrownBy(() -> StepRegistry.builtIn().pipeline(source))
                .hasMessageContaining("map.document: {body} is not available here");
    }
}
