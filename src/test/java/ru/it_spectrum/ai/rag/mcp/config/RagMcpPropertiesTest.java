package ru.it_spectrum.ai.rag.mcp.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void environmentOverridesPathsAndLimits() {
        Map<String, String> env = Map.of(
                RagMcpProperties.DATA_DIR_ENV, "~/rag",
                RagMcpProperties.COLLECTIONS_FILE_ENV, "conf/collections.json",
                RagMcpProperties.SEARCH_DEFAULT_LIMIT_ENV, "3",
                RagMcpProperties.SEARCH_MAX_LIMIT_ENV, " 5 ",
                RagMcpProperties.RESPONSE_MAX_CHARS_ENV, "4000");

        RagMcpProperties properties = RagMcpProperties.fromEnvironment(env::get);

        assertThat(properties.resolvedDataDir()).isEqualTo(Paths.get(System.getProperty("user.home"), "rag"));
        assertThat(properties.resolvedCollectionsFile())
                .isEqualTo(Paths.get("conf", "collections.json").toAbsolutePath());
        assertThat(properties.searchDefaultLimit()).isEqualTo(3);
        assertThat(properties.searchMaxLimit()).isEqualTo(5);
        assertThat(properties.responseMaxChars()).isEqualTo(4000);
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

    @Test
    void aLimitThatIsNotANumberIsRejected() {
        Map<String, String> env = Map.of(RagMcpProperties.SEARCH_MAX_LIMIT_ENV, "many");

        assertThatThrownBy(() -> RagMcpProperties.fromEnvironment(env::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(RagMcpProperties.SEARCH_MAX_LIMIT_ENV);
    }
}
