package ru.it_spectrum.ai.rag.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Why a search ranks records as it does, as {@code explainSearch} returns it. Every field carries
 * {@code nullable = true} and an explicit {@code requiredMode}, as {@link CollectionInfo} explains.
 */
@Schema(description = "How a search treats the query and why records rank where they do")
public record ExplainResult(
        @Schema(description = "The mode the search ran in: lexical, vector or hybrid", nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        String mode,
        @Schema(description = "The query's words as the Russian analysis indexes them: stems, stop words gone; " +
                "a word missing here cannot match", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> terms,
        @Schema(description = "The same for the English analysis", nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> englishTerms,
        @Schema(description = "Records the words and filters match", nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        int matching,
        @Schema(description = "The first records of the ranking, before search limits records per document",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        List<ExplainedHit> ranked,
        @Schema(description = "The record asked about by id; absent when none was", nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        ExplainedHit target,
        @Schema(description = "Whether the query's words match the target at all; absent without a target",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean targetMatchesWords,
        @Schema(description = "Whether the target passes the filters; absent without a target", nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean targetPassesFilters,
        @Schema(description = "Why the mode differs from the one asked for; absent when it does not",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String notice) {

    @Schema(description = "A record and its place in each ranked list")
    public record ExplainedHit(
            @Schema(description = "Record id, as search gives it", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
            String id,
            @Schema(description = "Document title and headings", nullable = true,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String breadcrumb,
            @Schema(description = "Place in the ranking, 1 first; absent beyond the first 1000", nullable = true,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Integer rank,
            @Schema(description = "Place among the records the words match; absent when they do not match it, " +
                    "beyond 1000, or in vector mode", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Integer lexicalRank,
            @Schema(description = "Place among the records nearest to the query vector; absent beyond 1000 " +
                    "or in lexical mode", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Integer vectorRank,
            @Schema(description = "The score of the ranking; in hybrid the fusion score", nullable = true,
                    requiredMode = Schema.RequiredMode.REQUIRED)
            float score) {
    }
}
