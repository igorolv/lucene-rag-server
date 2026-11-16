package ru.it_spectrum.ai.rag.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "The collections this server can search")
public record ListCollectionsResult(
        @Schema(description = "Collections, by name", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        List<CollectionInfo> collections,
        @Schema(description = "Why a file the server's indexes file imports gave no collections, or one "
                + "of its collections was left out; absent when every import is in order",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> importProblems) {
}
