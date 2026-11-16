package ru.it_spectrum.ai.rag.mcp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stdio MCP uses a single stdout stream. Keeping synchronous tool execution immediate prevents
 * concurrent boundedElastic tool completions from racing while enqueueing responses (MCP Java SDK
 * issue #686). A slow tool call therefore blocks later calls of the same session — long work such
 * as rebuilding an index must run in the background and return at once.
 *
 * <p>Copied from mail-mcp-server {@code config/McpServerConfig} @ {@code 0.1.1}.
 */
@Configuration
public class McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);

    /** Stdio only: the HTTP transport has a customizer of its own and no single stream to protect. */
    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "stdio", havingValue = "true")
    McpSyncServerCustomizer stdioSyncServerCustomizer() {
        return serverBuilder -> {
            log.info("Applying MCP sync server customization: immediateExecution=true");
            serverBuilder.immediateExecution(true);
        };
    }
}
