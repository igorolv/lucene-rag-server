package ru.it_spectrum.ai.rag.mcp.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

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
