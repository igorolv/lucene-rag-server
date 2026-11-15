package ru.it_spectrum.ai.rag.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.rag.mcp.api.FieldValuesResult;
import ru.it_spectrum.ai.rag.mcp.api.RelatedResult;
import ru.it_spectrum.ai.rag.mcp.api.SearchHitInfo;
import ru.it_spectrum.ai.rag.mcp.search.BrowseService;
import ru.it_spectrum.ai.rag.mcp.search.SearchHit;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The {@code browse} tool group (docs/decisions.md D72): what values a field has, to filter by
 * them, and what is around a record. Listing records by filters is {@code search} without a query.
 */
@Service
@ConditionalOnProperty(prefix = "rag-mcp.tools", name = "browse", havingValue = "true", matchIfMissing = true)
public class BrowseTools {

    private static final Logger log = LoggerFactory.getLogger(BrowseTools.class);

    private final BrowseService browse;

    public BrowseTools(BrowseService browse) {
        this.browse = browse;
    }

    @McpTool(
            description = "List the values a field of a collection has, with the number of records of each, " +
                    "most frequent first. Use a value as a filter of search to narrow it down. Field names " +
                    "come from listCollections.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public FieldValuesResult fieldValues(
            @McpToolParam(description = "Collection name from listCollections", required = true) String collection,
            @McpToolParam(description = "Field name from the fields of the collection", required = true) String field,
            @McpToolParam(description = "Only values starting with this, ignoring case", required = false)
            String prefix,
            @McpToolParam(description = "Values to return, default 50, at most 200", required = false) Integer limit) {
        log.info("Tool call: fieldValues collection={} field={} prefixChars={} limit={}", collection, field,
                prefix == null ? 0 : prefix.length(), limit);
        try {
            BrowseService.FieldValues values = browse.fieldValues(collection, field, prefix, limit);
            return new FieldValuesResult(values.collection(), values.field(), values.distinct(),
                    values.values().stream().map(v -> new FieldValuesResult.FieldValue(v.value(), v.count())).toList());
        } catch (IOException e) {
            throw new UncheckedIOException("The index could not be read: " + e.getMessage(), e);
        }
    }

    @McpTool(
            description = "Show what is around a record: the other sections of its document nearest to it, and " +
                    "the records most like it elsewhere in the collection. Use the collection and id of a " +
                    "search hit; read a related record with getDocument.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public RelatedResult relatedRecords(
            @McpToolParam(description = "Collection of the record", required = true) String collection,
            @McpToolParam(description = "id of the record", required = true) String id,
            @McpToolParam(description = "Sections and similar records to return, each; default 5", required = false)
            Integer limit) {
        log.info("Tool call: relatedRecords collection={} id={} limit={}", collection, id, limit);
        try {
            BrowseService.Related related = browse.related(collection, id, limit);
            return new RelatedResult(info(related.record()), related.sections().stream().map(BrowseTools::info).toList(),
                    related.similar().stream().map(BrowseTools::info).toList(), related.similarBy());
        } catch (IOException e) {
            throw new UncheckedIOException("The index could not be read: " + e.getMessage(), e);
        }
    }

    static SearchHitInfo info(SearchHit hit) {
        return new SearchHitInfo(hit.collection(), hit.id(), hit.breadcrumb(), hit.source(), hit.score(),
                hit.snippet());
    }
}
