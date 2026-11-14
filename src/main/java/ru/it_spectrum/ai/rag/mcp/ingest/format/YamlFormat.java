package ru.it_spectrum.ai.rag.mcp.ingest.format;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.representer.Representer;
import ru.it_spectrum.ai.rag.mcp.collections.SplitSettings;
import ru.it_spectrum.ai.rag.mcp.ingest.Format;
import ru.it_spectrum.ai.rag.mcp.ingest.MapDefaults;
import ru.it_spectrum.ai.rag.mcp.ingest.ParsedDocument;
import ru.it_spectrum.ai.rag.mcp.ingest.Rows;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceText;
import ru.it_spectrum.ai.rag.mcp.ingest.Unit;
import ru.it_spectrum.ai.rag.mcp.ingest.ValueNames;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code yaml} format: a row format whose top level is a mapping (docs/decisions.md D22).
 *
 * <ul>
 *   <li>The top-level keys with a scalar or a list of scalars are the document values
 *       {@code yaml.<key>}.</li>
 *   <li>Without {@code split.list} the file is one unit, and {@code {body}} is the file as it
 *       is.</li>
 *   <li>With {@code split.list} every item of the top-level list of that key is a unit: the keys of
 *       an item that is a mapping are {@code item.<key>}, an item that is a scalar is
 *       {@code {item}}, and {@code {body}} is the item written back as YAML.</li>
 * </ul>
 *
 * Nested structures are not values; they stay readable in {@code {body}}.
 */
@Component
public final class YamlFormat implements Format {

    public static final String NAME = "yaml";

    /** Prefix of the document values made of the top-level keys. */
    static final String YAML = "yaml.";
    /** A list item that is a scalar. */
    static final String ITEM = "item";
    /** Prefix of the unit values made of the keys of a list item. */
    static final String ITEM_KEY = "item.";

    private static final MapDefaults DEFAULTS = new MapDefaults("{document.path}", "{document.title}",
            "{document.key}#{row}", null);
    private static final ValueNames VALUES = new ValueNames("a YAML source", false, Set.of(), Set.of(YAML),
            Set.of(Unit.ROW, Unit.BODY, ITEM), Set.of(ITEM_KEY));

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> extensions() {
        return List.of("yaml", "yml");
    }

    @Override
    public String defaultInclude() {
        return "**/*.yaml";
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
        // split.list is this format's setting; the heading settings do not apply to it
    }

    @Override
    public ParsedDocument parse(SourceText text, SplitSettings split) {
        String content = String.join("\n", RowValues.lines(text.text()));
        final Object parsed;
        try {
            parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
        } catch (YAMLException e) {
            throw new IllegalArgumentException(text.path() + " is not valid YAML: " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> top)) {
            throw new IllegalArgumentException(text.path() + ": the top level must be a mapping of keys");
        }
        Map<String, List<String>> document = new RowValues(YAML).putAll(top).values();
        List<Unit> units = new ArrayList<>();
        if (split.list() == null) {
            units.add(new RowValues(ITEM_KEY).putAs(Unit.BODY, content.strip()).unit(1));
        } else {
            Object list = top.get(split.list());
            if (list != null && !(list instanceof List<?>)) {
                throw new IllegalArgumentException(text.path() + ": \"" + split.list() + "\" is not a list");
            }
            if (list == null && !top.containsKey(split.list())) {
                throw new IllegalArgumentException(text.path() + " has no key \"" + split.list()
                        + "\" named by split.list");
            }
            for (Object item : list == null ? List.of() : (List<?>) list) {
                RowValues values = new RowValues(ITEM_KEY);
                if (item instanceof Map<?, ?> map) {
                    values.putAll(map);
                } else {
                    values.putAs(ITEM, item);
                }
                values.putAs(Unit.BODY, dump(item));
                units.add(values.unit(units.size() + 1));
            }
        }
        return new Rows(text.path(), RowValues.fileName(text.path()), Map.of(), document, List.copyOf(units));
    }

    private static String dump(Object item) {
        if (!(item instanceof Map<?, ?>) && !(item instanceof List<?>)) {
            return item == null ? "" : String.valueOf(item);
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        options.setWidth(Integer.MAX_VALUE);
        return new Yaml(new Representer(options), options).dump(item).strip();
    }
}
