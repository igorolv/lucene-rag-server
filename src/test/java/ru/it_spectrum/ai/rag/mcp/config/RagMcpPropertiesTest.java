package ru.it_spectrum.ai.rag.mcp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class RagMcpPropertiesTest {

    @Test
    void blankSettingsFallBackToTheHomeDataDirectory() {
        RagMcpProperties properties = new RagMcpProperties(" ", null);

        Path home = Paths.get(System.getProperty("user.home"));
        assertThat(properties.resolvedDataDir()).isEqualTo(home.resolve(".lucene-rag-server"));
        // The file name depends on which indexes file the home data directory holds.
        assertThat(properties.resolvedIndexesFile().getParent()).isEqualTo(home.resolve(".lucene-rag-server"));
        assertThat(properties.resolvedIndexesFile().getFileName().toString())
                .isIn("indexes.json", "indexes.yaml", "indexes.yml");
    }

    @Test
    void aCollectionsFileWithoutAnIndexesFileIsReported(@TempDir Path dataDir) throws IOException {
        RagMcpProperties properties = new RagMcpProperties(dataDir.toString(), null);
        assertThat(properties.oldFileNotice()).isEmpty();

        Files.writeString(dataDir.resolve("collections.yaml"), "collections: {}");
        assertThat(properties.oldFileNotice()).hasValueSatisfying(notice -> assertThat(notice)
                .contains("collections.yaml is not read", "rename it to indexes.yaml", "\"indexes\""));
        assertThat(new RagMcpProperties(dataDir.toString(), dataDir.resolve("x.json").toString())
                .oldFileNotice()).isEmpty();

        Files.writeString(dataDir.resolve("indexes.json"), "{}");
        assertThat(properties.oldFileNotice()).isEmpty();
    }

    @Test
    void limitsHaveDefaultsAndStayConsistent() {
        RagMcpProperties defaults = new RagMcpProperties(null, null);
        assertThat(defaults.searchDefaultLimit()).isEqualTo(10);
        assertThat(defaults.searchMaxLimit()).isEqualTo(50);
        assertThat(defaults.responseMaxChars()).isEqualTo(20_000);

        RagMcpProperties odd = new RagMcpProperties(null, null,
                new RagMcpProperties.Search(40, 5), new RagMcpProperties.Response(1));
        assertThat(odd.searchDefaultLimit()).isEqualTo(5);
        assertThat(odd.responseMaxChars()).isEqualTo(500);
    }
}
