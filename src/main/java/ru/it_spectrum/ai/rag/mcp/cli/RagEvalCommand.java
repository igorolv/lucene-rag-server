package ru.it_spectrum.ai.rag.mcp.cli;

import org.springframework.context.ConfigurableApplicationContext;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.embedding.EmbeddingModels;
import ru.it_spectrum.ai.rag.mcp.embedding.ModelUnavailableException;
import ru.it_spectrum.ai.rag.mcp.eval.Evaluation;
import ru.it_spectrum.ai.rag.mcp.eval.QuestionSet;
import ru.it_spectrum.ai.rag.mcp.eval.References;
import ru.it_spectrum.ai.rag.mcp.index.IndexStore;
import ru.it_spectrum.ai.rag.mcp.search.SearchMode;
import ru.it_spectrum.ai.rag.mcp.search.SearchService;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Measures search on a question set (docs/plan.md section 4, stage 4): searches every question
 * in every mode and reports how high the records the question expects rank. Runs in the
 * application context without the MCP transport, like {@code rag-search} (D42), and searches as the {@code search} tool does except that the response
 * limits of the server do not apply.
 *
 * <p>Exit codes: 0 measured, 1 unexpected error, 2 usage, an unknown index or mode, or a
 * question file that cannot be read, 4 an index could not be read, 5 the embedding model a mode
 * needs is missing or is not the one the index was built with.
 */
public final class RagEvalCommand {

    public static final String MODE = "rag-eval";

    /** Records of each search looked at, unless {@code --limit} says otherwise. */
    static final int DEFAULT_DEPTH = 10;
    static final int MAX_DEPTH = 100;

    private RagEvalCommand() {
    }

    /** @param application the Spring Boot application class */
    public static int run(Class<?> application, String[] args) {
        try (ConfigurableApplicationContext context = Cli.context(application)) {
            return run(args, context.getBean(RagMcpProperties.class), context.getBean(EmbeddingModels.class),
                    System.out);
        } catch (RuntimeException e) {
            System.out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 1;
        }
    }

    static int run(String[] args, RagMcpProperties properties, PrintStream out) {
        try (EmbeddingModels models = EmbeddingModels.inProcess(properties.resolvedDataDir())) {
            return run(args, properties, models, out);
        }
    }

    /** @param models the models vector and hybrid searches embed questions with; loaded only then */
    static int run(String[] args, RagMcpProperties properties, EmbeddingModels models, PrintStream out) {
        int depth = DEFAULT_DEPTH;
        String modes = null;
        boolean details = false;
        Integer perDocument = null;
        Map<String, String> refs = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (List.of("--help", "-h").contains(arg)) {
                usage(out);
                return 0;
            }
            if ("--details".equals(arg)) {
                details = true;
            } else if (List.of("--limit", "--modes", "--ref", "--per-document").contains(arg)) {
                if (i + 1 >= args.length) {
                    usage(out);
                    return 2;
                }
                String value = args[++i];
                if ("--modes".equals(arg)) {
                    modes = value;
                } else if ("--per-document".equals(arg)) {
                    try {
                        perDocument = Integer.valueOf(value);
                    } catch (NumberFormatException e) {
                        usage(out);
                        return 2;
                    }
                } else if ("--ref".equals(arg)) {
                    int eq = value.indexOf('=');
                    if (eq < 1 || eq == value.length() - 1) {
                        usage(out);
                        return 2;
                    }
                    refs.put(value.substring(0, eq).strip(), value.substring(eq + 1).strip());
                } else {
                    try {
                        depth = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        usage(out);
                        return 2;
                    }
                    if (depth < 1 || depth > MAX_DEPTH) {
                        out.println("RESULT: FAILED (--limit must be from 1 to " + MAX_DEPTH + ")");
                        return 2;
                    }
                }
            } else {
                positional.add(arg);
            }
        }
        if (positional.size() != 2) {
            usage(out);
            return 2;
        }
        List<String> collections = Arrays.stream(positional.get(0).split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        Path file = Path.of(positional.get(1));
        // Every record of a page is looked at: no response size cap, and the depth as the page size.
        var unbounded = new RagMcpProperties(properties.dataDir(), properties.indexesFile(),
                new RagMcpProperties.Search(depth, depth), new RagMcpProperties.Response(Integer.MAX_VALUE));
        SearchService search = new SearchService(new IndexStore(properties.resolvedDataDir()), unbounded, models);
        final Evaluation.Report report;
        try {
            if (!Files.isRegularFile(file)) {
                throw new NoSuchFileException(file.toString());
            }
            List<QuestionSet.Question> questions = QuestionSet.read(file);
            List<SearchMode> chosen = modes(modes, search, collections);
            report = new Evaluation(search, new References(refs)).run(collections, questions, chosen, depth, perDocument);
        } catch (ModelUnavailableException e) {
            out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 5;
        } catch (IllegalArgumentException e) {
            out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 2;
        } catch (NoSuchFileException e) {
            out.println("RESULT: FAILED (question file " + e.getFile() + " does not exist)");
            return 2;
        } catch (IOException e) {
            out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 4;
        }
        print(report, details, out);
        return 0;
    }

