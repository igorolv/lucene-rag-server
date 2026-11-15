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
import ru.it_spectrum.ai.rag.mcp.ingest.Unit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowFormatsTest {

    private static Rows parse(ru.it_spectrum.ai.rag.mcp.ingest.Format format, String path) throws IOException {
        Path file = TestKnowledge.root().resolve(path);
        return (Rows) format.parse(new SourceText(path, Files.readString(file)), SplitSettings.DEFAULT);
    }

    private static List<ChunkRecord> records(String path, String format, MapSettings map) throws IOException {
        return records(path, format, SplitSettings.DEFAULT, map);
    }

    private static List<ChunkRecord> records(String path, String format, SplitSettings split, MapSettings map)
            throws IOException {
        SourceDefinition source = new SourceDefinition(TestKnowledge.root().resolve(path), null, null, format,
                split, map);
        SourcePipeline pipeline = StepRegistry.builtIn().pipeline(source);
        List<ChunkRecord> records = new ArrayList<>();
        for (SourceItem item : pipeline.items()) {
            records.addAll(pipeline.records(item));
        }
        return records;
    }

    private static MapSettings map(String title, String text, Map<String, String> fields) {
        return new MapSettings(null, null, null, title, null, text, fields, null);
    }

    @Test
    void aTsvRowIsAUnitOfItsColumns() throws IOException {
        Rows rows = parse(new TsvFormat(), "catalogs/messages/messages-billing.tsv");

        assertThat(rows.title()).isEqualTo("messages-billing");
        assertThat(rows.units()).hasSize(5);
        Unit first = rows.units().getFirst();
        assertThat(first.ancestors()).isEmpty();
        assertThat(first.intro()).isFalse();
        assertThat(first.values()).containsEntry("column.kind", List.of("error"))
                .containsEntry("column.method", List.of("checkAmount"))
                .containsEntry("row", List.of("1"));
        assertThat(rows.units().get(3).value("column.text")).as("a quote is an ordinary character")
                .isEqualTo("Возврат по платежу в статусе \"Отменён\" невозможен");
        Unit shortRow = rows.units().get(4);
        assertThat(shortRow.value("column.text")).isEqualTo("Выписка банка загружена");
        assertThat(shortRow.values()).as("missing trailing cells are empty")
                .doesNotContainKeys("column.file", "column.line", "column.method");
    }

    @Test
    void emptyCellsGiveNoValue() throws IOException {
        Rows rows = parse(new TsvFormat(), "catalogs/menu/menu.tsv");

        assertThat(rows.units()).hasSize(4);
        assertThat(rows.units().getFirst().values()).doesNotContainKey("column.pid")
                .containsEntry("column.name", List.of("Биллинг"));
        assertThat(rows.units().get(3).values()).doesNotContainKey("column.help");
    }

    @Test
    void aTsvRowWithMoreCellsThanColumnsOrARepeatedColumnIsAnError() {
        TsvFormat format = new TsvFormat();
        assertThatThrownBy(() -> format.parse(new SourceText("a.tsv", "a\tb\n1\t2\t3\n"), SplitSettings.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a.tsv, line 2", "3 cells");
        assertThatThrownBy(() -> format.parse(new SourceText("a.tsv", "a\ta\n1\t2\n"), SplitSettings.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("\"a\" is named twice");
    }

    @Test
    void aTsvFileWithCarriageReturnsAndABomReadsTheSame() {
        Rows rows = (Rows) new TsvFormat().parse(new SourceText("a.tsv", "﻿code\tname\r\n1\tOne\r\n\r\n"), SplitSettings.DEFAULT);

        assertThat(rows.units()).hasSize(1);
        assertThat(rows.units().getFirst().values()).containsEntry("column.code", List.of("1"))
                .containsEntry("column.name", List.of("One"));
    }

    @Test
    void aJsonlLineIsAUnitOfItsKeys() throws IOException {
        Rows rows = parse(new JsonlFormat(), "eval/questions.jsonl");

        assertThat(rows.units()).as("blank lines are skipped").hasSize(3);
        Unit second = rows.units().get(1);
        assertThat(second.value("record.id")).isEqualTo("q-0002");
        assertThat(second.values().get("record.expected"))
                .containsExactly("article.billing.refunds", "data:PAYMENT");
        assertThat(second.value("row")).isEqualTo("2");
        assertThat(rows.units().get(2).values()).as("nested objects are skipped").doesNotContainKey("record.meta");
    }

    @Test
    void aJsonlLineThatIsNotAnObjectIsAnError() {
        JsonlFormat format = new JsonlFormat();
        assertThatThrownBy(() -> format.parse(new SourceText("q.jsonl", "{\"id\": 1}\n{broken\n"), SplitSettings.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("q.jsonl, line 2", "not valid JSON");
        assertThatThrownBy(() -> format.parse(new SourceText("q.jsonl", "[1, 2]\n"), SplitSettings.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("line 1 is not a JSON object");
    }

    @Test
    void tsvRowsBecomeRecordsByTheMapBlock() throws IOException {
        List<ChunkRecord> records = records("catalogs/messages", "tsv",
                map("{column.text}", "{column.text}\nКласс: {column.file}, метод {column.method}",
                        Map.of("kind", "{column.kind}", "catalog", "messages")));

        assertThat(records).hasSize(5);
        ChunkRecord first = records.getFirst();
        assertThat(first.id()).isEqualTo("messages-billing.tsv#1");
        assertThat(first.document()).isEqualTo("messages-billing.tsv");
        assertThat(first.title()).isEqualTo("Сумма платежа должна быть больше нуля");
        assertThat(first.breadcrumb()).isEqualTo("messages-billing › Сумма платежа должна быть больше нуля");
        assertThat(first.text()).contains("billing/src/payments/PaymentValidator.java", "checkAmount");
        assertThat(first.fields()).containsEntry("kind", List.of("error"))
                .containsEntry("catalog", List.of("messages"));
        assertThat(records.get(4).fields()).containsEntry("kind", List.of("info"));
    }

    @Test
    void jsonlLinesUseTheirIdByDefault() throws IOException {
        List<ChunkRecord> records = records("eval", "jsonl",
                map("{record.question}", "{record.question}\n{record.answer}",
                        Map.of("expected", "{record.expected}")));

        assertThat(records).extracting(ChunkRecord::id)
                .containsExactly("questions.jsonl#q-0001", "questions.jsonl#q-0002", "questions.jsonl#q-0003");
        assertThat(records.get(1).fields().get("expected")).hasSize(2);
    }

    @Test
    void aRowFormatHasNoBodyOrHeading() {
        StepRegistry registry = StepRegistry.builtIn();
        Path path = TestKnowledge.root().resolve("eval");

        assertThatThrownBy(() -> registry.pipeline(new SourceDefinition(path, null, null, "jsonl",
                SplitSettings.DEFAULT, map(null, "{body}", null))))
                .hasMessageContaining("{body} is not a value of a JSONL source");
        assertThatThrownBy(() -> registry.pipeline(new SourceDefinition(path, null, null, "tsv",
                SplitSettings.DEFAULT, map("{heading}", "{column.text}", null))))
                .hasMessageContaining("{heading} is not a value of a TSV source");
        assertThatThrownBy(() -> registry.pipeline(new SourceDefinition(path, null, null, "tsv",
                SplitSettings.DEFAULT, map(null, "{record.text}", null))))
                .hasMessageContaining("{record.text} is not a value of a TSV source");
    }

    private static SplitSettings list(String key) {
        return new SplitSettings(2, null, 2500, 0, key);
    }

    @Test
    void aYamlFileIsOneUnitWithItsTopLevelScalarsAsDocumentValues() throws IOException {
        Rows rows = parse(new YamlFormat(), "data/tables/PAYMENT.yaml");

        assertThat(rows.values()).containsEntry("yaml.table", List.of("PAYMENT"))
                .containsEntry("yaml.terms", List.of("платёж", "оплата", "выписка"))
                .containsEntry("yaml.key", List.of("PAYMENT_ID"))
                .doesNotContainKeys("yaml.columns", "yaml.relations", "yaml.scope", "yaml.pii");
        assertThat(rows.fields()).isEmpty();
        assertThat(rows.units()).hasSize(1);
        assertThat(rows.units().getFirst().text()).startsWith("table: PAYMENT")
                .contains("PAYMENT_PURPOSE:", "meaning: \"Назначение платежа из выписки банка\"");
    }

    @Test
    void withSplitListEveryItemOfThatListIsAUnit() throws IOException {
        Path file = TestKnowledge.root().resolve("data/enums/PaymentStatusEnum.yaml");
        Rows rows = (Rows) new YamlFormat().parse(new SourceText("PaymentStatusEnum.yaml", Files.readString(file)),
                list("values"));

        assertThat(rows.values()).containsEntry("yaml.enum", List.of("PaymentStatusEnum"))
                .doesNotContainKey("yaml.values");
        assertThat(rows.units()).hasSize(4);
        Unit second = rows.units().get(1);
        assertThat(second.values()).containsEntry("item.code", List.of("1"))
                .containsEntry("item.constant", List.of("UNRECOGNIZED"))
                .containsEntry("item.label", List.of("не распознан"))
                .containsEntry("row", List.of("2"));
        assertThat(second.text()).isEqualTo("code: '1'\nconstant: UNRECOGNIZED\nlabel: не распознан");
    }

    @Test
    void aListOfScalarsGivesItemAndAMissingListIsAnError() {
        YamlFormat format = new YamlFormat();
        Rows rows = (Rows) format.parse(new SourceText("a.yaml", "name: x\nterms: [one, two]\n"), list("terms"));
        assertThat(rows.units()).extracting(u -> u.value("item")).containsExactly("one", "two");

        assertThatThrownBy(() -> format.parse(new SourceText("a.yaml", "name: x\n"), list("values")))
                .hasMessageContaining("a.yaml has no key \"values\"");
        assertThatThrownBy(() -> format.parse(new SourceText("a.yaml", "values: x\n"), list("values")))
                .hasMessageContaining("\"values\" is not a list");
        assertThatThrownBy(() -> format.parse(new SourceText("a.yaml", "- a\n- b\n"), SplitSettings.DEFAULT))
                .hasMessageContaining("the top level must be a mapping");
        assertThatThrownBy(() -> format.parse(new SourceText("a.yaml", "a: [\n"), SplitSettings.DEFAULT))
                .hasMessageContaining("not valid YAML");
    }

    @Test
    void yamlItemsBecomeRecordsWithTheValuesOfTheirFile() throws IOException {
        List<ChunkRecord> records = records("data/enums", "yaml", list("values"),
                new MapSettings(null, "{yaml.enum}", "{document.key}#{item.code}", "{item.code} {item.label}",
                        null, "{yaml.enum} {item.code} — {item.label} ({item.constant}): {yaml.meaning}",
                        Map.of("enum", "{yaml.enum}"), null));

        assertThat(records).hasSize(6);
        ChunkRecord matched = records.get(2);
        assertThat(matched.id()).isEqualTo("PaymentStatusEnum#2");
        assertThat(matched.breadcrumb()).isEqualTo("PaymentStatusEnum › 2 привязан к счёту");
        assertThat(matched.text()).startsWith("PaymentStatusEnum 2 — привязан к счёту (MATCHED): статус");
        assertThat(matched.fields()).containsEntry("enum", List.of("PaymentStatusEnum"));
    }

    @Test
    void wholeYamlFilesAndListsInOneDirectory() throws IOException {
        List<ChunkRecord> tables = records("data/tables", "yaml",
                map("{yaml.business_name}", "{body}", Map.of("table", "{yaml.table}")));
        assertThat(tables).hasSize(1);
        assertThat(tables.getFirst().id()).isEqualTo("PAYMENT.yaml#1");
        assertThat(tables.getFirst().title()).isEqualTo("Входящий платёж");

        List<ChunkRecord> logs = records("data/logs.yaml", null, list("sources"),
                map("{item.name}", "{body}", Map.of("tables", "{item.tables}")));
        assertThat(logs).extracting(ChunkRecord::title)
                .containsExactly("История статусов платежа", "Загрузки банковских выписок");
        assertThat(logs.get(1).fields().get("tables")).containsExactly("STATEMENT_IMPORT", "STATEMENT_IMPORT_ERROR");
    }

    @Test
    void splitListBelongsToYamlOnly() {
        Path path = TestKnowledge.root().resolve("catalogs");
        assertThatThrownBy(() -> StepRegistry.builtIn().pipeline(new SourceDefinition(path, null, null, "tsv",
                list("rows"), map(null, "{row}", null))))
                .hasMessageContaining("split.list is not a setting of a TSV source");
        assertThatThrownBy(() -> StepRegistry.builtIn().pipeline(new SourceDefinition(path, null, null, null,
                list("rows"), MapSettings.DEFAULT)))
                .hasMessageContaining("split.list is not a setting of a Markdown source");
    }
}
