package ru.it_spectrum.ai.rag.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "One page of search results, best first")
public record SearchResult(
        @Schema(description = "Records matching the query and filters",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        int total,
        @Schema(description = "This page", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        List<SearchHitInfo> hits,
        @Schema(description = "Pass as 'offset' with the same arguments for the next page; absent on the last page",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer nextOffset,
        @Schema(description = "true when the page is shorter than asked because the response size cap was reached",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        boolean truncated,
        @Schema(description = "How the hits were ranked: lexical, vector or hybrid",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String mode,
        @Schema(description = "Why the search ran otherwise than asked, e.g. by words only because the " +
                "embedding model of a collection is missing; absent when it ran as asked",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String notice) {
}
