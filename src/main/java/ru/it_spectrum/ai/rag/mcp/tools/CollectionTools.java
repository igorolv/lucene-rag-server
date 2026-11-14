package ru.it_spectrum.ai.rag.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.rag.mcp.api.CollectionInfo;
import ru.it_spectrum.ai.rag.mcp.api.ListCollectionsResult;
import ru.it_spectrum.ai.rag.mcp.collections.CollectionDefinition;
import ru.it_spectrum.ai.rag.mcp.collections.CollectionsLoader;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.index.CollectionStore;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Discovery for the collection arguments of the search tools.
 *
 * <p>Built collections come from the data directory alone. Collections that are only described in
 * {@code collections.json} are listed too, as not built, so a missing index is visible; the file
 * is reread on every call and is optional.
 *
 * <p>The {@link ConditionalOnProperty} flag is the tool-group switch every tool class carries
 * (docs/decisions.md D5); modeled on jdbc-mcp-server {@code tools/DistributionTools}.
 */
@Service
@ConditionalOnProperty(prefix = "rag-mcp.tools", name = "collections", havingValue = "true", matchIfMissing = true)
public class CollectionTools {

    private static final Logger log = LoggerFactory.getLogger(CollectionTools.class);

    private final CollectionStore store;
    private final RagMcpProperties properties;

    public CollectionTools(CollectionStore store, RagMcpProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @McpTool(
            description = "List the collections of this server. Call first when the collection to search " +
                    "is not known yet: name is the value for the collection arguments of other tools, " +
                    "description says what the collection holds, fields are the names search accepts as " +
                    "filters. Only collections with built=true can be searched.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ListCollectionsResult listCollections() {
        log.info("Tool call: listCollections");
        List<CollectionInfo> infos = new ArrayList<>();
        List<Manifest> built = store.built();
        for (Manifest manifest : built) {
            infos.add(new CollectionInfo(manifest.name(), manifest.description(), true, manifest.records(),
                    manifest.builtAt(), manifest.fields(), null));
        }
        Set<String> builtNames = built.stream().map(Manifest::name).collect(Collectors.toSet());
        try {
            for (CollectionDefinition definition : CollectionsLoader.load(properties, System::getenv)) {
                if (!builtNames.contains(definition.name())) {
                    infos.add(new CollectionInfo(definition.name(), definition.description(), false, null,
                            null, null, definition.configError()));
                }
            }
        } catch (IllegalStateException e) {
            log.warn("The collections file is ignored: {}", e.getMessage());
        }
        return new ListCollectionsResult(List.copyOf(infos));
    }
}
