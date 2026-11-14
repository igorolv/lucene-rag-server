package ru.it_spectrum.ai.rag.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.rag.mcp.api.DocumentResult;
import ru.it_spectrum.ai.rag.mcp.api.SearchHitInfo;
import ru.it_spectrum.ai.rag.mcp.api.SearchResult;
import ru.it_spectrum.ai.rag.mcp.search.SearchHit;
import ru.it_spectrum.ai.rag.mcp.search.SearchPage;
import ru.it_spectrum.ai.rag.mcp.search.SearchService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * The {@code search} tool group: find passages and read one in full. Thin adapters over
 * {@link SearchService}; argument problems surface as tool errors with the service's message.
 */
@Service
@ConditionalOnProperty(prefix = "rag-mcp.tools", name = "search", havingValue = "true", matchIfMissing = true)
public class SearchTools {

    private static final Logger log = LoggerFactory.getLogger(SearchTools.class);

    private final SearchService search;

    public SearchTools(SearchService search) {
        this.search = search;
    }

    @McpTool(
            description = "Find passages in one or more collections. The query is plain words in any " +
                    "language of the texts; word forms match, \"quoted words\" are a phrase, and words are " +
                    "optional — records matching more of them rank higher. Returns snippets; call " +
                    "getDocument with a hit's collection and id for the full passage. Before concluding " +
                    "that nothing exists, retry with synonyms or fewer words.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public SearchResult search(
            @McpToolParam(description = "Collection names from listCollections", required = true)
            List<String> collections,
            @McpToolParam(description = "Words to look for. May be omitted when filters are given", required = false)
            String query,
            @McpToolParam(description = "Exact field values every result must have, e.g. {\"kind\": \"article\"}. " +
                    "Field names come from listCollections", required = false)
            Map<String, String> filters,
            @McpToolParam(description = "Page size; the server applies its own default and maximum", required = false)
            Integer limit,
            @McpToolParam(description = "nextOffset of the previous page", required = false)
            Integer offset) {
        log.info("Tool call: search collections={} queryChars={} filters={} limit={} offset={}",
                collections, query == null ? 0 : query.length(), filters == null ? List.of() : filters.keySet(),
                limit, offset);
        if ((query == null || query.isBlank()) && (filters == null || filters.isEmpty())) {
            throw new IllegalArgumentException("Give a query, filters or both");
        }
        final SearchPage page;
        try {
            page = search.search(collections, query, filters, limit, offset);
        } catch (IOException e) {
            throw new UncheckedIOException("The index could not be read: " + e.getMessage(), e);
        }
        List<SearchHitInfo> hits = page.hits().stream().map(hit -> new SearchHitInfo(hit.collection(), hit.id(),
                hit.breadcrumb(), hit.source(), hit.score(), hit.snippet())).toList();
        return new SearchResult(page.total(), hits, page.nextOffset(), page.truncated());
    }

    @McpTool(
            description = "Read one record in full: the passage, where it comes from and the fields of its " +
                    "document. Use the collection and id of a search hit.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public DocumentResult getDocument(
            @McpToolParam(description = "Collection of the search hit", required = true) String collection,
            @McpToolParam(description = "id of the search hit", required = true) String id) {
        log.info("Tool call: getDocument collection={} id={}", collection, id);
        final SearchHit hit;
        try {
            hit = search.get(collection, id).orElseThrow(() -> new IllegalArgumentException(
                    "No record '" + id + "' in collection '" + collection + "'"));
        } catch (IOException e) {
            throw new UncheckedIOException("The index could not be read: " + e.getMessage(), e);
        }
        return new DocumentResult(hit.collection(), hit.id(), hit.document(), hit.ordinal(), hit.breadcrumb(),
                hit.source(), hit.text(), hit.fields());
    }
}
