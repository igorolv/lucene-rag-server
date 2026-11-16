package ru.it_spectrum.ai.rag.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "One record with its full text")
public record DocumentResult(
        @Schema(description = "Record id, as search gives it", nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        String id,
        @Schema(description = "Key of the document the record is a part of",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String document,
        @Schema(description = "Position among the records of the document, from 0",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        int ordinal,
        @Schema(description = "Document title and headings leading to the passage",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String breadcrumb,
        @Schema(description = "Source file the passage comes from, relative to its source root",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String source,
        @Schema(description = "The passage", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String text,
        @Schema(description = "Document fields: name to values",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Map<String, List<String>> fields) {
}
