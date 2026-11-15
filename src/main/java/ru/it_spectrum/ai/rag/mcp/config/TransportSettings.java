package ru.it_spectrum.ai.rag.mcp.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Locale;
import java.util.Map;

/**
 * The MCP transport of the server, {@code rag-mcp.transport} ({@code RAG_MCP_TRANSPORT}):
 * {@value #STDIO}, the default, or {@value #HTTP}, the streamable HTTP transport of the Spring AI
 * MCP server starter (docs/decisions.md D45). One setting, because the transport decides the
 * Spring settings that go with it: whether a web server starts and whether stdio is read.
 *
 * <p>Runs after {@code application.yml} is read and overrides it. A user-run command switches the
 * MCP server off and is left alone: it never starts a web server.
 */
public final class TransportSettings implements EnvironmentPostProcessor, Ordered {

    public static final String PROPERTY = "rag-mcp.transport";
    public static final String STDIO = "stdio";
    public static final String HTTP = "http";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if ("false".equalsIgnoreCase(environment.getProperty("spring.ai.mcp.server.enabled"))) {
            return;
        }
        String transport = environment.getProperty(PROPERTY, STDIO).strip().toLowerCase(Locale.ROOT);
        Map<String, Object> settings = switch (transport) {
            case STDIO, "" -> Map.of("spring.main.web-application-type", "none",
                    "spring.ai.mcp.server.stdio", "true");
            case HTTP -> Map.of("spring.main.web-application-type", "servlet",
                    "spring.ai.mcp.server.stdio", "false",
                    "spring.ai.mcp.server.protocol", "streamable");
            default -> throw new IllegalStateException("RAG_MCP_TRANSPORT must be " + STDIO + " or " + HTTP
                    + ", not '" + transport + "'");
        };
        environment.getPropertySources().addFirst(new MapPropertySource("rag-mcp-transport", settings));
    }

    /** After the configuration files are read. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
