package ru.it_spectrum.ai.rag.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What this server knows, as {@code describeKnowledge} returns it (docs/decisions.md D89): the
 * projects and one section per built index, with the fields a search can be narrowed by. Nothing
 * here selects where to search.
 *
 * <p>Every field carries {@code nullable = true} and an explicit {@code requiredMode}: the server
 * JSON mapper drops null values, so a field left at the default mode would be declared required
 * and then be missing from the response (jdbc-mcp-server {@code AGENTS.md}, "@Schema annotations
 * on response records"). The other response records follow the same rule.
 */
@Schema(description = "What knowledge this server holds and how a search can be narrowed")
public record KnowledgeResult(
        @Schema(description = "Projects with the number of records of each; a value of the field 'project'",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        List<ProjectInfo> projects,
        @Schema(description = "Parts of the knowledge, each with what it holds and its fields; search covers " +
                "all of them at once", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        List<Section> sections) {

    @Schema(description = "A project and the number of its records")
    public record ProjectInfo(
            @Schema(description = "Value of the field 'project'", nullable = true,
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String project,
            @Schema(description = "Records of the project", nullable = true,
                    requiredMode = Schema.RequiredMode.REQUIRED)
            int records) {
    }

    @Schema(description = "One part of the knowledge")
    public record Section(
            @Schema(description = "Name of the part, a label only; not an argument of any tool", nullable = true,
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String label,
            @Schema(description = "Project of its records; absent when they have none", nullable = true,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String project,
            @Schema(description = "What it holds, written by the operator", nullable = true,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String description,
            @Schema(description = "Number of records", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
            int records,
            @Schema(description = "Fields whose values the lookup tool finds records by; absent when none",
                    nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            List<String> keys,
            @Schema(description = "Fields the 'filters' and 'filterExpression' of search accept", nullable = true,
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<FieldInfo> fields) {
    }

    @Schema(description = "A field and its values")
    public record FieldInfo(
            @Schema(description = "Field name", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "How many different values it has", nullable = true,
                    requiredMode = Schema.RequiredMode.REQUIRED)
            int distinct,
            @Schema(description = "Its values with the number of records of each, when they are few; absent " +
                    "otherwise (fieldValues lists them)", nullable = true,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            List<FieldValuesResult.FieldValue> values) {
    }
}
