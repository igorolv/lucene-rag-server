package ru.it_spectrum.ai.rag.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Records related to one record, as {@code relatedRecords} returns them. Every field carries
 * {@code nullable = true} and an explicit {@code requiredMode}, as {@link CollectionInfo} explains.
 */
@Schema(description = "Records related to one record")
public record RelatedResult(
        @Schema(description = "The record asked about, without text", nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        SearchHitInfo record,
        @Schema(description = "Other sections of its document, in document order, those nearest to it; " +
                "empty for a catalog or table row, which stands alone",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        List<SearchHitInfo> sections,
        @Schema(description = "Records most like it outside its document, best first, with the start of " +
                "their text as the snippet", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        List<SearchHitInfo> similar,
        @Schema(description = "How likeness was found: vector (the embeddings of the records) or words " +
                "(their shared words, in a collection without vectors)",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        String similarBy) {
}
