package ru.it_spectrum.ai.rag.mcp.search;

import java.util.List;

/**
 * One page of search results.
 *
 * @param total      records matching the query and filters in the searched collections
 * @param hits       this page, best first
 * @param nextOffset offset of the next page, or {@code null} when there is none
 * @param truncated  the page is shorter than asked because the response size cap was reached
 */
public record SearchPage(int total, List<SearchHit> hits, Integer nextOffset, boolean truncated) {
}
