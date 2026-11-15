package ru.it_spectrum.ai.rag.mcp.ingest;

import ru.it_spectrum.ai.rag.mcp.collections.SourceDefinition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The five steps of one source, resolved and checked: origin, converters, format, splitter,
 * mapper (docs/plan.md section 3.1). The indexer asks it for items and for the records of an
 * item and names no step.
 */
public final class SourcePipeline {

    private final SourceDefinition source;
    private final Origin origin;
    private final List<Converter.Conversion> converters;
    private final Format format;
    private final Splitter splitter;
    private final Mapper mapper;
    private final List<Lookup> lookups;
    private List<JoinTable> tables;

    /**
     * A lookup of the source: what the mapper checks of it and the steps that read its units.
     */
    record Lookup(JoinSpec spec, SourcePipeline pipeline) {
    }

    /** One item parsed and cut. */
    private record Parsed(ParsedDocument document, List<Unit> units) {
    }

    private SourcePipeline(SourceDefinition source, Origin origin, List<Converter.Conversion> converters,
                           Format format,
                           Splitter splitter, Mapper mapper, List<Lookup> lookups) {
        this.source = source;
        this.origin = origin;
        this.converters = converters;
        this.format = format;
        this.splitter = splitter;
        this.mapper = mapper;
        this.lookups = lookups;
    }

    /** @throws IllegalArgumentException when a setting of the source does not fit its steps */
    static SourcePipeline of(SourceDefinition source, Origin origin, List<Converter.Conversion> converters,
                             Format format,
                             Splitter splitter, Mapper mapper, List<Lookup> lookups) {
        checkSteps(source, converters, format);
        mapper.check(source.map(), source.split(), format, lookups.stream().map(Lookup::spec).toList());
        return new SourcePipeline(source, origin, List.copyOf(converters), format, splitter, mapper,
                List.copyOf(lookups));
    }

    /**
     * The steps of a lookup: it is read up to its units and has no {@code map}.
     *
     * @throws IllegalArgumentException when a setting of the lookup does not fit its steps
     */
    static SourcePipeline lookup(SourceDefinition source, Origin origin, List<Converter.Conversion> converters,
                                 Format format, Splitter splitter, Mapper mapper) {
        checkSteps(source, converters, format);
        return new SourcePipeline(source, origin, List.copyOf(converters), format, splitter, mapper, List.of());
    }

    private static void checkSteps(SourceDefinition source, List<Converter.Conversion> converters, Format format) {
        format.check(source.split());
        if (source.split().tableRows() && !format.valueNames().tree()) {
            throw new IllegalArgumentException("split.tableRows is not a setting of " + format.valueNames().label()
                    + "; it cuts the tables of a Markdown source");
        }
        if (format.readsFile() && !converters.isEmpty()) {
            throw new IllegalArgumentException("format " + format.name()
                    + " reads the file of an item itself and takes no convert steps");
        }
        if (format.readsFile() && source.command() != null) {
            throw new IllegalArgumentException("format " + format.name()
                    + " reads the file of an item, and a source with a command has no files");
        }
    }

    /** The format step, for the check of a source this pipeline is a lookup of. */
    Format formatStep() {
        return format;
    }

    /** Name of the format each lookup of the source is read with, by the name of the lookup. */
    public Map<String, String> lookupFormats() {
        Map<String, String> formats = new LinkedHashMap<>();
        for (Lookup lookup : lookups) {
            formats.put(lookup.spec().name(), lookup.pipeline().format());
        }
        return formats;
    }

    /**
     * Whether the records of an item are sections of one document, which search shows a limited
     * number of per page; otherwise every record (a row) stands alone.
     */
    public boolean sections() {
        return format.valueNames().tree() && !source.split().tableRows() || source.split().outline();
    }

    /** Name of the format the source is read with. */
    public String format() {
        return format.name();
    }

    /** The items of the source in a stable order. */
    public List<SourceItem> items() throws IOException {
        return origin.items(source, format.defaultInclude());
    }

    /**
     * The records of one item.
     *
     * @throws IOException              when the item cannot be read or converted
     * @throws IllegalArgumentException when the item does not fit the format or the mapping
     */
    public List<ChunkRecord> records(SourceItem item) throws IOException {
        List<JoinTable> joins = tables();
        Parsed parsed = parsed(item);
        List<ChunkRecord> records = mapper.map(parsed.document(), parsed.units(), source.map(),
                format.mapDefaults(), source.split().maxChars(), joins);
        return records.stream().filter(record -> source.where().matches(record.fields())).toList();
    }

    private Parsed parsed(SourceItem item) throws IOException {
        ParsedDocument document = format.readsFile()
                ? format.parseFile(item.file(), item.path(), source.split())
                : format.parse(text(item), source.split());
        List<Unit> units = switch (document) {
            case SectionTree tree -> splitter.split(tree, source.split());
            case Rows rows -> rows.units();
        };
        return new Parsed(document, units);
    }

    /**
     * The lookups of the source, each read whole on first use: every unit of every item under
     * its key.
     *
     * @throws IOException              when a lookup cannot be read
     * @throws IllegalArgumentException when an item does not fit the format of its lookup, or two
     *                                  units of a lookup have one key
     */
    private synchronized List<JoinTable> tables() throws IOException {
        if (tables == null) {
            List<JoinTable> read = new ArrayList<>();
            for (Lookup lookup : lookups) {
                JoinSpec spec = lookup.spec();
                JoinTable table = new JoinTable(spec.name(), spec.on());
                for (SourceItem item : lookup.pipeline().items()) {
                    try {
                        Parsed parsed = lookup.pipeline().parsed(item);
                        for (Unit unit : parsed.units()) {
                            Map<String, List<String>> values = new HashMap<>(parsed.document().values());
                            values.putAll(unit.values());
                            table.put(mapper.key(spec.key(), values), values);
                        }
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("join." + spec.name() + ": " + item.location() + ": "
                                + e.getMessage(), e);
                    }
                }
                read.add(table);
            }
            tables = List.copyOf(read);
        }
        return tables;
    }

    /** The text of an item after the {@code convert} steps. */
    private SourceText text(SourceItem item) throws IOException {
        SourceText text = null;
        for (Converter.Conversion converter : converters) {
            if (text == null && !converter.readsFile()) {
                text = item.read();
            }
            text = converter.convert(item, text);
        }
        return text == null ? item.read() : text;
    }
}
