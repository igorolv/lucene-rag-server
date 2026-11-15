package ru.it_spectrum.ai.rag.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One collection as {@code listCollections} reports it.
 *
 * <p>Every field carries {@code nullable = true} and an explicit {@code requiredMode}: the server
 * JSON mapper drops null values, so a field left at the default mode would be declared required
 * and then be missing from the response (jdbc-mcp-server {@code AGENTS.md}, "@Schema annotations
 * on response records").
 */
@Schema(description = "One collection of this server")
public record CollectionInfo(
        @Schema(description = "Value for the 'collections' and 'collection' arguments of other tools",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(description = "What the collection holds, written by the operator",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String description,
        @Schema(description = "true when the collection has an index and can be searched",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        boolean built,
        @Schema(description = "Number of searchable records",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer records,
        @Schema(description = "When the index was built, ISO-8601 UTC",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String builtAt,
        @Schema(description = "Field names accepted by the 'filters' and 'filterExpression' arguments of search",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> fields,
        @Schema(description = "Fields whose values the lookup tool finds records by; absent when none",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> keys,
        @Schema(description = "Values the 'mode' argument of search accepts for this collection; vector and " +
                "hybrid only when the collection has vectors",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> modes,
        @Schema(description = "Why the collection cannot be built, when its configuration is wrong",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String configError) {
}
