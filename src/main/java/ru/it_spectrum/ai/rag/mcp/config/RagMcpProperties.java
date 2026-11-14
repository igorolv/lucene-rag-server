package ru.it_spectrum.ai.rag.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.UnaryOperator;

/**
 * Top-level server settings: where local data lives and where the collections file is.
 *
 * <p>Modeled on mail-mcp-server {@code config/MailMcpProperties} @ {@code 0.1.1}.
 *
 * @param dataDir         root of all local data; {@code ~/.lucene-rag-server} when blank. Each
 *                        collection gets {@code <data-dir>/<collection>/}, logs go to
 *                        {@code <data-dir>/logs/}
 * @param collectionsFile JSON file with the named collections; {@code <data-dir>/collections.json}
 *                        when blank
 */
@ConfigurationProperties(prefix = "rag-mcp")
public record RagMcpProperties(String dataDir, String collectionsFile) {

    /** Environment variable that overrides {@link #dataDir()}. */
    public static final String DATA_DIR_ENV = "RAG_MCP_DATA_DIR";

    /** Environment variable that overrides {@link #collectionsFile()}. */
    public static final String COLLECTIONS_FILE_ENV = "RAG_MCP_COLLECTIONS_FILE";

    /**
     * Settings read straight from the environment, for user-run commands without Spring.
     * Mirrors the {@code rag-mcp.*} block of {@code application.yml}.
     */
    public static RagMcpProperties fromEnvironment(UnaryOperator<String> env) {
        return new RagMcpProperties(env.apply(DATA_DIR_ENV), env.apply(COLLECTIONS_FILE_ENV));
    }

    public Path resolvedDataDir() {
        String dir = dataDir;
        if (dir == null || dir.isBlank()) {
            dir = System.getProperty("user.home", ".") + "/.lucene-rag-server";
        }
        return Paths.get(expandHome(dir.trim())).normalize();
    }

    /** Path of the collections file: the configured one, or {@code <data-dir>/collections.json}. */
    public Path resolvedCollectionsFile() {
        if (collectionsFile == null || collectionsFile.isBlank()) {
            return resolvedDataDir().resolve("collections.json");
        }
        return Paths.get(expandHome(collectionsFile.trim())).normalize();
    }

    /** Local data of one collection: {@code <data-dir>/<collection>}. */
    public Path collectionDir(String collection) {
        return resolvedDataDir().resolve(collection);
    }

    private static String expandHome(String path) {
        return path.startsWith("~") ? System.getProperty("user.home", ".") + path.substring(1) : path;
    }
}
