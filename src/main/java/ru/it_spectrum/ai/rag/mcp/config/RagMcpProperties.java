package ru.it_spectrum.ai.rag.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.UnaryOperator;

/**
 * Top-level server settings: where local data lives, where the collections file is and how much
 * one response may return.
 *
 * <p>Modeled on mail-mcp-server {@code config/MailMcpProperties} @ {@code 0.1.1}.
 *
 * @param dataDir         root of all local data; {@code ~/.lucene-rag-server} when blank. Each
 *                        collection gets {@code <data-dir>/<collection>/}, logs go to
 *                        {@code <data-dir>/logs/}
 * @param collectionsFile JSON file with the named collections; {@code <data-dir>/collections.json}
 *                        when blank
 * @param search          bounds on the number of results
 * @param response        bounds on the size of one response
 */
@ConfigurationProperties(prefix = "rag-mcp")
public record RagMcpProperties(String dataDir, String collectionsFile, Search search, Response response) {

    /** Number of results: used when a call names none, and the most a call may ask for. */
    public record Search(Integer defaultLimit, Integer maxLimit) { }

    /** Total characters of passage text in one response. */
    public record Response(Integer maxChars) { }

    public static final int DEFAULT_SEARCH_LIMIT = 10;
    public static final int DEFAULT_SEARCH_MAX_LIMIT = 50;
    public static final int DEFAULT_RESPONSE_MAX_CHARS = 20_000;

    @ConstructorBinding
    public RagMcpProperties { }

    public RagMcpProperties(String dataDir, String collectionsFile) {
        this(dataDir, collectionsFile, null, null);
    }

    /** Environment variable that overrides {@link #dataDir()}. */
    public static final String DATA_DIR_ENV = "RAG_MCP_DATA_DIR";

    /** Environment variable that overrides {@link #collectionsFile()}. */
    public static final String COLLECTIONS_FILE_ENV = "RAG_MCP_COLLECTIONS_FILE";

    public static final String SEARCH_DEFAULT_LIMIT_ENV = "RAG_MCP_SEARCH_DEFAULT_LIMIT";
    public static final String SEARCH_MAX_LIMIT_ENV = "RAG_MCP_SEARCH_MAX_LIMIT";
    public static final String RESPONSE_MAX_CHARS_ENV = "RAG_MCP_RESPONSE_MAX_CHARS";

    /**
     * Settings read straight from the environment, for user-run commands without Spring.
     * Mirrors the {@code rag-mcp.*} block of {@code application.yml}.
     *
     * @throws IllegalArgumentException when a numeric variable is not a whole number
     */
    public static RagMcpProperties fromEnvironment(UnaryOperator<String> env) {
        return new RagMcpProperties(env.apply(DATA_DIR_ENV), env.apply(COLLECTIONS_FILE_ENV),
                new Search(number(env, SEARCH_DEFAULT_LIMIT_ENV), number(env, SEARCH_MAX_LIMIT_ENV)),
                new Response(number(env, RESPONSE_MAX_CHARS_ENV)));
    }

    private static Integer number(UnaryOperator<String> env, String name) {
        String value = env.apply(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number");
        }
    }

    /** The most results one call may return; at least 1. */
    public int searchMaxLimit() {
        Integer value = search == null ? null : search.maxLimit();
        return value == null ? DEFAULT_SEARCH_MAX_LIMIT : Math.max(1, value);
    }

    /** Results returned when a call names no limit; never above {@link #searchMaxLimit()}. */
    public int searchDefaultLimit() {
        Integer value = search == null ? null : search.defaultLimit();
        return Math.min(searchMaxLimit(), value == null ? DEFAULT_SEARCH_LIMIT : Math.max(1, value));
    }

    /** Characters of passage text one response may carry; at least 500. */
    public int responseMaxChars() {
        Integer value = response == null ? null : response.maxChars();
        return value == null ? DEFAULT_RESPONSE_MAX_CHARS : Math.max(500, value);
    }

    public Path resolvedDataDir() {
        String dir = dataDir;
        if (dir == null || dir.isBlank()) {
            dir = System.getProperty("user.home", ".") + "/.lucene-rag-server";
        }
        return Paths.get(expandHome(dir.trim())).toAbsolutePath().normalize();
    }

    /** Path of the collections file: the configured one, or {@code <data-dir>/collections.json}. */
    public Path resolvedCollectionsFile() {
        if (collectionsFile == null || collectionsFile.isBlank()) {
            return resolvedDataDir().resolve("collections.json");
        }
        return Paths.get(expandHome(collectionsFile.trim())).toAbsolutePath().normalize();
    }

    private static String expandHome(String path) {
        return path.startsWith("~") ? System.getProperty("user.home", ".") + path.substring(1) : path;
    }
}
