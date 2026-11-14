package ru.it_spectrum.ai.rag.mcp.search;

import java.util.List;
import java.util.Map;

/**
 * One record as search and lookup return it.
 *
 * @param score   relevance; comparable only inside one search
 * @param snippet passage around the matches with the matches in {@code **bold**}; {@code null}
 *                when the record was fetched by id
 * @param text    the full passage; {@code null} in search results
 */
public record SearchHit(String collection, String id, String document, String title, String breadcrumb,
                        String source, int ordinal, float score, String snippet, String text,
                        Map<String, List<String>> fields) {
}
