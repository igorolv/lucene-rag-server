package ru.it_spectrum.ai.rag.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Top-level server settings: where local data lives, where the indexes file is and how much
 * one response may return.
 *
 * <p>Modeled on mail-mcp-server {@code config/MailMcpProperties} @ {@code 0.1.1}.
 *
 * @param dataDir         root of all local data; {@code ~/.lucene-rag-server} when blank. Each
 *                        index gets {@code <data-dir>/<index>/}, logs go to
 *                        {@code <data-dir>/logs/}
 * @param indexesFile     JSON or YAML file with the named indexes; {@code <data-dir>/indexes.json}
 *                        when blank
 * @param search          bounds on the number of results
 * @param response        bounds on the size of one response
 */
@ConfigurationProperties(prefix = "rag-mcp")
public record RagMcpProperties(String dataDir, String indexesFile, Search search, Response response) {

    /** Number of results: used when a call names none, and the most a call may ask for. */
    public record Search(Integer defaultLimit, Integer maxLimit) { }

    /** Total characters of passage text in one response. */
    public record Response(Integer maxChars) { }

    public static final int DEFAULT_SEARCH_LIMIT = 10;
    public static final int DEFAULT_SEARCH_MAX_LIMIT = 50;
    public static final int DEFAULT_RESPONSE_MAX_CHARS = 20_000;

    @ConstructorBinding
    public RagMcpProperties { }

    public RagMcpProperties(String dataDir, String indexesFile) {
        this(dataDir, indexesFile, null, null);
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

    /**
     * Path of the indexes file: the configured one, else {@code <data-dir>/indexes.json},
     * else {@code indexes.yaml} or {@code indexes.yml} there when that one exists.
     *
     * @throws IllegalStateException when the data directory holds more than one of them
     */
    public Path resolvedIndexesFile() {
        if (indexesFile == null || indexesFile.isBlank()) {
            Path dataDir = resolvedDataDir();
            List<Path> present = new ArrayList<>();
            for (String name : List.of("indexes.json", "indexes.yaml", "indexes.yml")) {
                if (Files.isRegularFile(dataDir.resolve(name))) {
                    present.add(dataDir.resolve(name));
                }
            }
            if (present.size() > 1) {
                throw new IllegalStateException("The data directory " + dataDir + " has "
                        + String.join(" and ", present.stream().map(p -> p.getFileName().toString()).toList())
                        + "; keep one indexes file");
            }
            return present.isEmpty() ? dataDir.resolve("indexes.json") : present.getFirst();
        }
        return Paths.get(expandHome(indexesFile.trim())).toAbsolutePath().normalize();
    }

    /**
     * The advice to rename an indexes file of the old name: present when no indexes file is
     * configured, the data directory has none and holds {@code collections.json}, {@code .yaml} or
     * {@code .yml} instead (docs/decisions.md D90). Such a file is not read.
     */
    public Optional<String> oldFileNotice() {
        if (indexesFile != null && !indexesFile.isBlank()) {
            return Optional.empty();
        }
        Path dataDir = resolvedDataDir();
        for (String extension : List.of("json", "yaml", "yml")) {
            if (Files.isRegularFile(dataDir.resolve("indexes." + extension))) {
                return Optional.empty();
            }
        }
        for (String extension : List.of("json", "yaml", "yml")) {
            Path old = dataDir.resolve("collections." + extension);
            if (Files.isRegularFile(old)) {
                return Optional.of(old + " is not read: rename it to indexes." + extension
                        + " and its key \"collections\" to \"indexes\"");
            }
        }
        return Optional.empty();
    }

    private static String expandHome(String path) {
        return path.startsWith("~") ? System.getProperty("user.home", ".") + path.substring(1) : path;
    }
}
