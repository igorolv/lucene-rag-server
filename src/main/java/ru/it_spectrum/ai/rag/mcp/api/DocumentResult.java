package ru.it_spectrum.ai.rag.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "One record with its full text, or the sections of its document")
public record DocumentResult(
        @Schema(description = "Record id, as search gives it", nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        String id,
        @Schema(description = "Key of the document the record is a part of",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String document,
        @Schema(description = "Position among the records of the document, from 0; with scope document or "
                + "rest, of the first section in text",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        int ordinal,
        @Schema(description = "Document title and headings leading to the passage",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String breadcrumb,
        @Schema(description = "Source file the passage comes from, relative to its source root",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String source,
        @Schema(description = "The passage; with scope document or rest, the sections in document order, each "
                + "after a '## ' line with its breadcrumb", nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String text,
        @Schema(description = "Document fields: name to values",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Map<String, List<String>> fields,
        @Schema(description = "With scope document or rest: sections in text",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer sections,
        @Schema(description = "With scope document or rest: sections the document has",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer sectionsInDocument,
        @Schema(description = "First section left out by the size cap; read on with getDocument(nextId, "
                + "scope rest). Absent when text reaches the end of the document",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String nextId) {

    /** One record, as {@code scope: record} gives it. */
    public DocumentResult(String id, String document, int ordinal, String breadcrumb, String source, String text,
                          Map<String, List<String>> fields) {
        this(id, document, ordinal, breadcrumb, source, text, fields, null, null, null);
    }
}
