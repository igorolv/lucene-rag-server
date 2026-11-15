package ru.it_spectrum.ai.rag.mcp.cli;

import org.springframework.context.ConfigurableApplicationContext;
import ru.it_spectrum.ai.rag.mcp.collections.CollectionsLoader;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.embedding.EmbeddingModels;
import ru.it_spectrum.ai.rag.mcp.embedding.HuggingFace;
import ru.it_spectrum.ai.rag.mcp.embedding.ModelCatalog;
import ru.it_spectrum.ai.rag.mcp.embedding.ModelParameters;
import ru.it_spectrum.ai.rag.mcp.embedding.OnnxEmbeddingModel;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * User-run management of in-process embedding models (docs/decisions.md D70, D71): {@code list}
 * shows the models of {@link ModelCatalog} and whether their files are in place; {@code fetch}
 * downloads the files of a model of the catalog, or of any Hugging Face repository with an ONNX
 * export ({@code --repo}), into {@code <data-dir>/models/<name>/}. The server itself never
 * downloads; this command does when the operator runs it.
 *
 * <p>A file is written to a temporary name next to its place and moved there only when its size and
 * hash are the published ones; a file already in place is not downloaded again. The directory gets
 * {@value OnnxEmbeddingModel#SOURCE_FILE}, which records the repository, the revision and the
 * files. The JVM's proxy settings apply, and {@code --from} names a mirror that serves the same
 * paths as Hugging Face.
 *
 * <p>Exit codes: 0 done, 1 unexpected error, 2 usage or an unknown model, 4 a download failed, did
 * not match or could not be written, or the repository has no usable export.
 */
public final class RagModelCommand {

    public static final String MODE = "rag-model";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Set<String> OPTIONS = Set.of("--repo", "--revision", "--file", "--tokenizer", "--from");

    private RagModelCommand() {
    }

    /**
     * @param application the Spring Boot application class; passed in because this package must
     *                    not depend on the root package
     */
    public static int run(Class<?> application, String[] args) {
        try (ConfigurableApplicationContext context = Cli.context(application)) {
            return run(args, context.getBean(RagMcpProperties.class), System.out);
        } catch (RuntimeException e) {
            System.out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 1;
        }
    }

    static int run(String[] args, RagMcpProperties properties, PrintStream out) {
        Path models = properties.resolvedDataDir().resolve(EmbeddingModels.MODELS_DIR);
        if (args.length == 1 && args[0].equals("list")) {
            return list(models, out);
        }
        if (args.length >= 2 && args[0].equals("fetch") && !args[1].startsWith("--")) {
            Map<String, String> options = new HashMap<>();
            for (int i = 2; i < args.length; i += 2) {
                if (i + 1 >= args.length || !OPTIONS.contains(args[i]) || options.put(args[i], args[i + 1]) != null) {
                    return usage(out, 2);
                }
            }
            return fetch(args[1], options, models, out);
        }
        return usage(out, args.length == 1 && (args[0].equals("--help") || args[0].equals("-h")) ? 0 : 2);
    }

    private static int usage(PrintStream out, int code) {
        out.println("Usage: java -jar lucene-rag-server.jar rag-model list");
        out.println("       java -jar lucene-rag-server.jar rag-model fetch <model> [--from <base-url>]");
        out.println("       java -jar lucene-rag-server.jar rag-model fetch <name> --repo <owner/name>"
                + " [--revision <rev>] [--file <path>] [--tokenizer <path>] [--from <base-url>]");
        out.println("Lists the embedding models of the catalog, or downloads one, or the ONNX export of a");
        out.println("Hugging Face repository, into <data-dir>/models/<name>/.");
        return code;
    }

    private static int list(Path models, PrintStream out) {
        for (ModelCatalog.Model model : ModelCatalog.MODELS) {
            out.println(String.format(Locale.ROOT, "%-10s %4d dimensions, %s, %s license, %s", model.name(),
                    model.dimension(), megabytes(model.size()), model.license(), state(models.resolve(model.name()),
                            model)));
            out.println("           " + model.description() + "; " + model.repository());
        }
        out.println("RESULT: OK (" + ModelCatalog.MODELS.size() + " models)");
        return 0;
    }

    /** Whether the files of a model are in place, by their sizes; {@code fetch} checks the hashes. */
    private static String state(Path dir, ModelCatalog.Model model) {
        int present = 0;
        for (ModelCatalog.File file : model.files()) {
            Path path = dir.resolve(file.name());
            try {
                if (Files.isRegularFile(path) && Files.size(path) == file.size()) {
                    present++;
                }
            } catch (IOException e) {
                return "unreadable in " + dir;
            }
        }
        return present == model.files().size() ? "in " + dir
                : present == 0 && !Files.exists(dir) ? "not fetched" : "incomplete in " + dir;
    }

    private static int fetch(String name, Map<String, String> options, Path models, PrintStream out) {
        String from = options.getOrDefault("--from", ModelCatalog.HUGGING_FACE);
        String repository = options.get("--repo");
        if (!CollectionsLoader.validModelName(name)) {
            out.println("RESULT: FAILED (model name '" + name + "' must be letters, digits, '.', '_' or '-', up to 64"
                    + " characters, not starting with a dot)");
            return 2;
        }
        if (repository == null) {
            var known = ModelCatalog.find(name);
            if (known.isEmpty()) {
                out.println("RESULT: FAILED (unknown model '" + name + "'; known: "
                        + String.join(", ", ModelCatalog.MODELS.stream().map(ModelCatalog.Model::name).toList())
                        + "; for another model, name its Hugging Face repository with --repo)");
                return 2;
            }
            if (options.size() > (options.containsKey("--from") ? 1 : 0)) {
                return usage(out, 2);
            }
            ModelCatalog.Model model = known.get();
            out.println("Model " + model.name() + " (" + model.repository() + ", " + model.license() + " license)");
            return fetch(name, ModelCatalog.source(model), model.collectionsEntry(), false, models, from, http(), out);
        }
        out.println("Model " + name + " (" + repository + ")");
        HttpClient http = http();
        final HuggingFace.Source source;
        try {
            source = HuggingFace.resolve(http, from, repository, options.get("--revision"), options.get("--file"),
                    options.get("--tokenizer"));
        } catch (IOException e) {
            out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 4;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.println("RESULT: FAILED (interrupted)");
            return 4;
        }
        out.println("  revision " + source.revision());
        return fetch(name, source, entry(name, source.pooling()), true, models, from, http, out);
    }

    /**
     * @param entry    the entry of {@code models} to print
     * @param prefixes whether to remind that the prefixes come from the model card
     */
    static int fetch(String name, HuggingFace.Source source, String entry, boolean prefixes, Path models,
                     String from, HttpClient http, PrintStream out) {
        Path dir = models.resolve(name);
        try {
            Files.createDirectories(dir);
            for (ModelCatalog.File file : source.files()) {
                Path target = dir.resolve(file.name());
                if (HuggingFace.inPlace(file, target)) {
                    out.println("  " + file.name() + ": already there");
                    continue;
                }
                long start = System.nanoTime();
                HuggingFace.download(http, HuggingFace.url(from, source.repository(), source.revision(), file.path()),
                        file, target);
                out.println(String.format(Locale.ROOT, "  %s: %s in %.0f s, %s verified", file.name(),
                        megabytes(file.size()), (System.nanoTime() - start) / 1e9,
                        file.sha256() != null ? "SHA-256" : "git object id"));
            }
            writeSource(dir, source);
        } catch (IOException e) {
            out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 4;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.println("RESULT: FAILED (interrupted)");
            return 4;
        }
        out.println("  files in " + dir);
        if (source.pooling() == null) {
            out.println("  pooling: the repository does not say; mean is assumed, check the model card");
        } else if (source.pooling().startsWith("!")) {
            out.println("  pooling: the repository uses " + source.pooling().substring(1)
                    + " pooling, which this server does not support; its vectors would not be the model's");
        } else if (prefixes) {
            out.println("  pooling: " + source.pooling() + ", as the repository says");
        }
        out.println("  in collections.json, under \"models\":");
        out.println("    " + entry);
        if (prefixes) {
            out.println("  set queryPrefix and passagePrefix as the model card of " + source.repository() + " says");
        }
        out.println("RESULT: OK (" + name + " fetched)");
        return 0;
    }

    /** The entry of {@code models} for a model of a repository; prefixes are left for the operator. */
    static String entry(String name, String pooling) {
        return "\"" + name + "\": { \"type\": \"onnx\", \"queryPrefix\": \"\", \"passagePrefix\": \"\", "
                + "\"maxTokens\": 512" + (ModelParameters.CLS.equals(pooling) ? ", \"pooling\": \"cls\"" : "") + " }";
    }

    private static void writeSource(Path dir, HuggingFace.Source source) throws IOException {
        ObjectNode root = JSON.createObjectNode().put("repository", source.repository())
                .put("revision", source.revision());
        if (source.pooling() != null) {
            root.put("pooling", source.pooling());
        }
        ObjectNode files = root.putObject("files");
        for (ModelCatalog.File file : source.files()) {
            ObjectNode node = files.putObject(file.name()).put("path", file.path()).put("size", file.size());
            if (file.sha256() != null) {
                node.put("sha256", file.sha256());
            } else {
                node.put("gitBlob", file.gitBlob());
            }
        }
        Files.writeString(dir.resolve(OnnxEmbeddingModel.SOURCE_FILE),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
    }

    static HttpClient http() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault())
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0);
    }
}
