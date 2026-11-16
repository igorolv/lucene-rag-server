package ru.it_spectrum.ai.rag.mcp.ingest.format;

import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.rag.mcp.definition.SplitSettings;
import ru.it_spectrum.ai.rag.mcp.ingest.Format;
import ru.it_spectrum.ai.rag.mcp.ingest.MapDefaults;
import ru.it_spectrum.ai.rag.mcp.ingest.ParsedDocument;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceText;
import ru.it_spectrum.ai.rag.mcp.ingest.ValueNames;

import java.util.List;
import java.util.Set;

/**
 * The {@code markdown} format: a tree format read by {@link MarkdownReader}. Front matter fields
 * are the document fields and, as {@code frontmatter.<key>}, its document values.
 */
@Component
public final class MarkdownFormat implements Format {

    public static final String NAME = "markdown";

    /** Prefix of the document values made of front matter fields. */
    static final String FRONT_MATTER = "frontmatter.";

    private static final MapDefaults DEFAULTS = new MapDefaults("{frontmatter.id|document.path}", "{heading}",
            "{document.key}#{slug}", "{body}");
    private static final ValueNames VALUES = new ValueNames("a Markdown source", true, Set.of(),
            Set.of(FRONT_MATTER), Set.of(), Set.of());

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> extensions() {
        return List.of("md", "markdown");
    }

    @Override
    public String defaultInclude() {
        return "**/*.md";
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
        return MarkdownReader.read(text.path(), text.text());
    }
}
