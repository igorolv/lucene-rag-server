package ru.it_spectrum.ai.rag.mcp.ingest;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.rag.mcp.collections.MapSettings;
import ru.it_spectrum.ai.rag.mcp.collections.SourceDefinition;
import ru.it_spectrum.ai.rag.mcp.collections.SplitSettings;
import ru.it_spectrum.ai.rag.mcp.ingest.format.MarkdownFormat;
import ru.it_spectrum.ai.rag.mcp.ingest.map.TemplateMapper;
import ru.it_spectrum.ai.rag.mcp.ingest.origin.FilesOrigin;
import ru.it_spectrum.ai.rag.mcp.ingest.split.HeadingSplitter;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepRegistryTest {

    private static SourceDefinition source(String path, String include, String format) {
        return new SourceDefinition(Path.of(path), include, null, format, SplitSettings.DEFAULT, MapSettings.DEFAULT);
    }

    @Test
    void theFormatIsTheNamedOneOrFollowsTheExtensionOrIsMarkdown() {
        StepRegistry registry = StepRegistry.builtIn();

        assertThat(registry.pipeline(source("kb", null, "markdown")).format()).isEqualTo("markdown");
        assertThat(registry.pipeline(source("kb", "**/*.markdown", null)).format()).isEqualTo("markdown");
        assertThat(registry.pipeline(source("kb/notes.md", null, null)).format()).isEqualTo("markdown");
        assertThat(registry.pipeline(source("kb.v2/notes", "**/*.txt", null)).format()).isEqualTo("markdown");
    }

    @Test
    void anUnknownFormatNamesTheSupportedOnes() {
        assertThatThrownBy(() -> StepRegistry.builtIn().pipeline(source("kb", null, "docbook")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"docbook\" is not supported")
                .hasMessageContaining("markdown");
    }

    @Test
    void twoStepsOfOneNameAreRefused() {
        assertThatThrownBy(() -> new StepRegistry(List.of(new FilesOrigin()), List.of(),
                List.of(new MarkdownFormat(), new MarkdownFormat()), new HeadingSplitter(), new TemplateMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("format", "markdown");
    }

    @Test
    void theNamesOfTheStepsAreListed() {
        StepRegistry registry = StepRegistry.builtIn();

        assertThat(registry.origins()).isEqualTo(Set.of("files"));
        assertThat(registry.converters()).isEmpty();
        assertThat(registry.formats()).isEqualTo(Set.of("markdown"));
    }
}
