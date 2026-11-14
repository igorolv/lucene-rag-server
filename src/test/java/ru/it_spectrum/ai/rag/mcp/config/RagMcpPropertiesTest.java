package ru.it_spectrum.ai.rag.mcp.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagMcpPropertiesTest {

    @Test
    void blankSettingsFallBackToTheHomeDataDirectory() {
        RagMcpProperties properties = new RagMcpProperties(" ", null);

        Path home = Paths.get(System.getProperty("user.home"));
        assertThat(properties.resolvedDataDir()).isEqualTo(home.resolve(".lucene-rag-server"));
        assertThat(properties.resolvedCollectionsFile())
                .isEqualTo(home.resolve(".lucene-rag-server").resolve("collections.json"));
    }

    @Test
    void collectionDirectoryIsNamedAfterTheCollection() {
        RagMcpProperties properties = new RagMcpProperties("data", null);

        assertThat(properties.collectionDir("docs@billing")).isEqualTo(Paths.get("data", "docs@billing"));
    }

    @Test
    void environmentOverridesBothPaths() {
        Map<String, String> env = Map.of(
                RagMcpProperties.DATA_DIR_ENV, "~/rag",
                RagMcpProperties.COLLECTIONS_FILE_ENV, "conf/collections.json");

        RagMcpProperties properties = RagMcpProperties.fromEnvironment(env::get);

        assertThat(properties.resolvedDataDir()).isEqualTo(Paths.get(System.getProperty("user.home"), "rag"));
        assertThat(properties.resolvedCollectionsFile()).isEqualTo(Paths.get("conf", "collections.json"));
    }
}
