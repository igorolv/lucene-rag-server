package ru.it_spectrum.ai.rag.mcp.ingest;

import ru.it_spectrum.ai.rag.mcp.collections.CollectionDefinition;
import ru.it_spectrum.ai.rag.mcp.collections.ConvertStep;
import ru.it_spectrum.ai.rag.mcp.collections.SourceDefinition;
import ru.it_spectrum.ai.rag.mcp.ingest.convert.CommandConverter;
import ru.it_spectrum.ai.rag.mcp.ingest.exec.CommandRunner;
import ru.it_spectrum.ai.rag.mcp.ingest.format.JsonlFormat;
import ru.it_spectrum.ai.rag.mcp.ingest.format.MarkdownFormat;
import ru.it_spectrum.ai.rag.mcp.ingest.format.PdfFormat;
import ru.it_spectrum.ai.rag.mcp.ingest.format.SqlFormat;
import ru.it_spectrum.ai.rag.mcp.ingest.format.TsvFormat;
import ru.it_spectrum.ai.rag.mcp.ingest.format.YamlFormat;
import ru.it_spectrum.ai.rag.mcp.ingest.map.TemplateMapper;
import ru.it_spectrum.ai.rag.mcp.ingest.origin.CommandOrigin;
import ru.it_spectrum.ai.rag.mcp.ingest.origin.FilesOrigin;
import ru.it_spectrum.ai.rag.mcp.ingest.split.HeadingSplitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The step implementations this server has, by name: the one place that knows which origins,
 * converters and formats exist (docs/decisions.md D15). Nothing else names an implementation.
 *
 * <p>In the application the registry is a bean that receives every step bean (D19); a jar that
 * adds a step bean adds a format or a converter (D16). {@link #builtIn()} gives the same set
 * without a context, for unit tests.
 */
public final class StepRegistry {

    private final Map<String, Origin> origins;
    private final Map<String, Converter> converters;
    private final Map<String, Format> formats;
    private final Splitter splitter;
    private final Mapper mapper;

    /** @throws IllegalStateException when two implementations of a step share a name */
    public StepRegistry(List<Origin> origins, List<Converter> converters, List<Format> formats,
                        Splitter splitter, Mapper mapper) {
        this.origins = byName("origin", origins, Origin::name);
        this.converters = byName("converter", converters, Converter::name);
        this.formats = byName("format", formats, Format::name);
        this.splitter = splitter;
        this.mapper = mapper;
    }

    /** The steps that ship with the server. Keep in step with the step beans. */
    public static StepRegistry builtIn() {
        CommandRunner runner = new CommandRunner();
        return new StepRegistry(List.of(new FilesOrigin(), new CommandOrigin(runner)),
                List.of(new CommandConverter(runner)),
                List.of(new MarkdownFormat(), new TsvFormat(), new YamlFormat(), new JsonlFormat(),
                        new SqlFormat(), new PdfFormat()),
                new HeadingSplitter(), new TemplateMapper());
    }

    /** Names of the origins a source may have. */
    public Set<String> origins() {
        return origins.keySet();
    }

    /** Names of the converters a source may name. */
    public Set<String> converters() {
        return converters.keySet();
    }

    /** Names of the formats a source may name. */
    public Set<String> formats() {
        return formats.keySet();
    }

    /**
     * The steps of one source, checked against each other.
     *
     * @throws IllegalArgumentException when the source names a step that does not exist or a
     *                                  setting that does not fit its steps
     */
    public SourcePipeline pipeline(SourceDefinition source) {
        Origin origin = origin(source);
        return SourcePipeline.of(source, origin, conversions(source, origin), format(source), splitter, mapper);
    }

    /** The {@code convert} steps of a source, each resolved by the key that names its converter. */
    private List<Converter.Conversion> conversions(SourceDefinition source, Origin origin) {
        List<Converter.Conversion> result = new ArrayList<>();
        for (int i = 0; i < source.convert().size(); i++) {
            ConvertStep step = source.convert().get(i);
            String where = "convert[" + i + "]";
            List<Converter> named = step.settings().keySet().stream().map(converters::get)
                    .filter(Objects::nonNull).toList();
            if (named.size() != 1) {
                throw new IllegalArgumentException(where + " must name exactly one converter by a key; supported: "
                        + String.join(", ", converters.keySet()));
            }
            final Converter.Conversion conversion;
            try {
                conversion = named.getFirst().prepare(step);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ": " + e.getMessage(), e);
            }
            if (conversion.readsFile() && !FilesOrigin.NAME.equals(origin.name())) {
                throw new IllegalArgumentException(where + " reads the file of an item, and a source with a "
                        + origin.name() + " has no files");
            }
            result.add(conversion);
        }
        return List.copyOf(result);
    }

    /**
     * The definitions with every source checked against its steps: a collection whose source
     * does not fit becomes a broken entry, as a wrong entry of the collections file does.
     */
    public List<CollectionDefinition> checked(List<CollectionDefinition> definitions) {
        List<CollectionDefinition> result = new ArrayList<>();
        for (CollectionDefinition definition : definitions) {
            result.add(definition.usable() ? checked(definition) : definition);
        }
        return List.copyOf(result);
    }

    private CollectionDefinition checked(CollectionDefinition definition) {
        for (int i = 0; i < definition.sources().size(); i++) {
            try {
                pipeline(definition.sources().get(i));
            } catch (IllegalArgumentException e) {
                return definition.withError("collections." + definition.name() + ".sources[" + i + "]: "
                        + e.getMessage());
            }
        }
        return definition;
    }

    private Origin origin(SourceDefinition source) {
        String name = source.command() != null ? CommandOrigin.NAME : FilesOrigin.NAME;
        Origin origin = origins.get(name);
        if (origin == null) {
            throw new IllegalArgumentException("the origin \"" + name + "\" is not supported");
        }
        return origin;
    }

    /** The format a source names; without a name the extension of its files decides, then Markdown. */
    private Format format(SourceDefinition source) {
        if (source.format() != null) {
            Format named = formats.get(source.format());
            if (named == null) {
                throw new IllegalArgumentException("format \"" + source.format() + "\" is not supported; supported: "
                        + String.join(", ", formats.keySet()));
            }
            return named;
        }
        String extension = extension(source.include() != null ? source.include()
                : source.path() != null ? source.path().toString() : "");
        for (Format format : formats.values()) {
            if (format.extensions().contains(extension)) {
                return format;
            }
        }
        return formats.get(MarkdownFormat.NAME);
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || name.indexOf('/', dot) >= 0 || name.indexOf('\\', dot) >= 0 ? ""
                : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static <T> Map<String, T> byName(String step, List<T> implementations, Function<T, String> name) {
        Map<String, T> result = new LinkedHashMap<>();
        for (T implementation : implementations) {
            if (result.putIfAbsent(name.apply(implementation), implementation) != null) {
                throw new IllegalStateException("Two implementations of the " + step + " step are named \""
                        + name.apply(implementation) + "\"");
            }
        }
        return Map.copyOf(result);
    }
}
