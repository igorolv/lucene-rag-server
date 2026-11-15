package ru.it_spectrum.ai.rag.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.rag.mcp.api.ExplainResult;
import ru.it_spectrum.ai.rag.mcp.search.SearchService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * The {@code debug} tool group (docs/decisions.md D72): why a search finds what it finds, for
 * whoever tunes the collections and the search. A model answering questions does not need it, and
 * a small model may do without it ({@code RAG_MCP_TOOLS_DEBUG=false}).
 */
@Service
@ConditionalOnProperty(prefix = "rag-mcp.tools", name = "debug", havingValue = "true", matchIfMissing = true)
public class DebugTools {

    private static final Logger log = LoggerFactory.getLogger(DebugTools.class);

    private final SearchService search;

    public DebugTools(SearchService search) {
        this.search = search;
    }

    @McpTool(
            description = "Explain a search: the words the query becomes after analysis (a word missing there " +
                    "cannot match), the first records of the ranking with their place in the word list and the " +
                    "vector list, and, for a record given by id, where it ranks in each and whether the words " +
                    "and filters match it at all. For tuning collections and queries, not for answering.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ExplainResult explainSearch(
            @McpToolParam(description = "Collection names from listCollections", required = true)
            List<String> collections,
            @McpToolParam(description = "The query, as for search", required = true) String query,
            @McpToolParam(description = "Exact field values, as for search", required = false)
            Map<String, String> filters,
            @McpToolParam(description = "A filter expression, as for search", required = false)
            String filterExpression,
            @McpToolParam(description = "lexical, vector or hybrid; the default as for search", required = false)
            String mode,
            @McpToolParam(description = "id of a record to explain, e.g. the one expected but not found",
                    required = false)
            String id,
            @McpToolParam(description = "Records of the ranking to show; the server applies its page limits",
                    required = false)
            Integer limit) {
        log.info("Tool call: explainSearch collections={} queryChars={} mode={} id={} limit={}", collections,
                query == null ? 0 : query.length(), mode, id, limit);
        try {
            SearchService.Explanation explanation = search.explain(collections, query, filters, filterExpression,
                    mode, id, limit);
            return new ExplainResult(explanation.mode().id(), explanation.terms(), explanation.englishTerms(),
                    explanation.matching(), explanation.ranked().stream().map(DebugTools::hit).toList(),
                    explanation.target() == null ? null : hit(explanation.target()), explanation.matchesWords(),
                    explanation.passesFilters(), explanation.notice());
        } catch (IOException e) {
            throw new UncheckedIOException("The index could not be read: " + e.getMessage(), e);
        }
    }

    private static ExplainResult.ExplainedHit hit(SearchService.Ranked ranked) {
        return new ExplainResult.ExplainedHit(ranked.hit().collection(), ranked.hit().id(), ranked.hit().breadcrumb(),
                ranked.rank(), ranked.lexicalRank(), ranked.vectorRank(), ranked.hit().score());
    }
}
