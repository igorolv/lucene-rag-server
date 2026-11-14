package ru.it_spectrum.ai.rag.mcp.ingest.exec;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.collections.CollectionDefinition;
import ru.it_spectrum.ai.rag.mcp.collections.CollectionsLoader;
import ru.it_spectrum.ai.rag.mcp.index.CollectionIndexer;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;
import ru.it_spectrum.ai.rag.mcp.ingest.ChunkRecord;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceItem;
import ru.it_spectrum.ai.rag.mcp.ingest.SourcePipeline;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The command origin and the command converter, with {@code java} running a one-file program as
 * the external command: the tests need no program that is not already there.
 */
class CommandStepsTest {

    /** The program the commands run; its first argument selects what it does. */
    private static final String TOOL = """
            import java.io.PrintStream;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.nio.file.Path;

            public class Tool {
                public static void main(String[] args) throws Exception {
                    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
                    switch (args[0]) {
                        case "jsonl" -> {
                            out.println("{\\"id\\": \\"h-1\\", \\"text\\": \\"Справка по платежам\\"}");
                            out.println("{\\"id\\": \\"h-2\\", \\"text\\": \\"Справка по возвратам\\"}");
                        }
                        case "describe" -> {
                            Path file = Path.of(args[1]);
                            out.print("# " + file.getFileName() + "\\n\\nSize: " + Files.size(file) + " bytes\\n");
                        }
                        case "append" -> out.print(new String(System.in.readAllBytes(), StandardCharsets.UTF_8)
                                + "\\n" + args[1] + "\\n");
                        case "fail" -> {
                            System.err.println("the input is broken");
                            System.exit(3);
                        }
                        case "sleep" -> Thread.sleep(60_000);
                        default -> throw new IllegalArgumentException(args[0]);
                    }
                }
            }
            """;

    @TempDir
    static Path dir;

    private static String java;

    @BeforeAll
    static void writeTool() throws IOException {
        Files.writeString(dir.resolve("Tool.java"), TOOL);
        Files.createDirectories(dir.resolve("files"));
        Files.write(dir.resolve("files/a.bin"), new byte[]{(byte) 0xFF, (byte) 0xFE, 0, 1});
        Files.write(dir.resolve("files/b.bin"), new byte[]{(byte) 0xC3, 0x28});
        java = Path.of(System.getProperty("java.home"), "bin", "java").toString().replace('\\', '/');
    }

    /** A command running the tool, as a JSON array; the tool is found in the working directory. */
    private static String tool(String... args) {
        StringBuilder json = new StringBuilder("[\"" + java + "\", \"Tool.java\"");
        for (String arg : args) {
            json.append(", \"").append(arg).append('"');
        }
        return json.append(']').toString();
    }

    private static List<CollectionDefinition> load(String sources) throws IOException {
        Path file = dir.resolve("collections.json");
        Files.writeString(file, "{\"collections\": {\"c\": {\"sources\": [" + sources + "]}}}");
        return StepRegistry.builtIn().checked(CollectionsLoader.load(file, name -> null));
    }

    private static List<ChunkRecord> records(String source) throws IOException {
        CollectionDefinition definition = load(source).getFirst();
        assertThat(definition.configError()).isNull();
        SourcePipeline pipeline = StepRegistry.builtIn().pipeline(definition.sources().getFirst());
        List<ChunkRecord> records = new ArrayList<>();
        for (SourceItem item : pipeline.items()) {
            records.addAll(pipeline.records(item));
        }
        return records;
    }

    private static String error(String source) throws IOException {
        CollectionDefinition definition = load(source).getFirst();
        assertThat(definition.usable()).isFalse();
        return definition.configError();
    }

    @Test
    void theOutputOfACommandIsTheOnlyItemOfItsSource() throws IOException {
        List<ChunkRecord> records = records("{\"command\": " + tool("jsonl") + ", \"timeoutSeconds\": 120,"
                + " \"format\": \"jsonl\", \"map\": {\"document\": \"help\", \"text\": \"{record.text}\"}}");

        assertThat(records).extracting(ChunkRecord::id).containsExactly("help#h-1", "help#h-2");
        assertThat(records.getFirst().text()).isEqualTo("Справка по платежам");
        assertThat(records.getFirst().source()).endsWith("Tool.java jsonl");
    }

    @Test
    void aConverterWithFileReadsTheFileAndTheNextOneReadsTheTextOnStandardInput() throws IOException {
        List<ChunkRecord> records = records("{\"path\": \"files\", \"include\": \"**/*.bin\", \"format\": \"markdown\","
                + " \"convert\": [{\"command\": " + tool("describe", "{file}") + ", \"timeoutSeconds\": 120},"
                + "               {\"command\": " + tool("append", "Converted.") + ", \"timeoutSeconds\": 120}]}");

        assertThat(records).extracting(ChunkRecord::title).containsExactly("a.bin", "b.bin");
        assertThat(records.getFirst().text()).isEqualTo("Size: 4 bytes\n\nConverted.");
        assertThat(records.getFirst().source()).isEqualTo("a.bin");
    }

