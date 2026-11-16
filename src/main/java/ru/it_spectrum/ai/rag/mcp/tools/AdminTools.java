package ru.it_spectrum.ai.rag.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.rag.mcp.api.RebuildResult;
import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.IndexesLoader;
import ru.it_spectrum.ai.rag.mcp.definition.LoadedIndexes;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.embedding.EmbeddingModels;
import ru.it_spectrum.ai.rag.mcp.index.IndexBuilder;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

/**
 * The {@code admin} tool group: rebuild a collection from its sources, as {@code rag-index} does,
 * without leaving the client. The collection must be described in {@code collections.json}, which
 * the server reads on each call; the argument only names a collection of that file, so nothing a
 * client sends reaches a command line (docs/decisions.md D10). The group is on by default, as every
 * group is (D50).
 *
 * <p>One collection is rebuilt at a time per name; a second call for the same collection fails
 * instead of waiting. Searches go on during a build and see the new index once it is swapped in.
 */
@Service
@ConditionalOnProperty(prefix = "rag-mcp.tools", name = "admin", havingValue = "true", matchIfMissing = true)
public class AdminTools {

    private static final Logger log = LoggerFactory.getLogger(AdminTools.class);

    private final RagMcpProperties properties;
    private final StepRegistry registry;
    private final EmbeddingModels models;
    private final UnaryOperator<String> env;
    private final ConcurrentHashMap<String, ReentrantLock> building = new ConcurrentHashMap<>();

    @Autowired
    public AdminTools(RagMcpProperties properties, StepRegistry registry, EmbeddingModels models) {
        this(properties, registry, models, System::getenv);
    }

    AdminTools(RagMcpProperties properties, StepRegistry registry, EmbeddingModels models,
               UnaryOperator<String> env) {
        this.properties = properties;
        this.registry = registry;
        this.models = models;
        this.env = env;
    }

    @McpTool(
            description = "Rebuild one collection from its sources, as described in the server's collections " +
                    "file, so that search sees changed files. Takes from seconds to minutes; the old index is " +
                    "served until the new one is complete, and a failed build keeps it.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false)
    )
    public RebuildResult rebuildCollection(
            @McpToolParam(description = "Name of a collection from listCollections", required = true)
            String collection) {
        log.info("Tool call: rebuildCollection collection={}", collection);
        String name = collection == null ? "" : collection.strip();
        final List<IndexDefinition> definitions;
        final List<String> importProblems;
        try {
            LoadedIndexes loaded = IndexesLoader.read(properties, env);
            definitions = registry.checked(loaded.indexes());
            importProblems = loaded.importProblems();
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException("The collections file cannot be used: " + e.getMessage(), e);
        }
        IndexDefinition definition = definitions.stream().filter(d -> d.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Collection '" + name + "' is not in "
                        + properties.resolvedCollectionsFile() + " or a file it imports; defined: "
                        + String.join(", ", definitions.stream().map(IndexDefinition::name).toList())
                        + (importProblems.isEmpty() ? "" : "; " + String.join("; ", importProblems))));
        if (!definition.usable()) {
            throw new IllegalArgumentException("Collection '" + name + "': " + definition.configError());
        }
        ReentrantLock lock = building.computeIfAbsent(name, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new IllegalStateException("Collection '" + name + "' is being rebuilt already");
        }
        try {
            long start = System.nanoTime();
            IndexBuilder.Built built = IndexBuilder.build(definition, registry, models,
                    properties.resolvedDataDir(), serverVersion());
            Manifest manifest = built.manifest();
            double seconds = Math.round((System.nanoTime() - start) / 1e8) / 10.0;
            log.info("Rebuilt collection {}: {} records in {} s", name, manifest.records(), seconds);
            boolean vectors = manifest.vectors() != null;
            return new RebuildResult(name, manifest.files(), manifest.documents(), manifest.records(), seconds,
                    manifest.builtAt(), vectors ? built.embedded() : null, vectors ? built.cached() : null,
                    vectors ? manifest.vectors().truncated() : null);
        } catch (IOException e) {
            throw new UncheckedIOException("Collection '" + name + "' was not rebuilt: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    private static String serverVersion() {
        String version = AdminTools.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }
}
