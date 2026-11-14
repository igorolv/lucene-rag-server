package ru.it_spectrum.ai.rag.mcp.ingest.format;

import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.rag.mcp.collections.SplitSettings;
import ru.it_spectrum.ai.rag.mcp.ingest.Format;
import ru.it_spectrum.ai.rag.mcp.ingest.MapDefaults;
import ru.it_spectrum.ai.rag.mcp.ingest.ParsedDocument;
import ru.it_spectrum.ai.rag.mcp.ingest.Rows;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceText;
import ru.it_spectrum.ai.rag.mcp.ingest.Unit;
import ru.it_spectrum.ai.rag.mcp.ingest.ValueNames;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code tsv} format: a row format. The first non-blank line names the columns; every later
 * non-blank line is one unit whose values are {@code column.<name>}. Cells are separated by tabs
 * and are not quoted, so a quote is an ordinary character. A row with fewer cells than columns has
 * empty trailing cells; a row with more is an error.
 */
@Component
public final class TsvFormat implements Format {

    public static final String NAME = "tsv";

    /** Prefix of the unit values made of the cells of a row. */
    static final String COLUMN = "column.";

    private static final MapDefaults DEFAULTS = new MapDefaults("{document.path}", "{document.title}",
            "{document.key}#{row}", null);
    private static final ValueNames VALUES = new ValueNames("a TSV source", false, Set.of(), Set.of(),
            Set.of(Unit.ROW), Set.of(COLUMN));

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> extensions() {
        return List.of("tsv");
    }

    @Override
    public String defaultInclude() {
        return "**/*.tsv";
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
        List<String> columns = null;
        List<Unit> units = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] cells = lines[i].split("\t", -1);
            if (columns == null) {
                columns = header(cells, text.path());
                continue;
            }
            if (cells.length > columns.size()) {
                throw new IllegalArgumentException(text.path() + ", line " + (i + 1) + ": " + cells.length
                        + " cells, but the header names " + columns.size() + " columns");
            }
            RowValues values = new RowValues(COLUMN);
            for (int c = 0; c < cells.length; c++) {
                values.put(columns.get(c), cells[c]);
            }
            units.add(values.unit(units.size() + 1));
        }
        return new Rows(text.path(), RowValues.fileName(text.path()), Map.of(), Map.of(), List.copyOf(units));
    }

    private static List<String> header(String[] cells, String path) {
        List<String> columns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String cell : cells) {
            String name = cell.strip();
            if (!name.isEmpty() && !seen.add(name)) {
                throw new IllegalArgumentException(path + ": the column \"" + name + "\" is named twice");
            }
            columns.add(name);
        }
        return columns;
    }
}