    @Test
    void aFailingCommandReportsItsExitCodeAndErrorOutput() throws IOException {
        SourcePipeline pipeline = StepRegistry.builtIn().pipeline(
                load("{\"command\": " + tool("fail") + ", \"timeoutSeconds\": 120}").getFirst().sources().getFirst());

        assertThatThrownBy(pipeline::items).isInstanceOf(IOException.class)
                .hasMessageContaining("exited with code 3", "the input is broken");
    }

    @Test
    void aCommandThatRunsTooLongIsStopped() throws IOException {
        SourcePipeline pipeline = StepRegistry.builtIn().pipeline(
                load("{\"command\": " + tool("sleep") + ", \"timeoutSeconds\": 1}").getFirst().sources().getFirst());

        assertThatThrownBy(pipeline::items).isInstanceOf(IOException.class)
                .hasMessageContaining("did not finish in 1 seconds");
    }

    @Test
    void aProgramThatDoesNotExistIsAnErrorOfTheBuild() throws IOException {
        SourcePipeline pipeline = StepRegistry.builtIn().pipeline(
                load("{\"command\": [\"no-such-program-for-rag-tests\"]}").getFirst().sources().getFirst());

        assertThatThrownBy(pipeline::items).isInstanceOf(IOException.class)
                .hasMessageContaining("Cannot run the command no-such-program-for-rag-tests");
    }

    @Test
    void commandsThatDoNotFitAreConfigurationErrors() throws IOException {
        assertThat(error("{\"path\": \"files\", \"command\": [\"x\"]}")).contains("either \"path\" or \"command\"");
        assertThat(error("{\"format\": \"jsonl\"}")).contains("\"path\" or \"command\" is required");
        assertThat(error("{\"command\": \"x\"}")).contains("command must be a non-empty array of strings");
        assertThat(error("{\"command\": []}")).contains("command must be a non-empty array");
        assertThat(error("{\"command\": [\"x\", 1]}")).contains("command must be a non-empty array of strings");
        assertThat(error("{\"command\": [\"x\"], \"timeoutSeconds\": 0}")).contains("timeoutSeconds must be at least 1");
        assertThat(error("{\"command\": [\"x\"], \"timeoutSeconds\": \"5\"}")).contains("timeoutSeconds must be a whole");
        assertThat(error("{\"path\": \"files\", \"convert\": {\"command\": [\"x\"]}}"))
                .contains("\"convert\" must be an array");
        assertThat(error("{\"path\": \"files\", \"convert\": [{\"pandoc\": [\"x\"]}]}"))
                .contains("sources[0]: convert[0] must name exactly one converter", "supported: command");
        assertThat(error("{\"path\": \"files\", \"convert\": [{\"command\": []}]}"))
                .contains("convert[0]: command must be a non-empty array");
        assertThat(error("{\"command\": [\"x\"], \"convert\": [{\"command\": [\"y\", \"{file}\"]}]}"))
                .contains("convert[0] reads the file of an item", "has no files");
    }

    @Test
    void aReaderMayHoldTheConvertSteps() throws IOException {
        Path file = dir.resolve("collections.json");
        Files.writeString(file, "{\"readers\": {\"described\": {\"format\": \"markdown\", \"convert\": [{\"command\": "
                + tool("describe", "{file}") + "}]}},"
                + " \"collections\": {\"c\": {\"sources\": [{\"path\": \"files\", \"include\": \"**/*.bin\","
                + " \"reader\": \"described\"}]}}}");
        CollectionDefinition definition = StepRegistry.builtIn().checked(CollectionsLoader.load(file, n -> null))
                .getFirst();

        assertThat(definition.sources().getFirst().convert()).hasSize(1);
        assertThat(definition.sources().getFirst().convert().getFirst().settings())
                .containsKey("command").doesNotContainKey("timeoutSeconds");
        assertThat(definition.sources().getFirst().convert().getFirst().directory()).isEqualTo(dir.toAbsolutePath());
    }

    @Test
    void theManifestKeepsTheCommandAndTheConvertSteps(@TempDir Path dataDir) throws IOException {
        CollectionDefinition definition = load("{\"command\": " + tool("jsonl") + ", \"timeoutSeconds\": 120,"
                + " \"format\": \"jsonl\", \"map\": {\"document\": \"help\", \"text\": \"{record.text}\"}}").getFirst();

        Manifest manifest = CollectionIndexer.build(definition, StepRegistry.builtIn(), dataDir, "test");

        assertThat(manifest.files()).isEqualTo(1);
        assertThat(manifest.records()).isEqualTo(2);
        Manifest.Source source = Manifest.read(dataDir.resolve("c")).sources().getFirst();
        assertThat(source.path()).isNull();
        assertThat(source.command()).endsWith("Tool.java", "jsonl");
        assertThat(source.timeoutSeconds()).isEqualTo(120);
        assertThat(source.convert()).isEmpty();
        assertThat(source.format()).isEqualTo("jsonl");
    }
}
