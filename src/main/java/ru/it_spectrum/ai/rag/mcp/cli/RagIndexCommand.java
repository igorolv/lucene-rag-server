package ru.it_spectrum.ai.rag.mcp.cli;

import org.springframework.context.ConfigurableApplicationContext;
import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.IndexesLoader;
import ru.it_spectrum.ai.rag.mcp.definition.LoadedIndexes;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.embedding.EmbeddingModels;
import ru.it_spectrum.ai.rag.mcp.embedding.BeanModelType;
import ru.it_spectrum.ai.rag.mcp.index.IndexBuilder;
import ru.it_spectrum.ai.rag.mcp.index.IndexPreview;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;
import ru.it_spectrum.ai.rag.mcp.ingest.ChunkRecord;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * User-run build of indexes from their sources. It starts the application context to get the
 * same step beans the server has, with the MCP server switched off, so the stdio transport is
 * never opened and stdout carries only the report (docs/decisions.md D19).
 *
 * <p>With {@code --preview} it reads the sources and prints what the index would hold, without
 * embedding or writing anything ({@link IndexPreview}, docs/decisions.md D93).
 *
 * <p>Exit codes: 0 built or previewed, 1 unexpected error, 2 usage or configuration, 4 a source
 * could not be read or the index could not be written.
 */
public final class RagIndexCommand {

    public static final String MODE = "rag-index";

    /** Records of each source a preview prints in full when {@code --records} is not given. */
    static final int DEFAULT_SAMPLES = 3;
    static final int MAX_SAMPLES = 50;
    /** Characters of a record's text a preview prints. */
    static final int SAMPLE_CHARS = 300;

    private RagIndexCommand() {
    }

    /**
     * @param application the Spring Boot application class; passed in because this package must
     *                    not depend on the root package
     */
    public static int run(Class<?> application, String[] args) {
        try (ConfigurableApplicationContext context = Cli.context(application)) {
            return run(args, context.getBean(RagMcpProperties.class), context.getBean(StepRegistry.class),
                    context.getBean(EmbeddingModels.class), System.out, System::getenv, Cli.serverVersion());
        } catch (RuntimeException e) {
            System.out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 1;
        }
    }

