package ru.it_spectrum.ai.rag.mcp.cli;

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

    /** Commands report on stdout, so logging goes to stderr only ({@code logback-cli.xml}). */
    static void useCliLogging() {
        if (System.getProperty("logback.configurationFile") == null) {
            System.setProperty("logback.configurationFile", "logback-cli.xml");
        }
        LUCENE_VECTORIZATION.setLevel(java.util.logging.Level.SEVERE);
    }

    /** Version from the jar manifest; {@code dev} when run from classes. */
    static String serverVersion() {
        String version = Cli.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }
}
