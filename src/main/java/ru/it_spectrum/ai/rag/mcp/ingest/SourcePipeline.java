package ru.it_spectrum.ai.rag.mcp.ingest;

import ru.it_spectrum.ai.rag.mcp.collections.SourceDefinition;

import java.io.IOException;
import java.util.List;

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

    private SourcePipeline(SourceDefinition source, Origin origin, List<Converter.Conversion> converters,
                           Format format,
                           Splitter splitter, Mapper mapper) {
        this.source = source;
        this.origin = origin;
        this.converters = converters;
        this.format = format;
        this.splitter = splitter;
        this.mapper = mapper;
    }

    /** @throws IllegalArgumentException when a setting of the source does not fit its steps */
    static SourcePipeline of(SourceDefinition source, Origin origin, List<Converter.Conversion> converters,
                             Format format,
                             Splitter splitter, Mapper mapper) {
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
        mapper.check(source.map(), source.split(), format);
        return new SourcePipeline(source, origin, List.copyOf(converters), format, splitter, mapper);
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
        ParsedDocument document = format.readsFile()
                ? format.parseFile(item.file(), item.path(), source.split())
                : format.parse(text(item), source.split());
        List<Unit> units = switch (document) {
            case SectionTree tree -> splitter.split(tree, source.split());
            case Rows rows -> rows.units();
        };
        List<ChunkRecord> records = mapper.map(document, units, source.map(), format.mapDefaults(),
                source.split().maxChars());
        return records.stream().filter(record -> source.where().matches(record.fields())).toList();
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
