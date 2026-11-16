package ru.it_spectrum.ai.rag.mcp.ingest.format;

import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.rag.mcp.definition.SplitSettings;
import ru.it_spectrum.ai.rag.mcp.ingest.Format;
import ru.it_spectrum.ai.rag.mcp.ingest.MapDefaults;
import ru.it_spectrum.ai.rag.mcp.ingest.ParsedDocument;
import ru.it_spectrum.ai.rag.mcp.ingest.Rows;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceText;
import ru.it_spectrum.ai.rag.mcp.ingest.Unit;
import ru.it_spectrum.ai.rag.mcp.ingest.ValueNames;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code sql} format (docs/decisions.md D21): one file is one unit, and the statement is not
 * parsed.
 *
 * <ul>
 *   <li>The header is the block of {@code --} comment lines at the top of the file. A header line
 *       {@code -- key: value} gives the document value {@code header.<key>}; a key repeated on
 *       several lines gives several values.</li>
 *   <li>{@code {comment}} is the header without the dashes, {@code {sql}} is the text after it,
 *       {@code {body}} is the file as it is.</li>
 * </ul>
 */
@Component
public final class SqlFormat implements Format {

    public static final String NAME = "sql";

    /** Prefix of the document values made of the {@code key: value} lines of the header. */
    static final String HEADER = "header.";
    static final String COMMENT = "comment";
    static final String SQL = "sql";

    private static final Pattern COMMENT_LINE = Pattern.compile("^\\s*--");
    private static final Pattern KEY_LINE = Pattern.compile("^([A-Za-z0-9_.-]+):(.*)$");

    private static final MapDefaults DEFAULTS = new MapDefaults("{header.id|document.path}",
            "{header.purpose|path.name}", "{document.key}", "{body}");
    private static final ValueNames VALUES = new ValueNames("an SQL source", false, Set.of(COMMENT, SQL),
            Set.of(HEADER), Set.of(Unit.BODY), Set.of());

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> extensions() {
        return List.of("sql");
    }

    @Override
    public String defaultInclude() {
        return "**/*.sql";
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
    public ParsedDocument parse(SourceText text, SplitSettings split) {
        String[] lines = RowValues.lines(text.text());
        int start = 0;
        while (start < lines.length && lines[start].isBlank()) {
            start++;
        }
        int end = start;
        Map<String, List<String>> header = new LinkedHashMap<>();
        List<String> comment = new ArrayList<>();
        for (; end < lines.length; end++) {
            Matcher dashes = COMMENT_LINE.matcher(lines[end]);
            if (!dashes.find()) {
                break;
            }
            String line = lines[end].substring(dashes.end());
            line = line.startsWith(" ") ? line.substring(1) : line;
            comment.add(line.stripTrailing());
            Matcher key = KEY_LINE.matcher(line.strip());
            if (key.matches() && !key.group(2).isBlank()) {
                header.computeIfAbsent(HEADER + key.group(1), k -> new ArrayList<>()).add(key.group(2).strip());
            }
        }
        Map<String, List<String>> values = new LinkedHashMap<>();
        header.forEach((name, found) -> values.put(name, List.copyOf(found)));
        put(values, COMMENT, String.join("\n", comment).strip());
        put(values, SQL, String.join("\n", List.of(lines).subList(end, lines.length)).strip());
        String body = String.join("\n", lines).strip();
        Unit unit = new Unit(List.of(), false, body.isEmpty() ? Map.of() : Map.of(Unit.BODY, List.of(body)));
        return new Rows(text.path(), RowValues.fileName(text.path()), Map.of(), Map.copyOf(values), List.of(unit));
    }

    private static void put(Map<String, List<String>> values, String name, String value) {
        if (!value.isEmpty()) {
            values.put(name, List.of(value));
        }
    }
}
