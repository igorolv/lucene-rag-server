package ru.it_spectrum.ai.rag.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The indexes of this server as the operator sees them, as {@code listIndexes} returns them
 * (docs/decisions.md D89). Every field carries {@code nullable = true} and an explicit
 * {@code requiredMode}, as {@link KnowledgeResult} explains.
 */
@Schema(description = "The indexes of this server: built ones and those only described in the indexes file")
public record ListIndexesResult(
        @Schema(description = "Indexes, built first", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        List<IndexInfo> indexes,
        @Schema(description = "Why a file the server's indexes file imports gave no indexes, or one of its " +
                "indexes was left out; absent when every import is in order",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> importProblems,
        @Schema(description = "An indexes file of the old name that is not read, with what to rename; absent " +
                "when there is none", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String notice) {

    @Schema(description = "One index")
    public record IndexInfo(
            @Schema(description = "Name of the index; the value rebuildIndex takes", nullable = true,
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "Project of its records; absent when none", nullable = true,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String project,
            @Schema(description = "true when it is built and searched", nullable = true,
                    requiredMode = Schema.RequiredMode.REQUIRED)
            boolean built,
            @Schema(description = "When it was built, ISO-8601 UTC; absent when not built", nullable = true,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String builtAt,
            @Schema(description = "Number of records; absent when not built", nullable = true,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Integer records,
            @Schema(description = "Embedding model; absent without vectors", nullable = true,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String model,
            @Schema(description = "Why vector and hybrid search cannot use the model, e.g. its files are missing; " +
                    "absent when nothing is known to be wrong", nullable = true,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String modelProblem,
            @Schema(description = "Why it cannot be built, when its configuration is wrong", nullable = true,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String configError) {
    }
}
