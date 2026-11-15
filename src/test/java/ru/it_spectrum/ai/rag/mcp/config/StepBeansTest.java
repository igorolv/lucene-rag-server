package ru.it_spectrum.ai.rag.mcp.config;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.rag.mcp.embedding.BeanModelType;
import ru.it_spectrum.ai.rag.mcp.embedding.EmbeddingModels;
import ru.it_spectrum.ai.rag.mcp.embedding.ModelParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry bean of the application holds the same steps as {@link StepRegistry#builtIn()},
 * which the unit tests use: a step added as a bean and forgotten there, or the reverse, fails
 * here. The context is started the way {@code rag-index} starts it, without the MCP server.
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.server.enabled=false",
        "spring.ai.mcp.server.annotation-scanner.enabled=false",
        "logging.config=classpath:logback-cli.xml"})
class StepBeansTest {

    @Autowired
    StepRegistry registry;

    @Autowired
    EmbeddingModels models;

    @Test
    void theContextRegistersEveryBuiltInStep() {
        StepRegistry builtIn = StepRegistry.builtIn();

        assertThat(registry.origins()).isEqualTo(builtIn.origins());
        assertThat(registry.converters()).isEqualTo(builtIn.converters());
        assertThat(registry.formats()).isEqualTo(builtIn.formats());
    }

    @Test
    void theContextRegistersEveryModelType() {
        assertThat(models.types()).containsExactly(BeanModelType.NAME, ModelParameters.ONNX);
    }
}
