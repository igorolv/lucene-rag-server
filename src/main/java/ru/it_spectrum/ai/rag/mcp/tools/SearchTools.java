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
            description = "Find passages in all the knowledge of this server. The query is plain words or a question " +
                    "in any language of the texts. Lexical mode matches words: word forms match, \"quoted words\" " +
                    "are a phrase, and records matching more of the words rank higher. Vector mode finds passages " +
                    "close in meaning even without shared words; hybrid, the default, fuses both. Narrow the " +
                    "search with filters on record fields such as kind or project. By default only the best section of each document is returned; " +
                    "pass perDocument for more. Returns snippets; call " +
                    "getDocument with a hit's id for the full passage. Before concluding " +
                    "that nothing exists, retry with synonyms or fewer words.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public SearchResult search(
            @McpToolParam(description = "Words to look for. May be omitted when filters are given", required = false)
            String query,
            @McpToolParam(description = "Exact field values every result must have, e.g. {\"kind\": \"article\"}. " +
                    "describeKnowledge shows the fields and their values", required = false)
            Map<String, String> filters,
            @McpToolParam(description = "Conditions on fields when filters are not enough, in the Spring AI filter " +
                    "syntax: ==, !=, in [...], nin [...], <, <=, >, >= on quoted text, && (or AND), || (or OR), NOT, " +
                    "parentheses, values in single quotes, e.g. kind == 'article' && tags in ['refunds', 'payments']. " +
                    "Values compare ignoring case; must hold together with filters", required = false)
            String filterExpression,
            @McpToolParam(description = "Page size; the server applies its own default and maximum", required = false)
            Integer limit,
            @McpToolParam(description = "nextOffset of the previous page", required = false)
            Integer offset,
            @McpToolParam(description = "lexical, vector or hybrid. Omit for the default, hybrid", required = false)
            String mode,
            @McpToolParam(description = "Most sections of one document in the results, default 1. Raise it to " +
                    "read several sections of a document found; rows of catalogs and tables are not limited",
                    required = false)
            Integer perDocument) {
        log.info("Tool call: search queryChars={} filters={} filterExpressionChars={} limit={} offset={}"
                        + " mode={} perDocument={}", query == null ? 0 : query.length(),
                filters == null ? List.of() : filters.keySet(), filterExpression == null ? 0 : filterExpression.length(),
                limit, offset, mode, perDocument);
        if ((query == null || query.isBlank()) && (filters == null || filters.isEmpty())
                && (filterExpression == null || filterExpression.isBlank())) {
            throw new IllegalArgumentException("Give a query, filters, a filterExpression or several of them");
        }
        final SearchPage page;
        try {
            page = search.search(null, query, filters, filterExpression, limit, offset, mode, perDocument);
        } catch (IOException e) {
            throw new UncheckedIOException("The index could not be read: " + e.getMessage(), e);
        }
        List<SearchHitInfo> hits = page.hits().stream().map(hit -> new SearchHitInfo(hit.address(),
                hit.breadcrumb(), hit.source(), hit.score(), hit.snippet())).toList();
        return new SearchResult(page.total(), hits, page.nextOffset(), page.truncated(),
                page.mode() == null ? null : page.mode().id(), page.notice());
    }

    @McpTool(
            description = "Read one record in full: the passage, where it comes from and the fields of its " +
                    "document. Use the id of a search hit.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public DocumentResult getDocument(
            @McpToolParam(description = "id of the search hit, as it was returned", required = true) String id) {
        log.info("Tool call: getDocument id={}", id);
        final SearchHit hit;
        try {
            hit = search.get(id).orElseThrow(() -> new IllegalArgumentException(
                    "No record '" + id.strip() + "'"));
        } catch (IOException e) {
            throw new UncheckedIOException("The index could not be read: " + e.getMessage(), e);
        }
        return new DocumentResult(hit.address(), hit.document(), hit.ordinal(), hit.breadcrumb(),
                hit.source(), hit.text(), hit.fields());
    }
}