    static int run(String[] args, RagMcpProperties properties, StepRegistry registry, EmbeddingModels models,
                   PrintStream out, UnaryOperator<String> env, String serverVersion) {
        if (args.length == 1 && List.of("--help", "-h").contains(args[0])) {
            usage(out);
            return 0;
        }
        boolean preview = false;
        int samples = DEFAULT_SAMPLES;
        boolean samplesGiven = false;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--preview".equals(args[i])) {
                preview = true;
            } else if ("--records".equals(args[i]) && i + 1 < args.length) {
                samplesGiven = true;
                try {
                    samples = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    samples = -1;
                }
            } else {
                names.add(args[i]);
            }
        }
        if (names.size() != 1 || names.get(0).startsWith("-") && !"--all".equals(names.get(0))
                || samplesGiven && !preview || samples < 0 || samples > MAX_SAMPLES) {
            usage(out);
            return 2;
        }
        final String name = names.get(0);
        properties.oldFileNotice().ifPresent(notice -> out.println("NOTICE: " + notice));
        final List<IndexDefinition> definitions;
        final List<String> importProblems;
        try {
            LoadedIndexes loaded = IndexesLoader.read(properties, env);
            definitions = registry.checked(loaded.indexes());
            importProblems = loaded.importProblems();
            loaded.warnings().forEach(warning -> out.println("WARNING: " + warning));
        } catch (IllegalStateException e) {
            out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 2;
        }
        final boolean all = "--all".equals(name);
        final List<IndexDefinition> selected;
        if (all) {
            selected = definitions;
            if (selected.isEmpty()) {
                importProblems.forEach(problem -> out.println("IMPORT: " + problem));
                out.println("RESULT: FAILED (no indexes in " + properties.resolvedIndexesFile() + ")");
                return 2;
            }
        } else {
            selected = definitions.stream().filter(d -> d.name().equals(name)).toList();
            if (selected.isEmpty()) {
                importProblems.forEach(problem -> out.println("IMPORT: " + problem));
                out.println("RESULT: FAILED (index '" + name + "' is not in "
                        + properties.resolvedIndexesFile() + " or a file it imports; defined: "
                        + String.join(", ", definitions.stream().map(IndexDefinition::name).toList()) + ")");
                return 2;
            }
        }
        for (IndexDefinition definition : selected) {
            if (!definition.usable()) {
                out.println("RESULT: FAILED (index '" + definition.name() + "': "
                        + definition.configError() + ")");
                return 2;
            }
        }
        if (preview) {
            return preview(selected, importProblems, registry, samples, out);
        }
        for (IndexDefinition definition : selected) {
            out.println("Index " + definition.name());
            long start = System.nanoTime();
            try {
                IndexBuilder.Built built = IndexBuilder.build(definition, registry, models,
                        properties.resolvedDataDir(), serverVersion);
                Manifest manifest = built.manifest();
                out.println(String.format(Locale.ROOT, "  files %d, documents %d, records %d, %.1f s",
                        manifest.files(), manifest.documents(), manifest.records(),
                        (System.nanoTime() - start) / 1e9));
                if (manifest.vectors() != null) {
                    out.println(String.format(Locale.ROOT,
                            "  vectors: model %s, %d dimensions, %d embedded, %d from cache, %d cut to %d tokens",
                            manifest.vectors().model(), manifest.vectors().dimension(), built.embedded(),
                            built.cached(), manifest.vectors().truncated(), manifest.vectors().maxTokens()));
                    out.println("  model: " + (BeanModelType.NAME.equals(manifest.vectors().type())
                            ? "Spring AI bean " + manifest.vectors().model()
                            : "files in " + models.directory(manifest.vectors().model())));
                }
            } catch (IllegalArgumentException e) {
                out.println("RESULT: FAILED (" + e.getMessage() + ")");
                return 2;
            } catch (IOException e) {
                out.println("RESULT: FAILED (" + e.getMessage() + ")");
                return 4;
            }
        }
        String built = selected.size() + (selected.size() == 1 ? " index" : " indexes") + " built";
        importProblems.forEach(problem -> out.println("IMPORT: " + problem));
        if (all && !importProblems.isEmpty()) {
            // Everything was asked for and a part of it could not even be read.
            out.println("RESULT: FAILED (" + built + "; " + importProblems.size()
                    + (importProblems.size() == 1 ? " import problem" : " import problems") + ")");
            return 2;
        }
        out.println("RESULT: OK (" + built + ")");
        return 0;
    }

    private static void usage(PrintStream out) {
        out.println("Usage: java -jar lucene-rag-server.jar rag-index <index> | --all");
        out.println("       java -jar lucene-rag-server.jar rag-index --preview [--records N] <index> | --all");
        out.println("Builds indexes from their sources as described in indexes.json. With --preview, reads");
        out.println("the sources and prints what an index would hold, N records of each source in full");
        out.println("(default " + DEFAULT_SAMPLES + ", at most " + MAX_SAMPLES
                + "), without embedding or writing anything.");
    }

    private static int preview(List<IndexDefinition> selected, List<String> importProblems, StepRegistry registry,
                               int samples, PrintStream out) {
        for (IndexDefinition definition : selected) {
            out.println("Preview " + definition.name() + (definition.project() == null ? ""
                    : ", project " + definition.project()) + ": nothing is embedded or written");
            final IndexPreview.Result result;
            try {
                result = IndexPreview.preview(definition, registry, samples);
            } catch (IllegalArgumentException e) {
                out.println("RESULT: FAILED (" + e.getMessage() + ")");
                return 2;
            } catch (IOException e) {
                out.println("RESULT: FAILED (" + e.getMessage() + ")");
                return 4;
            }
            int number = 0;
            for (IndexPreview.Source source : result.sources()) {
                number++;
                out.println("  source " + number + ": " + source.label() + " (" + source.format() + ")");
                out.println(String.format(Locale.ROOT,
                        "    files %d, documents %d, records %d, longest record %d characters",
                        source.files(), source.documents(), source.records(), source.longest()));
                out.println("    fields: " + (source.fields().isEmpty() ? "none" : fields(source)));
                for (ChunkRecord record : source.samples()) {
                    printRecord(record, out);
                }
            }
            out.println(String.format(Locale.ROOT, "  total: files %d, documents %d, records %d",
                    result.files(), result.documents(), result.records()));
        }
        importProblems.forEach(problem -> out.println("IMPORT: " + problem));
        String previewed = selected.size() + (selected.size() == 1 ? " index" : " indexes") + " previewed";
        out.println("RESULT: OK (" + previewed + "; nothing written)");
        return 0;
    }

    private static String fields(IndexPreview.Source source) {
        List<String> parts = new ArrayList<>();
        for (var field : source.fields().entrySet()) {
            if (field.getValue() == null) {
                parts.add(field.getKey() + ": " + source.distinct().get(field.getKey()) + " values");
            } else {
                List<String> values = new ArrayList<>();
                field.getValue().forEach((value, records) -> values.add(value + " " + records));
                parts.add(field.getKey() + ": " + String.join(", ", values));
            }
        }
        return String.join("; ", parts);
    }

    private static void printRecord(ChunkRecord record, PrintStream out) {
        out.println("    record " + record.id());
        out.println("      document: " + record.document());
        out.println("      title: " + record.title());
        out.println("      breadcrumb: " + record.breadcrumb());
        if (!record.fields().isEmpty()) {
            List<String> fields = new ArrayList<>();
            for (Map.Entry<String, List<String>> field : record.fields().entrySet()) {
                fields.add(field.getKey() + "=" + String.join(", ", field.getValue()));
            }
            out.println("      fields: " + String.join("; ", fields));
        }
        String text = record.text().strip();
        boolean cut = text.length() > SAMPLE_CHARS;
        if (cut) {
            text = text.substring(0, SAMPLE_CHARS);
        }
        String[] lines = text.split("\\R", -1);
        out.println("      text, " + record.text().length() + " characters:");
        for (int i = 0; i < lines.length; i++) {
            out.println("        " + lines[i] + (cut && i == lines.length - 1 ? " …" : ""));
        }
    }
}
