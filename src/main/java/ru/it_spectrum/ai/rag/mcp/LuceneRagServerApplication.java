package ru.it_spectrum.ai.rag.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entry point of the stdio MCP server. Empty by design.
 *
 * <p>User-run commands ({@code rag-index}, {@code rag-search}; docs/plan.md section 4) will
 * dispatch here before Spring starts, so they never open the stdio transport.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LuceneRagServerApplication {

    static void main(String[] args) {
        SpringApplication.run(LuceneRagServerApplication.class, args);
    }
}
