package ru.it_spectrum.ai.rag.mcp.definition;

import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The keys of an indexes file that the loader does not read, as warnings (docs/decisions.md D95).
 * A key that is not known is ignored, as before; the warning names it, where it is and the known
 * key it is nearest to, so that a misspelled {@code exlude} does not pass unseen.
 *
 * <p>Only the keys this version defines are checked. Names the user chooses are not keys: the
 * names of indexes, models, readers, lookups and variables, and the field names of
 * {@code map.fields} and {@code where}. The settings of a {@code convert} step name their
 * converter, which the registry of steps decides on, and are not checked here.
 */
final class UnknownKeys {

    static final Set<String> MAIN = Set.of("vars", "models", "indexes", "readers", "import");
    static final Set<String> IMPORTED = Set.of("indexes", "readers", "project");
    static final Set<String> IMPORT_ENTRY = Set.of("file", "commands");
    static final Set<String> MODEL = Set.of("type", "queryPrefix", "passagePrefix", "maxTokens", "default", "pooling");
    static final Set<String> INDEX = Set.of("description", "project", "vectors", "sources");
    static final Set<String> VECTORS = Set.of("model");
    static final Set<String> SOURCE = Set.of("path", "include", "exclude", "format", "split", "map", "command",
            "timeoutSeconds", "convert", "join", "where", "reader");
    static final Set<String> SPLIT = Set.of("level", "headingPattern", "maxChars", "minChars", "list", "tableRows",
            "outline", "selector");
    static final Set<String> MAP = Set.of("pathPattern", "bodyPattern", "document", "title", "breadcrumb", "text",
            "id", "fields", "keys", "inheritFields");
    static final Set<String> LOOKUP = Set.of("path", "command", "include", "exclude", "timeoutSeconds", "convert",
            "format", "split", "key", "on");

    private final String file;
    private final List<String> warnings = new ArrayList<>();

    private UnknownKeys(Path file) {
        this.file = file.toString();
    }

    /**
     * @param root     the object an indexes file holds
     * @param imported whether the file is imported, which allows other keys at its top
     * @return one warning per unknown key, in the order of the file
     */
    static List<String> of(JsonNode root, Path file, boolean imported) {
        UnknownKeys check = new UnknownKeys(file);
        check.object(root, "", imported ? IMPORTED : MAIN);
        check.named(root.get("models"), "models", MODEL, (model, where) -> { });
        check.named(root.get("readers"), "readers", Set.copyOf(IndexesLoader.READER_KEYS), check::reading);
        JsonNode imports = root.get("import");
        if (!imported && imports != null && imports.isArray()) {
            int i = 0;
            for (JsonNode entry : imports) {
                check.object(entry, "import[" + i++ + "]", IMPORT_ENTRY);
            }
        }
        check.named(root.get("indexes"), "indexes", INDEX, check::index);
        return List.copyOf(check.warnings);
    }

    private interface Inner {
        void check(JsonNode node, String where);
    }

    /** Every entry of an object of names the user chose, each checked against {@code known}. */
    private void named(JsonNode node, String where, Set<String> known, Inner inner) {
        if (node == null || !node.isObject()) {
            return;
        }
        for (var entry : node.properties()) {
            String path = where + "." + entry.getKey();
            object(entry.getValue(), path, known);
            if (entry.getValue().isObject()) {
                inner.check(entry.getValue(), path);
            }
        }
    }

    private void index(JsonNode node, String where) {
        JsonNode vectors = node.get("vectors");
        object(vectors, where + ".vectors", VECTORS);
        JsonNode sources = node.get("sources");
        if (sources != null && sources.isArray()) {
            int i = 0;
            for (JsonNode source : sources) {
                String path = where + ".sources[" + i++ + "]";
                object(source, path, SOURCE);
                if (source.isObject()) {
                    reading(source, path);
                }
            }
        }
    }

    /** The reading keys a source and a reader share: {@code split}, {@code map}, {@code join}. */
    private void reading(JsonNode node, String where) {
        object(node.get("split"), where + ".split", SPLIT);
        object(node.get("map"), where + ".map", MAP);
        named(node.get("join"), where + ".join", LOOKUP,
                (lookup, path) -> object(lookup.get("split"), path + ".split", SPLIT));
    }

    private void object(JsonNode node, String where, Set<String> known) {
        if (node == null || !node.isObject()) {
            return;
        }
        for (String key : node.propertyNames()) {
            if (!known.contains(key)) {
                String nearest = nearest(key, known);
                warnings.add(file + ": " + (where.isEmpty() ? "" : where + ": ") + "unknown key \"" + key
                        + "\" is ignored" + (nearest == null ? "" : "; did you mean \"" + nearest + "\"?"));
            }
        }
    }

    /** The known key that differs from {@code key} in case only, or by at most two edits. */
    static String nearest(String key, Set<String> known) {
        String best = null;
        int bestDistance = 3;
        for (String candidate : known.stream().sorted().toList()) {
            int distance = candidate.equalsIgnoreCase(key) ? 0
                    : distance(key.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT));
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Levenshtein distance. */
    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
