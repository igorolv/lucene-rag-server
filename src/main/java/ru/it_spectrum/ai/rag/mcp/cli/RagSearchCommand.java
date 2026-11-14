package ru.it_spectrum.ai.rag.mcp.cli;

import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.index.CollectionStore;
import ru.it_spectrum.ai.rag.mcp.search.SearchHit;
import ru.it_spectrum.ai.rag.mcp.search.SearchPage;
import ru.it_spectrum.ai.rag.mcp.search.SearchService;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * User-run search over built collections, for looking something up without a model. Runs without
 * Spring and never opens the MCP transport. Uses the same search and the same limits as the
 * {@code search} tool.
 *
 * <p>Exit codes: 0 searched (also when nothing matched), 1 unexpected error, 2 usage or an
 * unknown collection or filter, 4 an index could not be read.
 */
public final class RagSearchCommand {

    public static final String MODE = "rag-search";

    private RagSearchCommand() {
    }

    public static int run(String[] args) {
        Cli.useCliLogging();
        try {
            return run(args, RagMcpProperties.fromEnvironment(System::getenv), System.out);
        } catch (RuntimeException e) {
            System.out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 1;
        }
    }

    static int run(String[] args, RagMcpProperties properties, PrintStream out) {
        Integer limit = null;
        Map<String, String> filters = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (List.of("--help", "-h").contains(arg)) {
                usage(out);
                return 0;
            }
            if (List.of("--limit", "--filter").contains(arg)) {
                if (i + 1 >= args.length) {
                    usage(out);
                    return 2;
                }
                String value = args[++i];
                if ("--limit".equals(arg)) {
                    try {
                        limit = Integer.valueOf(value);
                    } catch (NumberFormatException e) {
                        usage(out);
                        return 2;
                    }
                } else {
                    int eq = value.indexOf('=');
                    if (eq < 1) {
                        usage(out);
                        return 2;
                    }
                    filters.put(value.substring(0, eq), value.substring(eq + 1));
                }
            } else {
                positional.add(arg);
            }
        }
        if (positional.isEmpty() || (positional.size() == 1 && filters.isEmpty())) {
            usage(out);
            return 2;
        }
        List<String> collections = Arrays.stream(positional.getFirst().split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        String query = String.join(" ", positional.subList(1, positional.size()));
        final SearchPage page;
        try {
            page = new SearchService(new CollectionStore(properties.resolvedDataDir()), properties)
                    .search(collections, query, filters, limit, null);
        } catch (IllegalArgumentException e) {
            out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 2;
        } catch (IOException e) {
            out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 4;
        }
        int number = 0;
        for (SearchHit hit : page.hits()) {
            out.println(++number + ". [" + hit.collection() + "] " + hit.breadcrumb());
            out.println(String.format(Locale.ROOT, "   id: %s   source: %s   score: %.2f",
                    hit.id(), hit.source(), hit.score()));
            for (String line : hit.snippet().split("\n")) {
                if (!line.isBlank()) {
                    out.println("   " + line.strip());
                }
            }
            out.println();
        }
        out.println("RESULT: OK (" + page.total() + " found, " + page.hits().size() + " shown"
                + (page.truncated() ? ", cut by the response size cap" : "") + ")");
        return 0;
    }

    private static void usage(PrintStream out) {
        out.println("Usage: java -jar lucene-rag-server.jar rag-search <collection>[,<collection>...] <query>"
                + " [--limit N] [--filter name=value]...");
        out.println("Searches built collections. Words are optional, \"quoted words\" are a phrase.");
        out.println("With --filter the query may be omitted to list matching records.");
    }
}
