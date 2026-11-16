package ru.it_spectrum.ai.rag.mcp.ingest.format;

import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.IndexesLoader;
import ru.it_spectrum.ai.rag.mcp.ingest.ChunkRecord;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceItem;
import ru.it_spectrum.ai.rag.mcp.ingest.SourcePipeline;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code html} and {@code office} formats (docs/decisions.md D74). Pages and Office files are
 * written by the test, so no binary file is kept in the repository.
 */
class HtmlAndOfficeFormatTest {

    static final String PAGE = """
            <!doctype html>
            <html><head><title>Billing help</title>
              <meta name="description" content="How payments and refunds work">
              <meta name="keywords" content="payments, refunds"></head>
            <body>
              <nav>Home | Contacts</nav>
              <section><h2>Payments</h2><p>A payment is matched to an invoice by its number.</p></section>
              <section><h2>Refunds</h2><p>A refund is issued for a matched payment.</p></section>
              <section></section>
            </body></html>
            """;

    @TempDir
    Path dir;

    private List<IndexDefinition> load(String source) throws IOException {
        Path file = dir.resolve("indexes.json");
        Files.writeString(file, "{\"indexes\": {\"help@billing\": {\"sources\": [" + source + "]}}}");
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

    private void write(String name, String text) throws IOException {
        Path file = dir.resolve("site").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void aPageIsOneRecordOfItsBodyText() throws IOException {
        write("help.html", PAGE);

        List<ChunkRecord> records = records("{\"path\": \"site\"," + "\"include\": \"**/*.html\"}");

        assertThat(records).hasSize(1);
        ChunkRecord page = records.getFirst();
        assertThat(page.id()).isEqualTo("help.html#1");
        assertThat(page.title()).isEqualTo("Billing help");
        assertThat(page.text()).contains("Home | Contacts", "matched to an invoice", "issued for a matched payment");
    }

    @Test
    void aSelectorMakesARecordOfEveryElementItMatches() throws IOException {
        write("help.htm", PAGE);

        List<ChunkRecord> records = records("""
                {"path": "site", "format": "html", "split": {"selector": "section"},
                 "map": {"title": "{html.title}, part {row}",
                         "fields": {"about": "{html.description}", "keywords": "{html.keywords}"}}}
                """);

        assertThat(records).extracting(ChunkRecord::title)
                .containsExactly("Billing help, part 1", "Billing help, part 2");
        assertThat(records.getFirst().text()).isEqualTo("Payments A payment is matched to an invoice by its number.");
        assertThat(records.getFirst().fields()).containsEntry("about", List.of("How payments and refunds work"));
        assertThat(records).noneMatch(r -> r.text().contains("Contacts"));
    }

    @Test
    void htmlMayComeFromACommand() throws IOException {
        // The format reads text, so the output of a command can be HTML.
        Path page = dir.resolve("page.html");
        Files.writeString(page, PAGE);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString().replace('\\', '/');
        Path script = dir.resolve("Cat.java");
        Files.writeString(script, """
                public class Cat { public static void main(String[] a) throws Exception {
                  System.out.print(java.nio.file.Files.readString(java.nio.file.Path.of(a[0]))); } }
                """);

        List<ChunkRecord> records = records("""
                {"command": ["%s", "%s", "%s"], "format": "html", "split": {"selector": "section h2"}}
                """.formatted(java, script.toString().replace('\\', '/'), page.toString().replace('\\', '/')));

        assertThat(records).extracting(ChunkRecord::text).containsExactly("Payments", "Refunds");
    }

    @Test
    void aWordFileIsOneRecordOfItsText() throws IOException {
        Path file = dir.resolve("office").resolve("refunds.docx");
        Files.createDirectories(file.getParent());
        try (XWPFDocument document = new XWPFDocument(); OutputStream out = Files.newOutputStream(file)) {
            document.createParagraph().createRun().setText("Refunds");
            document.createParagraph().createRun().setText("A refund is issued for a matched payment.");
            document.write(out);
        }

        List<ChunkRecord> records = records("{\"path\": \"office\", \"format\": \"office\"}");

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().id()).isEqualTo("refunds.docx");
        assertThat(records.getFirst().title()).isEqualTo("refunds");
        assertThat(records.getFirst().text()).contains("Refunds", "A refund is issued for a matched payment.");
    }

    @Test
    void aSheetGivesItsCellsByRow() throws IOException {
        Path file = dir.resolve("office").resolve("statuses.xlsx");
        Files.createDirectories(file.getParent());
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            XSSFSheet sheet = workbook.createSheet("Statuses");
            String[][] rows = {{"Code", "Meaning"}, {"CREATED", "the payment is registered"},
                    {"MATCHED", "the payment is matched to an invoice"}};
            for (int r = 0; r < rows.length; r++) {
                for (int c = 0; c < rows[r].length; c++) {
                    sheet.createRow(r);
                }
            }
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.getRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            workbook.write(out);
        }

        List<ChunkRecord> records = records("{\"path\": \"office\"}".replace("}", ", \"include\": \"**/*.xlsx\"}"));

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().text()).contains("Statuses", "MATCHED", "the payment is matched to an invoice");
    }

    @Test
    void officeFilesAreReadFromFilesOnlyAndADamagedOneIsReported() throws IOException {
        assertThat(load("""
                {"path": "office", "format": "office", "convert": [{"command": ["java", "-version"]}]}
                """).getFirst().configError()).contains("takes no convert steps");
        assertThat(load("""
                {"path": "site", "format": "html", "split": {"outline": true}}
                """).getFirst().configError()).contains("split.outline is not a setting of an HTML source");
        assertThat(load("""
                {"path": "office", "format": "office", "split": {"selector": "p"}}
                """).getFirst().configError()).contains("split.selector is not a setting of an Office source");

        // A Word file cut in half, as an interrupted copy leaves it.
        Path broken = dir.resolve("broken").resolve("notes.docx");
        Files.createDirectories(broken.getParent());
        java.io.ByteArrayOutputStream whole = new java.io.ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Notes ".repeat(2000));
            document.write(whole);
        }
        byte[] bytes = whole.toByteArray();
        Files.write(broken, java.util.Arrays.copyOf(bytes, bytes.length / 2));
        assertThatThrownBy(() -> records("{\"path\": \"broken\", \"format\": \"office\"}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("notes.docx");
    }
}
