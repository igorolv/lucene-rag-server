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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code jsonl} format: a row format. Every non-blank line is a JSON object and one unit whose
 * values are {@code record.<key>}: a scalar is one value, an array of scalars is several, nested
 * objects are skipped.
 */
@Component
public final class JsonlFormat implements Format {

    public static final String NAME = "jsonl";

    /** Prefix of the unit values made of the keys of a line. */
    static final String RECORD = "record.";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final MapDefaults DEFAULTS = new MapDefaults("{document.path}", "{record.title|record.id}",
            "{document.key}#{record.id|row}", null);
    private static final ValueNames VALUES = new ValueNames("a JSONL source", false, Set.of(), Set.of(),
            Set.of(Unit.ROW), Set.of(RECORD));

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> extensions() {
        return List.of("jsonl");
    }

    @Override
    public String defaultInclude() {
        return "**/*.jsonl";
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
        List<Unit> units = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            final Object parsed;
            try {
                parsed = JSON.readValue(lines[i], Object.class);
            } catch (JacksonException e) {
                throw new IllegalArgumentException(text.path() + ", line " + (i + 1) + " is not valid JSON: "
                        + e.getOriginalMessage());
            }
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(text.path() + ", line " + (i + 1) + " is not a JSON object");
            }
            units.add(new RowValues(RECORD).putAll(map).unit(units.size() + 1));
        }
        return new Rows(text.path(), RowValues.fileName(text.path()), Map.of(), Map.of(), List.copyOf(units));
    }
}