    /** The modes asked for, or every mode the indexes have. */
    private static List<SearchMode> modes(String value, SearchService search, List<String> collections) {
        if (value == null || value.isBlank()) {
            List<String> withVectors = search.withVectors(collections);
            if (withVectors.isEmpty()) {
                return List.of(SearchMode.LEXICAL);
            }
            return withVectors.size() == new java.util.LinkedHashSet<>(collections).size()
                    ? List.of(SearchMode.LEXICAL, SearchMode.VECTOR, SearchMode.HYBRID)
                    : List.of(SearchMode.LEXICAL, SearchMode.HYBRID);
        }
        List<SearchMode> result = new ArrayList<>();
        for (String name : value.split(",")) {
            SearchMode mode = SearchMode.parse(name);
            if (mode != null && !result.contains(mode)) {
                result.add(mode);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("--modes names no mode");
        }
        // Checked before any search, so that a set whose references name nothing still fails here.
        List<String> withVectors = search.withVectors(collections);
        List<String> without = new java.util.LinkedHashSet<>(collections).stream()
                .filter(c -> !withVectors.contains(c)).toList();
        if (result.contains(SearchMode.VECTOR) && !without.isEmpty()) {
            throw new IllegalArgumentException(String.join(", ", without) + (without.size() == 1 ? " has" : " have")
                    + " no vectors; measure it with lexical or hybrid");
        }
        if (result.contains(SearchMode.HYBRID) && withVectors.isEmpty()) {
            throw new IllegalArgumentException(String.join(", ", without) + (without.size() == 1 ? " has" : " have")
                    + " no vectors; measure it with lexical");
        }
        return result;
    }

    private static void print(Evaluation.Report report, boolean details, PrintStream out) {
        List<Evaluation.Outcome> outcomes = report.outcomes();
        long measured = report.measured();
        out.println("Questions: " + outcomes.size() + " read, " + measured + " measured, "
                + (outcomes.size() - measured) + " left out (" + report.withoutReferences()
                + " without references, the others with no reference that names a record)");
        Map<String, Integer> unreachable = new TreeMap<>();
        outcomes.forEach(o -> o.unreachable().forEach(ref -> unreachable.merge(
                References.prefix(ref).isEmpty() ? "(no prefix)" : References.prefix(ref) + ":", 1, Integer::sum)));
        if (!unreachable.isEmpty()) {
            out.println("References that name no record: " + unreachable.values().stream().mapToInt(i -> i).sum()
                    + " (" + unreachable.entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                    .collect(Collectors.joining(", ")) + ")");
        }
        out.println();
        int depth = report.depth();
        out.println(String.format(Locale.ROOT, "%-8s %7s %7s %7s %6s %9s %9s", "mode", "@1", "@5", "@" + depth,
                "MRR", "recall@" + depth, "ms/query"));
        for (Evaluation.Measures m : report.measures()) {
            out.println(String.format(Locale.ROOT, "%-8s %7s %7s %7s %6.2f %9.2f %9.0f", m.mode().id(),
                    m.first() + "/" + m.measured(), m.top5() + "/" + m.measured(), m.top() + "/" + m.measured(),
                    m.mrr(), m.recall(), m.millis()));
        }
        if (details) {
            out.println();
            for (Evaluation.Outcome outcome : outcomes) {
                StringBuilder line = new StringBuilder(outcome.question().id()).append(':');
                if (outcome.reachable().isEmpty()) {
                    line.append(" not measured");
                } else {
                    outcome.ranks().forEach((mode, rank) -> line.append(' ').append(mode.id()).append(' ')
                            .append(rank == 0 ? "-" : String.valueOf(rank)));
                }
                if (!outcome.unreachable().isEmpty()) {
                    line.append("; names no record: ").append(String.join(", ", outcome.unreachable()));
                }
                out.println(line);
            }
        }
        out.println();
        out.println("RESULT: OK (" + measured + " questions measured in " + report.measures().size() + " mode"
                + (report.measures().size() == 1 ? "" : "s") + ", depth " + depth + ")");
    }

    private static void usage(PrintStream out) {
        out.println("Usage: java -jar lucene-rag-server.jar rag-eval <index>[,<index>...] <questions.jsonl>"
                + " [--limit N] [--modes lexical,vector,hybrid] [--ref prefix=field]... [--per-document N]"
                + " [--details]");
        out.println("Searches every question of a JSONL question set (\"question\", \"expected\", \"id\") and reports");
        out.println("how high the expected records rank in each mode. A reference is a document key, a record id,");
        out.println("a source path, or prefix:value matching a field; --ref maps a prefix to a field name.");
        out.println("--limit is the depth looked at (default " + DEFAULT_DEPTH + ", at most " + MAX_DEPTH
                + "); without --modes every mode the indexes have.");
    }
}
