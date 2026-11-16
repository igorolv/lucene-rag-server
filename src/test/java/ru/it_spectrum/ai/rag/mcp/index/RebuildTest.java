package ru.it_spectrum.ai.rag.mcp.index;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.TestKnowledge;
import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.IndexesLoader;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.it_spectrum.ai.rag.mcp.TestKnowledge.DOCS;

/** A rebuild replaces a collection whole or not at all. */
class RebuildTest {

    @TempDir
    Path dataDir;

    private IndexDefinition docs() throws IOException {
        List<IndexDefinition> all = IndexesLoader.load(TestKnowledge.writeCollectionsFile(dataDir),
                name -> null);
        return all.stream().filter(d -> d.name().equals(DOCS)).findFirst().orElseThrow();
    }

    private Manifest build() throws IOException {
        return IndexBuilder.build(docs(), StepRegistry.builtIn(), dataDir, "test");
    }

    private List<String> hidden() throws IOException {
        try (Stream<Path> children = Files.list(dataDir)) {
            return children.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(".")).toList();
        }
    }

    @Test
    void aRebuildReplacesTheCollectionAndLeavesNothingAside() throws IOException {
        Manifest first = build();
        Manifest second = build();

        assertThat(new IndexStore(dataDir).manifest(DOCS)).contains(second);
        assertThat(second.records()).isEqualTo(first.records());
        assertThat(hidden()).isEmpty();
    }

    @Test
    void oldDirectoriesOfEarlierBuildsAreRemoved() throws IOException {
        build();
        Files.createDirectories(dataDir.resolve(".old-" + DOCS + "-1").resolve("index"));

        build();

        assertThat(hidden()).isEmpty();
    }

    @Test
    void aRebuildWhileTheIndexIsOpenEitherReplacesItOrKeepsItWhole() throws IOException {
        Manifest first = build();
        IndexStore store = new IndexStore(dataDir);
        try (var directory = FSDirectory.open(store.indexDir(DOCS));
             var reader = DirectoryReader.open(directory)) {
            try {
                build();
            } catch (IOException e) {
                // Windows: the open index cannot be renamed, so the build fails and the old one stays.
                assertThat(e).hasMessageContaining("is in use and was kept");
            }
            assertThat(reader.numDocs()).isEqualTo(first.records());
        }
        assertThat(store.manifest(DOCS)).isPresent();
        try (var directory = FSDirectory.open(store.indexDir(DOCS));
             var reader = DirectoryReader.open(directory)) {
            assertThat(reader.numDocs()).isEqualTo(first.records());
        }
        assertThat(hidden()).allMatch(name -> name.startsWith(".old-"));
    }
}
