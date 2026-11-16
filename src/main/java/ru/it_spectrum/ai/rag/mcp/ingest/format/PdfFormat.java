package ru.it_spectrum.ai.rag.mcp.ingest.format;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.rag.mcp.definition.SplitSettings;
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
 * The {@code pdf} format (docs/decisions.md D32, D35, D74): Spring AI's PDF readers read the file.
 * It reads the file itself, so it takes no {@code convert} steps.
 *
 * <ul>
 *   <li>By default every page is one unit, read by {@link PagePdfDocumentReader}; its heading is
 *       {@code page <n>}.</li>
 *   <li>With {@code split.outline} every entry of the file's outline (its bookmarks) is one unit,
 *       read by {@link ParagraphPdfDocumentReader}: the text from that entry to the next. Its
 *       heading is the entry's title and its parents the titles of the entries above it, so the
 *       breadcrumb follows the outline. A file without an outline is read by pages.</li>
 *   <li>Unit values: {@code {body}} the text, {@code {heading}}, {@code {parents}}, {@code {page}}
 *       the number of the (first) page from 1, {@code {row}} the position among the units that
 *       have text.</li>
 *   <li>Document values: {@code {pages}} the number of pages, {@code {pdf.title}} the title in the
 *       document information of the file, when it has one; the document title is that title or
 *       the file name.</li>
 *   <li>The readers keep the layout of a page with spaces; runs of blanks are joined into one and
 *       empty lines into one. A unit without text gives no record.</li>
 * </ul>
 */
@Component
public final class PdfFormat implements Format {

    public static final String NAME = "pdf";

    static final String PAGE = "page";
    static final String PAGES = "pages";
    static final String TITLE = "pdf.title";

    private static final Logger log = LoggerFactory.getLogger(PdfFormat.class);

    private static final Pattern BLANKS = Pattern.compile("[ \\t\\u00A0]+");
    private static final Pattern EMPTY_LINES = Pattern.compile("\\n{3,}");

    /** A page's heading is {@code page <n>}, so these give the ids pages always had. */
    private static final MapDefaults DEFAULTS = new MapDefaults("{document.path}", "{heading}",
            "{document.key}#{slug}", "{body}");
    private static final ValueNames VALUES = new ValueNames("a PDF source", false, Set.of(PAGES, TITLE),
            Set.of(), Set.of(Unit.BODY, Unit.ROW, Unit.HEADING, Unit.PARENTS, PAGE), Set.of());

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
    public void check(SplitSettings split) {
        if (split.list() != null) {
            throw new IllegalArgumentException("split.list is not a setting of " + VALUES.label());
        }
        if (split.selector() != null) {
            throw new IllegalArgumentException("split.selector is not a setting of " + VALUES.label()
                    + "; it chooses the elements of an HTML file");
        }
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
        try {
            List<Unit> units = split.outline() ? outline(file, path) : null;
            try (PageReader reader = new PageReader(file)) {
                if (units == null) {
                    units = pages(reader.get());
                }
                Map<String, List<String>> values = new LinkedHashMap<>();
                values.put(PAGES, List.of(String.valueOf(reader.pageCount())));
                String title = reader.title();
                if (title != null) {
                    values.put(TITLE, List.of(title));
                }
                return new Rows(path, title != null ? title : RowValues.fileName(path), Map.of(),
                        Map.copyOf(values), List.copyOf(units));
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(path + " cannot be read as PDF: " + e.getMessage(), e);
        }
    }

    private static List<Unit> pages(List<Document> pages) {
        List<Unit> units = new ArrayList<>();
        for (Document page : pages) {
            String text = normalized(page.getText());
            if (text.isEmpty()) {
                continue;
            }
            String number = String.valueOf(page.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER));
            units.add(unit(List.of(), "page " + number, number, text, units.size() + 1));
        }
        return units;
    }

    /**
     * The units of the outline entries, or {@code null} when the file has no outline. Spring AI's
     * reader gives each entry its title and level; the titles of the entries above it in reading
     * order are its parents.
     */
    private static List<Unit> outline(Path file, String path) throws IOException {
        final List<Document> entries;
        try (OutlineReader reader = new OutlineReader(file)) {
            entries = reader.get();
        } catch (IllegalArgumentException e) {
            log.info("{} has no outline; it is read by pages", path);
            return null;
        }
        List<Unit> units = new ArrayList<>();
        List<String> open = new ArrayList<>();
        for (Document entry : entries) {
            String title = String.valueOf(entry.getMetadata().getOrDefault("title", "")).strip();
            int level = entry.getMetadata().get("level") instanceof Number n ? Math.max(0, n.intValue()) : 0;
            while (open.size() > level) {
                open.removeLast();
            }
            List<String> parents = List.copyOf(open);
            while (open.size() < level) {
                open.add("");
            }
            open.add(title);
            String text = normalized(entry.getText());
            if (text.isEmpty()) {
                continue;
            }
            Object page = entry.getMetadata().get("page_number");
            units.add(unit(parents.stream().filter(p -> !p.isEmpty()).toList(), title,
                    String.valueOf(page), text, units.size() + 1));
        }
        return units;
    }

    private static Unit unit(List<String> parents, String heading, String page, String text, int row) {
        return new Unit(parents, false, Map.of(Unit.BODY, List.of(text), Unit.ROW, List.of(String.valueOf(row)),
                Unit.HEADING, List.of(heading),
                Unit.PARENTS, List.of(String.join(ru.it_spectrum.ai.rag.mcp.ingest.ChunkRecord.BREADCRUMB_SEPARATOR,
                        parents)),
                PAGE, List.of(page)));
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

    /** Spring AI's page reader, one page per document, closing the file it opens. */
    private static final class PageReader extends PagePdfDocumentReader implements AutoCloseable {

        PageReader(Path file) {
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

    /**
     * Spring AI's outline reader, closing the file it opens. It throws
     * {@link IllegalArgumentException} for a file without an outline.
     */
    private static final class OutlineReader extends ParagraphPdfDocumentReader implements AutoCloseable {

        OutlineReader(Path file) {
            super(new FileSystemResource(file), PdfDocumentReaderConfig.defaultConfig());
        }

        @Override
        public void close() throws IOException {
            PDDocument opened = document;
            opened.close();
        }
    }
}
