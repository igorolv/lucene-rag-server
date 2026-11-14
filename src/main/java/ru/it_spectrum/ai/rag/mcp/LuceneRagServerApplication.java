package ru.it_spectrum.ai.rag.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import ru.it_spectrum.ai.rag.mcp.cli.RagIndexCommand;
import ru.it_spectrum.ai.rag.mcp.cli.RagSearchCommand;

import java.util.Arrays;

/**
 * Spring Boot entry point of the stdio MCP server. Empty apart from command dispatch.
 *
 * <p>The user-run {@code rag-index} and {@code rag-search} modes dispatch before Spring starts,
 * so they never open the stdio transport or wait for JSON-RPC on stdin.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LuceneRagServerApplication {

    static void main(String[] args) {
        if (args.length > 0 && RagIndexCommand.MODE.equals(args[0])) {
            System.exit(RagIndexCommand.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && RagSearchCommand.MODE.equals(args[0])) {
            System.exit(RagSearchCommand.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        SpringApplication.run(LuceneRagServerApplication.class, args);
    }
}
