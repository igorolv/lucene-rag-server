package ru.it_spectrum.ai.rag.mcp.tools;

import io.modelcontextprotocol.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult.CompleteCompletion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpComplete;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.rag.mcp.index.IndexStore;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;
import ru.it_spectrum.ai.rag.mcp.search.SearchHit;
import ru.it_spectrum.ai.rag.mcp.search.SearchService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A record of a collection as an MCP resource, {@value #URI}, and completion of the collection
 * and the id in that template (docs/decisions.md D48). The resource gives what {@code getDocument}
 * gives, as Markdown a client can show or attach without a tool call.
 *
 * <p>MCP completes only arguments of prompts and variables of resource templates, not tool
 * arguments; this template is what completion of collection names and ids attaches to. A client
 * percent-encodes the values it puts into the template, since ids hold {@code /} and {@code #};
 * they are decoded here.
 *
 * <p>Part of the {@code search} group: the same switch as {@code getDocument} (D5).
 */
@Service
@ConditionalOnProperty(prefix = "rag-mcp.tools", name = "search", havingValue = "true", matchIfMissing = true)
public class RecordResources {

    /** The resource template of a record. */
    public static final String URI = "rag://{collection}/{id}";

    /** The most values one completion returns, as MCP allows. */
    static final int MAX_COMPLETIONS = 100;

    private static final Logger log = LoggerFactory.getLogger(RecordResources.class);

    private final SearchService search;
    private final IndexStore store;

    public RecordResources(SearchService search, IndexStore store) {
        this.search = search;
        this.store = store;
    }

    @McpResource(uri = URI, name = "record", title = "Record of a collection",
            description = "One record of a collection in full: its passage, source and fields. The collection " +
                    "and id are those of a search hit.",
            mimeType = "text/markdown")
    public String record(String collection, String id) {
        String name = decode(collection);
        String key = decode(id);
        log.info("Resource read: record collection={} id={}", name, key);
        final SearchHit hit;
        try {
            hit = search.get(name, key).orElseThrow(() -> new IllegalArgumentException(
                    "No record '" + key + "' in collection '" + name + "'"));
        } catch (IOException e) {
            throw new UncheckedIOException("The index could not be read: " + e.getMessage(), e);
        }
        return markdown(hit);
    }

    /**
     * Completes {@code collection} with the built collections whose name starts with the typed
     * value, ignoring case, and {@code id} with the ids of the collection already chosen that start
     * with it.
     */
    @McpComplete(uri = URI)
    public CompleteCompletion complete(CompleteRequest request) {
        String argument = request.argument().name();
        String value = request.argument().value();
        return switch (argument) {
            case "collection" -> collections(value);
            case "id" -> ids(request.context() == null || request.context().arguments() == null ? null
                    : request.context().arguments().get("collection"), value);
            default -> new CompleteCompletion(List.of(), 0, false);
        };
    }

    private CompleteCompletion collections(String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> names = store.built().stream().map(Manifest::name)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        List<String> values = names.stream().limit(MAX_COMPLETIONS).toList();
        return new CompleteCompletion(values, names.size(), names.size() > values.size());
    }

    private CompleteCompletion ids(String collection, String typed) {
        if (collection == null || collection.isBlank()
                || store.manifest(decode(collection).strip()).isEmpty()) {
            return new CompleteCompletion(List.of(), 0, false);
        }
        try {
            SearchService.IdPage page = search.ids(decode(collection).strip(), typed, MAX_COMPLETIONS);
            return new CompleteCompletion(page.ids(), null, page.more());
        } catch (IOException e) {
            throw new UncheckedIOException("The index could not be read: " + e.getMessage(), e);
        }
    }

    /**
     * A record as Markdown: its breadcrumb as the heading, a list of its collection, id, source and
     * fields (nested, since a field may be named like an item), then its text.
     */
    static String markdown(SearchHit hit) {
        StringBuilder out = new StringBuilder();
        String heading = hit.breadcrumb() != null && !hit.breadcrumb().isBlank() ? hit.breadcrumb()
                : hit.title() != null && !hit.title().isBlank() ? hit.title() : hit.id();
        out.append("# ").append(heading).append("\n\n");
        out.append("- collection: ").append(hit.collection()).append('\n');
        out.append("- record: ").append(hit.id()).append('\n');
        if (hit.source() != null) {
            out.append("- source: ").append(hit.source()).append('\n');
        }
        if (!hit.fields().isEmpty()) {
            out.append("- fields:\n");
            for (Map.Entry<String, List<String>> field : hit.fields().entrySet()) {
                out.append("  - ").append(field.getKey()).append(": ")
                        .append(String.join(", ", field.getValue())).append('\n');
            }
        }
        out.append('\n').append(hit.text() == null ? "" : hit.text().strip()).append('\n');
        return out.toString();
    }

    /** A value of the template as the client percent-encoded it; a {@code +} stays a plus sign. */
    static String decode(String value) {
        return value == null ? null : URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
