package ru.it_spectrum.ai.rag.mcp.search;

import java.util.List;

/**
 * One page of search results.
 *
 * @param total      records found in the searched collections: all matches of a lexical search,
 *                   the fused candidates of a vector or hybrid search
 * @param hits       this page, best first
 * @param nextOffset offset of the next page, or {@code null} when there is none
 * @param truncated  the page is shorter than asked because the response size cap was reached
 * @param mode       how the records were ranked; {@code null} for a lookup
 * @param notice     why the search ran otherwise than the call implied, for example lexically
 *                   because a model is missing; {@code null} when it did not
 */
public record SearchPage(int total, List<SearchHit> hits, Integer nextOffset, boolean truncated, SearchMode mode,
                         String notice) {

    public SearchPage(int total, List<SearchHit> hits, Integer nextOffset, boolean truncated, SearchMode mode) {
        this(total, hits, nextOffset, truncated, mode, null);
    }

    /** The same page with a notice. */
    public SearchPage withNotice(String notice) {
        return new SearchPage(total, hits, nextOffset, truncated, mode, notice);
    }
}
