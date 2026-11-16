package ru.it_spectrum.ai.rag.mcp.ingest.format;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.jsoup.JsoupDocumentReader;
import org.springframework.ai.reader.jsoup.config.JsoupDocumentReaderConfig;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.rag.mcp.definition.SplitSettings;
import ru.it_spectrum.ai.rag.mcp.ingest.Format;
import ru.it_spectrum.ai.rag.mcp.ingest.MapDefaults;
import ru.it_spectrum.ai.rag.mcp.ingest.ParsedDocument;
import ru.it_spectrum.ai.rag.mcp.ingest.Rows;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceText;
import ru.it_spectrum.ai.rag.mcp.ingest.Unit;
import ru.it_spectrum.ai.rag.mcp.ingest.ValueNames;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code html} format (docs/decisions.md D32, D74): Spring AI's {@link JsoupDocumentReader}
 * reads the text of an HTML page. It reads text, so a {@code command} origin and {@code convert}
 * steps may come before it.
 *
 * <ul>
 *   <li>Without {@code split.selector} the body of the page is one unit; with it, every element
 *       the CSS selector matches is one, in document order.</li>
 *   <li>Unit values: {@code {body}} the text of the element, {@code {row}} its position among the
 *       units that have text.</li>
 *   <li>Document values: {@code {html.title}} the {@code <title>} of the page,
 *       {@code {html.description}} and {@code {html.keywords}} its meta tags, when it has them;
 *       the document title is the page title or the file name.</li>
 *   <li>The reader gives the text of an element as one line: headings, paragraphs and lists are
 *       not kept. For the structure of a page, convert it to Markdown first (D17).</li>
 * </ul>
 */
@Component
public final class HtmlFormat implements Format {

    public static final String NAME = "html";

    static final String TITLE = "html.title";
    static final String DESCRIPTION = "html.description";
    static final String KEYWORDS = "html.keywords";

    private static final MapDefaults DEFAULTS = new MapDefaults("{document.path}", "{document.title}",
            "{document.key}#{row}", "{body}");
    private static final ValueNames VALUES = new ValueNames("an HTML source", false,
            Set.of(TITLE, DESCRIPTION, KEYWORDS), Set.of(), Set.of(Unit.BODY, Unit.ROW), Set.of());

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> extensions() {
        return List.of("html", "htm");
    }

    @Override
    public String defaultInclude() {
        return "**/*.{html,htm}";
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
    public void check(SplitSettings split) {
        if (split.list() != null) {
            throw new IllegalArgumentException("split.list is not a setting of " + VALUES.label());
        }
        if (split.outline()) {
            throw new IllegalArgumentException("split.outline is not a setting of " + VALUES.label()
                    + "; it cuts a PDF file by its outline");
        }
    }

    @Override
    public ParsedDocument parse(SourceText text, SplitSettings split) {
        JsoupDocumentReaderConfig.Builder config = JsoupDocumentReaderConfig.builder()
                .charset(StandardCharsets.UTF_8.name())
                .metadataTags(List.of("description", "keywords"));
        config = split.selector() == null ? config.allElements(true)
                : config.selector(split.selector()).groupByElement(true);
        final List<Document> read;
        try {
            read = new JsoupDocumentReader(new ByteArrayResource(text.text().getBytes(StandardCharsets.UTF_8)),
                    config.build()).get();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(text.path() + " cannot be read as HTML: " + e.getMessage(), e);
        }
        List<Unit> units = new ArrayList<>();
        Map<String, List<String>> values = new LinkedHashMap<>();
        if (!read.isEmpty()) {
            // Every element carries the metadata of the page.
            Map<String, Object> page = read.getFirst().getMetadata();
            put(values, TITLE, page.get("title"));
            put(values, DESCRIPTION, page.get("description"));
            put(values, KEYWORDS, page.get("keywords"));
        }
        for (Document element : read) {
            String body = element.getText() == null ? "" : element.getText().strip();
            if (!body.isEmpty()) {
                units.add(new Unit(List.of(), false, Map.of(Unit.BODY, List.of(body),
                        Unit.ROW, List.of(String.valueOf(units.size() + 1)))));
            }
        }
        String title = values.containsKey(TITLE) ? values.get(TITLE).getFirst() : RowValues.fileName(text.path());
        return new Rows(text.path(), title, Map.of(), Map.copyOf(values), List.copyOf(units));
    }

    private static void put(Map<String, List<String>> values, String name, Object value) {
        String text = value == null ? "" : String.valueOf(value).strip();
        if (!text.isEmpty()) {
            values.put(name, List.of(text));
        }
    }
}
