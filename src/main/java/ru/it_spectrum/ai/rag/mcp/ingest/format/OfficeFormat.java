package ru.it_spectrum.ai.rag.mcp.ingest.format;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code office} format (docs/decisions.md D32, D74): Spring AI's {@link TikaDocumentReader}
 * reads the text of a Word, Excel, PowerPoint, OpenDocument or RTF file, with the Office and
 * ODF/RTF parsers of Apache Tika only. It reads the file itself, so it takes no {@code convert}
 * steps.
 *
 * <ul>
 *   <li>A file is one unit: {@code {body}} its text, {@code {row}} 1. A long text is cut by
 *       {@code split.maxChars} as any text is.</li>
 *   <li>The document title is the file name.</li>
 *   <li>Tika gives the text in reading order, the cells of a sheet separated by tabs and its rows
 *       by line ends; headings and styles are not kept. For the structure of a document, convert
 *       it to Markdown first (D17).</li>
 * </ul>
 */
@Component
public final class OfficeFormat implements Format {

    public static final String NAME = "office";

    private static final MapDefaults DEFAULTS = new MapDefaults("{document.path}", "{document.title}",
            "{document.key}", "{body}");
    private static final ValueNames VALUES = new ValueNames("an Office source", false, Set.of(), Set.of(),
            Set.of(Unit.BODY, Unit.ROW), Set.of());

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> extensions() {
        return List.of("docx", "doc", "xlsx", "xls", "pptx", "ppt", "odt", "ods", "odp", "rtf");
    }

    @Override
    public String defaultInclude() {
        return "**/*.{docx,doc,xlsx,xls,pptx,ppt,odt,ods,odp,rtf}";
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
        throw new IllegalArgumentException("An Office file is read from the file, not from text");
    }

    @Override
    public ParsedDocument parseFile(Path file, String path, SplitSettings split) {
        if (file == null) {
            throw new IllegalArgumentException(path + " is not a file; the office format reads files");
        }
        final List<Document> read;
        try {
            read = new TikaDocumentReader(new FileSystemResource(file)).get();
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalArgumentException(path + " cannot be read as an Office file: " + cause.getMessage(), e);
        }
        String body = read.isEmpty() ? "" : PdfFormat.normalized(read.getFirst().getText());
        List<Unit> units = body.isEmpty() ? List.of()
                : List.of(new Unit(List.of(), false, Map.of(Unit.BODY, List.of(body), Unit.ROW, List.of("1"))));
        return new Rows(path, RowValues.fileName(path), Map.of(), Map.of(), units);
    }
}
