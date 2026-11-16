package ru.it_spectrum.ai.rag.mcp.cli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;
import ru.it_spectrum.ai.rag.mcp.search.LookupTestData;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LookupCommandTest {

    @TempDir
    static Path dataDir;

    static RagMcpProperties properties;

    @BeforeAll
    static void build() throws IOException {
        Path file = LookupTestData.writeIndexesFile(dataDir);
        properties = new RagMcpProperties(dataDir.toString(), file.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = RagIndexCommand.run(new String[]{"--all"}, properties, StepRegistry.builtIn(), null,
                new PrintStream(out, true, StandardCharsets.UTF_8), name -> null, "test");
        assertThat(code).as(out.toString(StandardCharsets.UTF_8)).isZero();
    }

    private static String search(int expectedCode, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = RagSearchCommand.run(args, properties, new PrintStream(out, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(code).as(text).isEqualTo(expectedCode);
        return text;
    }

    @Test
    void lookupPrintsTheRecordsWithTheValue() {
        assertThat(search(0, LookupTestData.CATALOGS, "--lookup", "2003"))
                .contains("Очередь разбора", "RESULT: OK (1 found, 1 shown)");
        assertThat(search(0, LookupTestData.CATALOGS, "--lookup", "nothing-like-this"))
                .contains("RESULT: OK (0 found, 0 shown)");
    }

    @Test
    void lookupTakesNoQueryOrFiltersAndNeedsKeys() {
        assertThat(search(2, LookupTestData.CATALOGS, "words", "--lookup", "2003")).contains("Usage:");
        assertThat(search(2, LookupTestData.CATALOGS, "--lookup", "2003", "--filter", "id=2003")).contains("Usage:");
        assertThat(search(2, LookupTestData.NOTES, "--lookup", "2003")).contains("has no key fields");
    }
}
