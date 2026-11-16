package ru.it_spectrum.ai.rag.mcp;

import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.IndexesLoader;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.index.IndexBuilder;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Builds indexes from the synthetic knowledge under {@code testdata/} into a temporary data directory. */
public final class TestKnowledge {

    public static final String DOCS = "docs@billing";
    public static final String API = "api@billing";

    private TestKnowledge() {
    }

    public static Path root() {
        return Paths.get("testdata", "knowledge").toAbsolutePath();
    }

    /** Writes an indexes file for {@link #DOCS} (Russian articles and playbooks) and {@link #API}. */
    public static Path writeIndexesFile(Path dataDir) throws IOException {
        return writeIndexesFile(dataDir, false);
    }

    /**
     * @param vectors {@link #DOCS} gets vectors of the model {@code fake} of type
     *                {@code FakeEmbeddingModel.TYPE}, with E5 prefixes; {@link #API} stays lexical
     */
    public static Path writeIndexesFile(Path dataDir, boolean vectors) throws IOException {
        String root = root().toString().replace('\\', '/');
        Path file = dataDir.resolve("indexes.json");
        Files.createDirectories(dataDir);
        String models = vectors ? """
                "models": {"fake": {"type": "fake", "queryPrefix": "query: ", "passagePrefix": "passage: "}},
                """ : "";
        String docsVectors = vectors ? "\"vectors\": {\"model\": \"fake\"}," : "";
        Files.writeString(file, """
                {%s"indexes": {
                  "%s": {
                    "description": "Billing: articles and playbooks",
                    %s
                    "sources": [
                      {"path": "%s/articles"},
                      {"path": "%s/playbooks"}
                    ]
                  },
                  "%s": {
                    "description": "Billing: API notes",
                    "sources": [{"path": "%s/api"}]
                  }
                }}
                """.formatted(models, DOCS, docsVectors, root, root, API, root));
        return file;
    }

    /** Builds both indexes and returns settings pointing at {@code dataDir}. */
    public static RagMcpProperties build(Path dataDir) throws IOException {
        Path file = writeIndexesFile(dataDir);
        List<IndexDefinition> definitions = IndexesLoader.load(file, name -> null);
        for (IndexDefinition definition : definitions) {
            IndexBuilder.build(definition, StepRegistry.builtIn(), dataDir, "test");
        }
        return new RagMcpProperties(dataDir.toString(), null);
    }
}
