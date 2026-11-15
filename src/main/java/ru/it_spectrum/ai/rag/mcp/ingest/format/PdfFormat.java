package ru.it_spectrum.ai.rag.mcp.ingest.format;

import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.rag.mcp.collections.SplitSettings;
import ru.it_spectrum.ai.rag.mcp.ingest.Format;
import ru.it_spectrum.ai.rag.mcp.ingest.MapDefaults;
import ru.it_spectrum.ai.rag.mcp.ingest.ParsedDocument;
import ru.it_spectrum.ai.rag.mcp.ingest.Rows;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceText;
import ru.it_spectrum.ai.rag.mcp.ingest.Unit;
import ru.it_spectrum.ai.rag.mcp.ingest.ValueNames;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code pdf} format (docs/decisions.md D32): Spring AI's {@link PagePdfDocumentReader} reads
 * the file, and every page is one unit. It reads the file itself, so it takes no {@code convert}
 * steps.
 *
 * <ul>
 *   <li>Unit values: {@code {body}} the text of the page, {@code {page}} its number from 1,
 *       {@code {row}} its position among the pages that have text.</li>
 *   <li>Document values: {@code {pages}} the number of pages, {@code {pdf.title}} the title in the
 *       document information of the file, when it has one; the document title is that title or
 *       the file name.</li>
 *   <li>The reader keeps the layout of a page with spaces; runs of blanks are joined into one and
 *       empty lines into one. A page without text gives no record.</li>
 * </ul>
 */
@Component
public final class PdfFormat implements Format {

    public static final String NAME = "pdf";

    static final String PAGE = "page";
    static final String PAGES = "pages";
    static final String TITLE = "pdf.title";

    private static final Pattern BLANKS = Pattern.compile("[ \\t\\u00A0]+");
    private static final Pattern EMPTY_LINES = Pattern.compile("\\n{3,}");

    private static final MapDefaults DEFAULTS = new MapDefaults("{document.path}", "page {page}",
            "{document.key}#page-{page}", "{body}");
    private static final ValueNames VALUES = new ValueNames("a PDF source", false, Set.of(PAGES, TITLE),
            Set.of(), Set.of(Unit.BODY, Unit.ROW, PAGE), Set.of());

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> extensions() {
        return List.of("pdf");
    }

    @Override
    public String defaultInclude() {
        return "**/*.pdf";
    }

    @Override
    public MapDefaults mapDefaults() {
        return DEFAULTS;
    }

    @Override
    public ValueNames valueNames() {
        return VALUES;
    }

    @Override
    public boolean readsFile() {
        return true;
    }

    @Override
    public ParsedDocument parse(SourceText text, SplitSettings split) {
        throw new IllegalArgumentException("A PDF file is read from the file, not from text");
    }

    @Override
    public ParsedDocument parseFile(Path file, String path, SplitSettings split) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException(path + " is not a file; the pdf format reads files");
        }
        try (Reader reader = new Reader(file)) {
            List<Document> pages = reader.get();
            List<Unit> units = new ArrayList<>();
            for (Document page : pages) {
                String text = normalized(page.getText());
                if (text.isEmpty()) {
                    continue;
                }
                Object number = page.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER);
                units.add(new Unit(List.of(), false, Map.of(Unit.BODY, List.of(text),
                        Unit.ROW, List.of(String.valueOf(units.size() + 1)),
                        PAGE, List.of(String.valueOf(number)))));
            }
            Map<String, List<String>> values = new LinkedHashMap<>();
            values.put(PAGES, List.of(String.valueOf(reader.pageCount())));
            String title = reader.title();
            if (title != null) {
                values.put(TITLE, List.of(title));
            }
            return new Rows(path, title != null ? title : RowValues.fileName(path), Map.of(), Map.copyOf(values),
                    List.copyOf(units));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(path + " cannot be read as PDF: " + e.getMessage(), e);
        }
    }

    /** The text of a page with runs of blanks and of empty lines joined. */
    static String normalized(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            result.append(BLANKS.matcher(line).replaceAll(" ").strip()).append('\n');
        }
        return EMPTY_LINES.matcher(result.toString()).replaceAll("\n\n").strip();
    }

    /** The Spring AI reader, one page per document, closing the file it opens. */
    private static final class Reader extends PagePdfDocumentReader implements AutoCloseable {

        Reader(Path file) {
            super(new FileSystemResource(file), PdfDocumentReaderConfig.builder().withPagesPerDocument(1).build());
        }

        int pageCount() {
            return document.getNumberOfPages();
        }

        String title() {
            PDDocumentInformation information = document.getDocumentInformation();
            String title = information == null ? null : information.getTitle();
            return title == null || title.isBlank() ? null : title.strip();
        }

        @Override
        public void close() throws IOException {
            document.close();
        }
    }
}
