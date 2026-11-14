package ru.it_spectrum.ai.rag.mcp;

import ru.it_spectrum.ai.rag.mcp.collections.CollectionDefinition;
import ru.it_spectrum.ai.rag.mcp.collections.CollectionsLoader;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.index.CollectionIndexer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Builds collections from the synthetic knowledge under {@code testdata/} into a temporary data directory. */
public final class TestKnowledge {

    public static final String DOCS = "docs@billing";
    public static final String API = "api@billing";

    private TestKnowledge() {
    }

    public static Path root() {
        return Paths.get("testdata", "knowledge").toAbsolutePath();
    }

    /** Writes a collections file for {@link #DOCS} (Russian articles and playbooks) and {@link #API}. */
    public static Path writeCollectionsFile(Path dataDir) throws IOException {
        String root = root().toString().replace('\\', '/');
        Path file = dataDir.resolve("collections.json");
        Files.createDirectories(dataDir);
        Files.writeString(file, """
                {"collections": {
                  "%s": {
                    "description": "Billing: articles and playbooks",
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
                """.formatted(DOCS, root, root, API, root));
        return file;
    }

    /** Builds both collections and returns settings pointing at {@code dataDir}. */
    public static RagMcpProperties build(Path dataDir) throws IOException {
        Path file = writeCollectionsFile(dataDir);
        List<CollectionDefinition> definitions = CollectionsLoader.load(file, name -> null);
        for (CollectionDefinition definition : definitions) {
            CollectionIndexer.build(definition, dataDir, "test");
        }
        return new RagMcpProperties(dataDir.toString(), null);
    }
}
