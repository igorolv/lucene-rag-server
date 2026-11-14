package ru.it_spectrum.ai.rag.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One search result")
public record SearchHitInfo(
        @Schema(description = "Collection the record is in",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        String collection,
        @Schema(description = "Record id; pass it to getDocument with the same collection for the full text",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        String id,
        @Schema(description = "Document title and headings leading to the passage",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String breadcrumb,
        @Schema(description = "Source file the passage comes from, relative to its source root",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String source,
        @Schema(description = "Relevance; comparable only inside one search",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        float score,
        @Schema(description = "Part of the passage around the matches, matches in **bold**",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String snippet) {
}
