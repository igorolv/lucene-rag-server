package ru.it_spectrum.ai.rag.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.rag.mcp.api.SearchHitInfo;
import ru.it_spectrum.ai.rag.mcp.api.SearchResult;
import ru.it_spectrum.ai.rag.mcp.search.SearchPage;
import ru.it_spectrum.ai.rag.mcp.search.SearchService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * The {@code lookup} tool group: exact lookups in catalog collections by a value, without naming
 * the field it is in (docs/decisions.md D23). The fields searched are the keys of the collection,
 * {@code map.keys} of its sources.
 */
@Service
@ConditionalOnProperty(prefix = "rag-mcp.tools", name = "lookup", havingValue = "true", matchIfMissing = true)
public class LookupTools {

    private static final Logger log = LoggerFactory.getLogger(LookupTools.class);

    private final SearchService search;

    public LookupTools(SearchService search) {
        this.search = search;
    }

    @McpTool(
            description = "Find catalog records by an exact value: a code, an identifier, a class or table " +
                    "name, a message text. The value is compared whole with the key fields of the " +
                    "collections (listCollections shows them as keys), ignoring case; words are not " +
                    "matched one by one, so use search for questions and descriptions. getDocument reads " +
                    "a hit in full by its collection and id.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public SearchResult lookup(
            @McpToolParam(description = "Collection names from listCollections; those with keys are searched",
                    required = true)
            List<String> collections,
            @McpToolParam(description = "The exact value to find, e.g. a code", required = true)
            String value,
            @McpToolParam(description = "Page size; the server applies its own default and maximum", required = false)
            Integer limit,
            @McpToolParam(description = "nextOffset of the previous page", required = false)
            Integer offset) {
        log.info("Tool call: lookup collections={} valueChars={} limit={} offset={}",
                collections, value == null ? 0 : value.length(), limit, offset);
        final SearchPage page;
        try {
            page = search.lookup(collections, value, limit, offset);
        } catch (IOException e) {
            throw new UncheckedIOException("The index could not be read: " + e.getMessage(), e);
        }
        List<SearchHitInfo> hits = page.hits().stream().map(hit -> new SearchHitInfo(hit.collection(), hit.id(),
                hit.breadcrumb(), hit.source(), hit.score(), hit.snippet())).toList();
        return new SearchResult(page.total(), hits, page.nextOffset(), page.truncated());
    }
}
