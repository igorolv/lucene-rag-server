package ru.it_spectrum.ai.rag.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import ru.it_spectrum.ai.rag.mcp.cli.RagEvalCommand;
import ru.it_spectrum.ai.rag.mcp.cli.RagIndexCommand;
import ru.it_spectrum.ai.rag.mcp.cli.RagModelCommand;
import ru.it_spectrum.ai.rag.mcp.cli.RagSearchCommand;

import java.util.Arrays;

/**
 * Spring Boot entry point of the stdio MCP server. Empty apart from command dispatch.
 *
 * <p>The user-run {@code rag-index}, {@code rag-search}, {@code rag-eval} and {@code rag-model} modes
 * dispatch before the server starts, so they never open the stdio transport or wait for JSON-RPC
 * on stdin.
 * Each starts the application context without the MCP server (docs/decisions.md D42).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LuceneRagServerApplication {

    static void main(String[] args) {
        if (args.length > 0 && RagIndexCommand.MODE.equals(args[0])) {
            System.exit(RagIndexCommand.run(LuceneRagServerApplication.class,
                    Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && RagSearchCommand.MODE.equals(args[0])) {
            System.exit(RagSearchCommand.run(LuceneRagServerApplication.class,
                    Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && RagEvalCommand.MODE.equals(args[0])) {
            System.exit(RagEvalCommand.run(LuceneRagServerApplication.class,
                    Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && RagModelCommand.MODE.equals(args[0])) {
            System.exit(RagModelCommand.run(LuceneRagServerApplication.class,
                    Arrays.copyOfRange(args, 1, args.length)));
        }
        SpringApplication.run(LuceneRagServerApplication.class, args);
    }
}
