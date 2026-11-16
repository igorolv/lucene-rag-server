package ru.it_spectrum.ai.rag.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The values of a field, as {@code fieldValues} returns them. Every field carries
 * {@code nullable = true} and an explicit {@code requiredMode}, as {@link KnowledgeResult} explains.
 */
@Schema(description = "Values of a field with the number of records of each, most frequent first")
public record FieldValuesResult(
        @Schema(description = "The field", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        String field,
        @Schema(description = "Distinct values of the field matching the prefix in the counted records, also those not returned",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        int distinct,
        @Schema(description = "The values; each works as a value of search's 'filters' for this field",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        List<FieldValue> values) {

    @Schema(description = "One value of a field")
    public record FieldValue(
            @Schema(description = "The value as records have it", nullable = true,
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String value,
            @Schema(description = "Records with the value", nullable = true,
                    requiredMode = Schema.RequiredMode.REQUIRED)
            int count) {
    }
}
