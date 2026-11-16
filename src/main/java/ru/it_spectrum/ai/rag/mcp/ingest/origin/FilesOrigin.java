package ru.it_spectrum.ai.rag.mcp.ingest.origin;

import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.rag.mcp.definition.SourceDefinition;
import ru.it_spectrum.ai.rag.mcp.ingest.Origin;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceItem;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lists the files of one source in a stable order: the origin of a source with a {@code path}.
 *
 * <p>Globs are matched against the path relative to the source root. Unlike a plain Java glob, a
 * pattern starting with {@code **}{@code /} also matches files directly in the root, as it does
 * in {@code .gitignore} and most build tools.
 */
@Component
public final class FilesOrigin implements Origin {

    public static final String NAME = "files";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<SourceItem> items(SourceDefinition source, String defaultInclude) throws IOException {
        Path root = source.path();
        if (Files.isRegularFile(root)) {
            return List.of(SourceItem.ofFile(root, root.getFileName().toString()));
        }
        if (!Files.isDirectory(root)) {
            throw new IOException("Source path does not exist: " + root);
        }
        List<PathMatcher> include = matchers(source.include() == null ? defaultInclude : source.include());
        List<PathMatcher> exclude = source.exclude() == null ? List.of() : matchers(source.exclude());
        List<SourceItem> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                Path relative = root.relativize(file);
                if (matches(include, relative) && !matches(exclude, relative)) {
                    result.add(SourceItem.ofFile(file, relative.toString().replace('\\', '/')));
                }
            }
        }
        result.sort((a, b) -> a.path().compareTo(b.path()));
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
