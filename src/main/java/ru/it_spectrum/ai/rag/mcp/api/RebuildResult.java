package ru.it_spectrum.ai.rag.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A collection rebuilt by {@code rebuildCollection}: what {@code rag-index} prints for it.
 *
 * <p>Every field carries {@code nullable = true} and an explicit {@code requiredMode}, as
 * {@link CollectionInfo} explains.
 */
@Schema(description = "A collection rebuilt from its sources")
public record RebuildResult(
        @Schema(description = "The collection", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        String collection,
        @Schema(description = "Files read from the sources", nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        int files,
        @Schema(description = "Documents made of them", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        int documents,
        @Schema(description = "Searchable records now in the collection", nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        int records,
        @Schema(description = "Build time in seconds", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        double seconds,
        @Schema(description = "When the index was built, ISO-8601 UTC", nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        String builtAt,
        @Schema(description = "Records the embedding model embedded; absent without vectors", nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer embedded,
        @Schema(description = "Records whose vector came from the embedding cache; absent without vectors",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer fromCache,
        @Schema(description = "Records longer than the model input, whose vector covers only their beginning; " +
                "absent without vectors", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer truncated) {
}
