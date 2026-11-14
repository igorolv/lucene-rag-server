package ru.it_spectrum.ai.rag.mcp.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.it_spectrum.ai.rag.mcp.collections.CollectionNames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The built collections under the data directory. A collection is served when its directory has a
 * readable manifest of the current schema whose name equals the directory name; nothing else is
 * consulted, so a directory copied from another machine is served as it is.
 *
 * <p>Nothing is cached: the directory is listed and an index is opened per call. Indexes are
 * small and a rebuild may replace a collection at any time.
 */
public final class CollectionStore {

    private static final Logger log = LoggerFactory.getLogger(CollectionStore.class);

    private final Path dataDir;

    public CollectionStore(Path dataDir) {
        this.dataDir = dataDir;
    }

    /** Built collections, by name. */
    public List<Manifest> built() {
        if (!Files.isDirectory(dataDir)) {
            return List.of();
        }
        List<Manifest> result = new ArrayList<>();
        try (Stream<Path> children = Files.list(dataDir)) {
            for (Path dir : (Iterable<Path>) children.filter(Files::isDirectory)::iterator) {
                String name = dir.getFileName().toString();
                if (CollectionNames.valid(name) && Files.isRegularFile(dir.resolve(Manifest.FILE_NAME))) {
                    manifest(name).ifPresent(result::add);
                }
            }
        } catch (IOException e) {
            log.warn("Cannot list the data directory {}: {}", dataDir, e.getMessage());
        }
        result.sort(Comparator.comparing(Manifest::name));
        return result;
    }

    /** The manifest of one built collection, or empty when it is absent or cannot be served. */
    public Optional<Manifest> manifest(String name) {
        if (!CollectionNames.valid(name)) {
            return Optional.empty();
        }
        Path dir = dataDir.resolve(name);
        if (!Files.isRegularFile(dir.resolve(Manifest.FILE_NAME))) {
            return Optional.empty();
        }
        try {
            Manifest manifest = Manifest.read(dir);
            if (!name.equals(manifest.name())) {
                log.warn("Collection directory {} holds the manifest of '{}'; skipped", dir, manifest.name());
                return Optional.empty();
            }
            if (manifest.schema() != Manifest.SCHEMA) {
                log.warn("Collection '{}' has index schema {}, this server reads {}; rebuild it",
                        name, manifest.schema(), Manifest.SCHEMA);
                return Optional.empty();
            }
            return Optional.of(manifest);
        } catch (IOException e) {
            log.warn("Collection '{}' is skipped: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    /** Directory of the Lucene index of a collection. */
    public Path indexDir(String name) {
        return dataDir.resolve(name).resolve(Manifest.INDEX_DIR);
    }
}
