package ru.it_spectrum.ai.rag.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.rag.mcp.api.ListCollectionsResult;

import java.util.List;

/**
 * Discovery for the {@code collection} argument the search tools will require.
 *
 * <p>Scaffold: no collection can be built or opened yet, so the list is always empty. Stage 1 of
 * docs/plan.md section 4 replaces the body with the indexes found under the data directory.
 *
 * <p>The {@link ConditionalOnProperty} flag is the tool-group switch every tool class carries
 * (docs/decisions.md D5); modeled on jdbc-mcp-server {@code tools/DistributionTools}.
 */
@Service
@ConditionalOnProperty(prefix = "rag-mcp.tools", name = "collections", havingValue = "true", matchIfMissing = true)
public class CollectionTools {

    private static final Logger log = LoggerFactory.getLogger(CollectionTools.class);

    @McpTool(
            description = "List the collections this server can search. Call first when the collection " +
                    "to use is not known yet: name is the value for the 'collection' argument of other " +
                    "tools, description says what the collection holds.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ListCollectionsResult listCollections() {
        log.info("Tool call: listCollections");
        return new ListCollectionsResult(List.of());
    }
}
