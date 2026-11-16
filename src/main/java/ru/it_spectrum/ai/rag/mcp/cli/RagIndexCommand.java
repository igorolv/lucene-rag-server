package ru.it_spectrum.ai.rag.mcp.cli;

import org.springframework.context.ConfigurableApplicationContext;
import ru.it_spectrum.ai.rag.mcp.collections.CollectionDefinition;
import ru.it_spectrum.ai.rag.mcp.collections.CollectionsLoader;
import ru.it_spectrum.ai.rag.mcp.collections.LoadedCollections;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.embedding.EmbeddingModels;
import ru.it_spectrum.ai.rag.mcp.embedding.BeanModelType;
import ru.it_spectrum.ai.rag.mcp.index.CollectionIndexer;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * User-run build of collections from their sources. It starts the application context to get the
 * same step beans the server has, with the MCP server switched off, so the stdio transport is
 * never opened and stdout carries only the report (docs/decisions.md D19).
 *
 * <p>Exit codes: 0 built, 1 unexpected error, 2 usage or configuration, 4 a source could not be
 * read or the index could not be written.
 */
public final class RagIndexCommand {

    public static final String MODE = "rag-index";

    private RagIndexCommand() {
    }

    /**
     * @param application the Spring Boot application class; passed in because this package must
     *                    not depend on the root package
     */
    public static int run(Class<?> application, String[] args) {
        try (ConfigurableApplicationContext context = Cli.context(application)) {
            return run(args, context.getBean(RagMcpProperties.class), context.getBean(StepRegistry.class),
                    context.getBean(EmbeddingModels.class), System.out, System::getenv, Cli.serverVersion());
        } catch (RuntimeException e) {
            System.out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 1;
        }
    }

    static int run(String[] args, RagMcpProperties properties, StepRegistry registry, EmbeddingModels models,
                   PrintStream out, UnaryOperator<String> env, String serverVersion) {
        if (args.length != 1 || List.of("--help", "-h").contains(args[0])) {
            out.println("Usage: java -jar lucene-rag-server.jar rag-index <collection> | --all");
            out.println("Builds collections from their sources as described in collections.json.");
            return args.length == 1 ? 0 : 2;
        }
        final List<CollectionDefinition> definitions;
        final List<String> importProblems;
        try {
            LoadedCollections loaded = CollectionsLoader.read(properties, env);
            definitions = registry.checked(loaded.collections());
            importProblems = loaded.importProblems();
        } catch (IllegalStateException e) {
            out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 2;
        }
        final boolean all = "--all".equals(args[0]);
        final List<CollectionDefinition> selected;
        if (all) {
            selected = definitions;
            if (selected.isEmpty()) {
                importProblems.forEach(problem -> out.println("IMPORT: " + problem));
                out.println("RESULT: FAILED (no collections in " + properties.resolvedCollectionsFile() + ")");
                return 2;
            }
        } else {
            selected = definitions.stream().filter(d -> d.name().equals(args[0])).toList();
            if (selected.isEmpty()) {
                importProblems.forEach(problem -> out.println("IMPORT: " + problem));
                out.println("RESULT: FAILED (collection '" + args[0] + "' is not in "
                        + properties.resolvedCollectionsFile() + " or a file it imports; defined: "
                        + String.join(", ", definitions.stream().map(CollectionDefinition::name).toList()) + ")");
                return 2;
            }
        }
        for (CollectionDefinition definition : selected) {
            if (!definition.usable()) {
                out.println("RESULT: FAILED (collection '" + definition.name() + "': "
                        + definition.configError() + ")");
                return 2;
            }
        }
        for (CollectionDefinition definition : selected) {
            out.println("Collection " + definition.name());
            long start = System.nanoTime();
            try {
                CollectionIndexer.Built built = CollectionIndexer.build(definition, registry, models,
                        properties.resolvedDataDir(), serverVersion);
                Manifest manifest = built.manifest();
                out.println(String.format(Locale.ROOT, "  files %d, documents %d, records %d, %.1f s",
                        manifest.files(), manifest.documents(), manifest.records(),
                        (System.nanoTime() - start) / 1e9));
                if (manifest.vectors() != null) {
                    out.println(String.format(Locale.ROOT,
                            "  vectors: model %s, %d dimensions, %d embedded, %d from cache, %d cut to %d tokens",
                            manifest.vectors().model(), manifest.vectors().dimension(), built.embedded(),
                            built.cached(), manifest.vectors().truncated(), manifest.vectors().maxTokens()));
                    out.println("  model: " + (BeanModelType.NAME.equals(manifest.vectors().type())
                            ? "Spring AI bean " + manifest.vectors().model()
                            : "files in " + models.directory(manifest.vectors().model())));
                }
            } catch (IllegalArgumentException e) {
                out.println("RESULT: FAILED (" + e.getMessage() + ")");
                return 2;
            } catch (IOException e) {
                out.println("RESULT: FAILED (" + e.getMessage() + ")");
                return 4;
            }
        }
        String built = selected.size() + (selected.size() == 1 ? " collection" : " collections") + " built";
        importProblems.forEach(problem -> out.println("IMPORT: " + problem));
        if (all && !importProblems.isEmpty()) {
            // Everything was asked for and a part of it could not even be read.
            out.println("RESULT: FAILED (" + built + "; " + importProblems.size()
                    + (importProblems.size() == 1 ? " import problem" : " import problems") + ")");
            return 2;
        }
        out.println("RESULT: OK (" + built + ")");
        return 0;
    }
}
