package ru.it_spectrum.ai.rag.mcp.source;

import ru.it_spectrum.ai.rag.mcp.collections.SourceDefinition;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lists the files of one source in a stable order.
 *
 * <p>Globs are matched against the path relative to the source root. Unlike a plain Java glob, a
 * pattern starting with {@code **}{@code /} also matches files directly in the root, as it does
 * in {@code .gitignore} and most build tools.
 */
public final class SourceScanner {

    public static final String DEFAULT_MARKDOWN_INCLUDE = "**/*.md";

    private SourceScanner() {
    }

    /** One file of a source: where it is and its {@code /}-separated path relative to the root. */
    public record SourceFile(Path file, String relativePath) { }

    public static List<SourceFile> files(SourceDefinition source) throws IOException {
        Path root = source.path();
        if (Files.isRegularFile(root)) {
            return List.of(new SourceFile(root, root.getFileName().toString()));
        }
        if (!Files.isDirectory(root)) {
            throw new IOException("Source path does not exist: " + root);
        }
        List<PathMatcher> include = matchers(source.include() == null ? DEFAULT_MARKDOWN_INCLUDE : source.include());
        List<PathMatcher> exclude = source.exclude() == null ? List.of() : matchers(source.exclude());
        List<SourceFile> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                Path relative = root.relativize(file);
                if (matches(include, relative) && !matches(exclude, relative)) {
                    result.add(new SourceFile(file, relative.toString().replace('\\', '/')));
                }
            }
        }
        result.sort((a, b) -> a.relativePath().compareTo(b.relativePath()));
        return result;
    }

    private static List<PathMatcher> matchers(String glob) {
        List<PathMatcher> matchers = new ArrayList<>();
        matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
        if (glob.startsWith("**/")) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3)));
        }
        return matchers;
    }

    private static boolean matches(List<PathMatcher> matchers, Path path) {
        return matchers.stream().anyMatch(m -> m.matches(path));
    }
}
