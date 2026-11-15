package ru.it_spectrum.ai.rag.mcp.ingest.format;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.collections.CollectionDefinition;
import ru.it_spectrum.ai.rag.mcp.collections.CollectionsLoader;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.index.CollectionIndexer;
import ru.it_spectrum.ai.rag.mcp.index.CollectionStore;
import ru.it_spectrum.ai.rag.mcp.ingest.ChunkRecord;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceItem;
import ru.it_spectrum.ai.rag.mcp.ingest.SourcePipeline;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;
import ru.it_spectrum.ai.rag.mcp.search.SearchService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PDF files are made by PDFBox in the test, so no binary file is kept in the repository. */
class PdfFormatTest {

    @TempDir
    Path dir;

    /** Writes a PDF with one page per text; an empty text is a page without text. */
    private Path pdf(String name, String title, String... pages) throws IOException {
        Path file = dir.resolve("manuals").resolve(name);
        Files.createDirectories(file.getParent());
        try (PDDocument document = new PDDocument()) {
            if (title != null) {
                PDDocumentInformation information = new PDDocumentInformation();
                information.setTitle(title);
                document.setDocumentInformation(information);
            }
            for (String text : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (text.isEmpty()) {
                    continue;
                }
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 700);
                    for (String line : text.split("\n")) {
                        content.showText(line);
                        content.newLineAtOffset(0, -16);
                    }
                    content.endText();
                }
            }
            document.save(file.toFile());
        }
        return file;
    }

    private List<CollectionDefinition> load(String source) throws IOException {
        Path file = dir.resolve("collections.json");
        Files.writeString(file, "{\"collections\": {\"manuals@billing\": {\"sources\": [" + source + "]}}}");
        return StepRegistry.builtIn().checked(CollectionsLoader.load(file, name -> null));
    }

    private List<ChunkRecord> records(String source) throws IOException {
        CollectionDefinition definition = load(source).getFirst();
        assertThat(definition.configError()).isNull();
        SourcePipeline pipeline = StepRegistry.builtIn().pipeline(definition.sources().getFirst());
        List<ChunkRecord> records = new ArrayList<>();
        for (SourceItem item : pipeline.items()) {
            records.addAll(pipeline.records(item));
        }
        return records;
    }

    @Test
    void everyPageWithTextIsARecord() throws IOException {
        pdf("refunds.pdf", "Billing operator manual",
                "Refunds\nA refund is issued for a matched payment.",
                "",
                "Refund statuses\nCreated,     Sent,     Done.");

        List<ChunkRecord> records = records("{\"path\": \"manuals\", \"include\": \"**/*.pdf\"}");

        assertThat(records).extracting(ChunkRecord::id).containsExactly("refunds.pdf#page-1", "refunds.pdf#page-3");
        ChunkRecord first = records.getFirst();
        assertThat(first.title()).isEqualTo("page 1");
        assertThat(first.breadcrumb()).isEqualTo("Billing operator manual › page 1");
        assertThat(first.text()).contains("A refund is issued for a matched payment.");
        assertThat(first.source()).isEqualTo("refunds.pdf");
        assertThat(records.get(1).text()).contains("Created, Sent, Done.").doesNotContain("  ");
    }

    @Test
    void valuesOfThePageAndTheFileReachTheTemplates() throws IOException {
        pdf("guide.pdf", null, "First page", "Second page");

        List<ChunkRecord> records = records("""
                {"path": "manuals", "format": "pdf",
                 "map": {"title": "{path.name}, p. {page} of {pages}", "fields": {"page": "{page}", "kind": "manual"}}}
                """);

        assertThat(records).extracting(ChunkRecord::title).containsExactly("guide, p. 1 of 2", "guide, p. 2 of 2");
        assertThat(records.getFirst().breadcrumb()).startsWith("guide");
        assertThat(records.get(1).fields()).containsEntry("page", List.of("2")).containsEntry("kind", List.of("manual"));
    }

    @Test
    void theFormatReadsFilesOnly() throws IOException {
        assertThat(load("""
                {"path": "manuals", "format": "pdf", "convert": [{"command": ["java", "-version"]}]}
                """).getFirst().configError()).contains("takes no convert steps");
        assertThat(load("""
                {"command": ["java", "-version"], "format": "pdf"}
                """).getFirst().configError()).contains("has no files");
        assertThat(load("""
                {"path": "manuals", "format": "pdf", "map": {"title": "{chapter}"}}
                """).getFirst().configError()).contains("chapter");
    }

    @Test
    void aDamagedFileIsReportedWithItsPath() throws IOException {
        Files.createDirectories(dir.resolve("manuals"));
        Files.writeString(dir.resolve("manuals").resolve("broken.pdf"), "not a PDF");

        assertThatThrownBy(() -> records("{\"path\": \"manuals\", \"format\": \"pdf\"}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("broken.pdf");
    }

    @Test
    void aPdfCollectionIsBuiltAndSearched() throws IOException {
        pdf("refunds.pdf", "Billing operator manual",
                "Refunds\nA refund is issued for a matched payment.",
                "Webhooks\nDeliveries are retried with backoff.");
        Path dataDir = dir.resolve("data");
        CollectionDefinition definition = load("{\"path\": \"manuals\", \"include\": \"**/*.pdf\"}").getFirst();

        CollectionIndexer.build(definition, StepRegistry.builtIn(), dataDir, "test");
        var properties = new RagMcpProperties(dataDir.toString(), null, null, null);
        var page = new SearchService(new CollectionStore(dataDir), properties)
                .search(List.of("manuals@billing"), "retrying deliveries", null, null, null);

        assertThat(page.hits()).isNotEmpty();
        assertThat(page.hits().getFirst().id()).isEqualTo("refunds.pdf#page-2");
    }

    @Test
    void blanksAndEmptyLinesAreJoined() {
        assertThat(PdfFormat.normalized("  a   b \r\n\r\n\r\n\r\n  c  d  ")).isEqualTo("a b\n\nc d");
        assertThat(PdfFormat.normalized(null)).isEmpty();
    }
}
