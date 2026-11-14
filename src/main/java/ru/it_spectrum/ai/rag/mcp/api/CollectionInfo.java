package ru.it_spectrum.ai.rag.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One collection as {@code listCollections} reports it.
 *
 * <p>Every field carries {@code nullable = true} and an explicit {@code requiredMode}: the server
 * JSON mapper drops null values, so a field left at the default mode would be declared required
 * and then be missing from the response (jdbc-mcp-server {@code AGENTS.md}, "@Schema annotations
 * on response records").
 */
@Schema(description = "One collection this server can search")
public record CollectionInfo(
        @Schema(description = "Value for the 'collection' argument of other tools",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(description = "What the collection holds, written by the operator",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String description) {
}
