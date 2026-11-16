package ru.it_spectrum.ai.rag.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.rag.mcp.api.FieldValuesResult;
import ru.it_spectrum.ai.rag.mcp.api.KnowledgeResult;
import ru.it_spectrum.ai.rag.mcp.index.IndexFields;
import ru.it_spectrum.ai.rag.mcp.index.IndexStore;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The {@code knowledge} tool group: what an assistant may narrow a search by (docs/decisions.md
 * D89). Everything comes from the manifests of the built indexes: neither an index nor the indexes
 * file is opened (invariant 4), so the answer is the same on a machine without the sources.
 *
 * <p>The {@link ConditionalOnProperty} flag is the tool-group switch every tool class carries
 * (docs/decisions.md D5); modeled on jdbc-mcp-server {@code tools/DistributionTools}.
 */
@Service
@ConditionalOnProperty(prefix = "rag-mcp.tools", name = "knowledge", havingValue = "true", matchIfMissing = true)
public class KnowledgeTools {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTools.class);

    private final IndexStore store;

    public KnowledgeTools(IndexStore store) {
        this.store = store;
    }

    @McpTool(
            description = "Describe the knowledge this server holds: its projects with their record counts and " +
                    "its parts, each with what it holds, its record count, the fields search can filter by and, " +
                    "for a field with few values, those values and their record counts. Calling it is not " +
                    "required: search covers all the knowledge without it. Use it to learn which fields and " +
                    "values narrow a search, e.g. filters {\"kind\": \"term\"} or {\"project\": \"billing\"}.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public KnowledgeResult describeKnowledge() {
        log.info("Tool call: describeKnowledge");
        Map<String, Integer> projects = new TreeMap<>();
        List<KnowledgeResult.Section> sections = new ArrayList<>();
        for (Manifest manifest : store.built()) {
            if (manifest.project() != null) {
                projects.merge(manifest.project(), manifest.records(), Integer::sum);
            }
            List<KnowledgeResult.FieldInfo> fields = new ArrayList<>();
            for (String field : manifest.fields()) {
                if (field.equals(IndexFields.PROJECT)) {
                    continue;
                }
                Manifest.FieldValues values = manifest.fieldValues().get(field);
                fields.add(new KnowledgeResult.FieldInfo(field, values == null ? 0 : values.distinct(),
                        values == null || values.values() == null ? null : values.values().stream()
                                .map(v -> new FieldValuesResult.FieldValue(v.value(), v.records())).toList()));
            }
            sections.add(new KnowledgeResult.Section(manifest.name(), manifest.project(), manifest.description(),
                    manifest.records(), manifest.keys().isEmpty() ? null : manifest.keys(), List.copyOf(fields)));
        }
        return new KnowledgeResult(projects.entrySet().stream()
                .map(e -> new KnowledgeResult.ProjectInfo(e.getKey(), e.getValue())).toList(), List.copyOf(sections));
    }
}
