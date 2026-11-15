package ru.it_spectrum.ai.rag.mcp.cli;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** What the user-run commands share. */
final class Cli {

    /**
     * Lucene reports through java.util.logging that the incubating Vector API is not enabled. It
     * only matters for vector search speed and would be printed on every command run. Held in a
     * field because java.util.logging keeps loggers weakly.
     */
    private static final java.util.logging.Logger LUCENE_VECTORIZATION =
            java.util.logging.Logger.getLogger("org.apache.lucene.internal.vectorization.VectorizationProvider");

    private Cli() {
    }

    /**
     * The application context of a command (docs/decisions.md D42): the beans the server has,
     * models of every type among them, with the MCP server switched off, so the stdio transport is
     * never opened and stdout carries only the report. Logging goes to stderr only
     * ({@code logback-cli.xml}).
     *
     * @param application the Spring Boot application class; passed in because this package must
     *                    not depend on the root package
     */
    static ConfigurableApplicationContext context(Class<?> application) {
        quietLucene();
        return new SpringApplicationBuilder(application)
                .properties("spring.ai.mcp.server.enabled=false",
                        "spring.ai.mcp.server.annotation-scanner.enabled=false",
                        "logging.config=classpath:logback-cli.xml")
                .logStartupInfo(false)
                .run();
    }

    static void quietLucene() {
        LUCENE_VECTORIZATION.setLevel(java.util.logging.Level.SEVERE);
    }

    /** Version from the jar manifest; {@code dev} when run from classes. */
    static String serverVersion() {
        String version = Cli.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }
}
