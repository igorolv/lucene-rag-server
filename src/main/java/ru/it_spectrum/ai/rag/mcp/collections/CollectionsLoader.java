package ru.it_spectrum.ai.rag.mcp.collections;

import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Reads {@code collections.json}: the only place the collections this installation can build are
 * described. A missing or empty file yields no collections; a file that is not valid JSON, or
 * whose top level has the wrong shape, is an error. A single bad entry is reported through
 * {@link CollectionDefinition#configError()} and leaves the others usable.
 *
 * <p>Only the shape of the file is read here. Whether a source fits its steps — the format
 * exists, the templates of {@code map} use values the format gives — is checked by
 * {@code ingest.StepRegistry#checked}, which callers apply to the result.
 *
 * <p>A top-level {@code readers} object holds named sets of the reading keys of a source
 * ({@link #READER_KEYS}); a source names one with {@code reader}, and every key the source sets
 * itself replaces the reader's key whole (docs/decisions.md D18). The reader is resolved here,
 * before the steps are checked, so the rest of the server sees only complete sources.
 *
 * <p>A top-level {@code models} object names the embedding models; a collection with
 * {@code "vectors": {"model": "<name>"}} is embedded with one of them, a collection without
 * {@code vectors} is searched lexically only.
 *
 * <p>Keys the current version does not know are ignored without a warning, so a file written for
 * a later version still loads; check spelling when a setting seems to have no effect.
 *
 * <p>Modeled on jdbc-mcp-server {@code connection/ConnectionsLoader}.
 */
public final class CollectionsLoader {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The keys of a source a named reader may hold. */
    static final List<String> READER_KEYS = List.of("convert", "format", "split", "map");

    private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}");

    private CollectionsLoader() {
    }

    public static List<CollectionDefinition> load(RagMcpProperties properties, UnaryOperator<String> env) {
        return load(properties.resolvedCollectionsFile(), env);
    }

    public static List<CollectionDefinition> load(Path file, UnaryOperator<String> env) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        final JsonNode root;
        try {
            String text = Files.readString(file);
            if (text.isBlank()) {
                return List.of();
            }
            root = JSON.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the collections file " + file + ": " + e.getMessage(), e);
        } catch (JacksonException e) {
            throw new IllegalStateException("The collections file " + file + " is not valid JSON: "
                    + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("The collections file " + file + " must be a JSON object");
        }
        JsonNode collections = root.get("collections");
        if (collections == null || collections.isNull()) {
            return List.of();
        }
        if (!collections.isObject()) {
            throw new IllegalStateException("\"collections\" in " + file + " must be a JSON object");
        }
        JsonNode readers = root.get("readers");
        if (readers == null || readers.isNull()) {
            readers = JSON.createObjectNode();
        } else if (!readers.isObject()) {
            throw new IllegalStateException("\"readers\" in " + file + " must be a JSON object");
        }
        JsonNode modelsNode = root.get("models");
        if (modelsNode != null && !modelsNode.isNull() && !modelsNode.isObject()) {
            throw new IllegalStateException("\"models\" in " + file + " must be a JSON object");
        }
        Map<String, Object> models = models(modelsNode);
        Path baseDir = file.toAbsolutePath().getParent();
        List<CollectionDefinition> result = new ArrayList<>();
        for (var entry : collections.properties()) {
            CollectionDefinition definition = collection(entry.getKey(), entry.getValue(), readers, baseDir, env);
            result.add(definition.usable() ? withVectors(definition, entry.getValue(), models) : definition);
        }
        return List.copyOf(result);
    }

    /** Whether a name can be the name of a model and of the directory of its files. */
    public static boolean validModelName(String name) {
        return name != null && MODEL_NAME.matcher(name).matches();
    }

    /** The entries of {@code models}: a {@link ModelDefinition}, or the message of a wrong entry. */
    private static Map<String, Object> models(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return result;
        }
        for (var entry : node.properties()) {
            String name = entry.getKey();
            JsonNode model = entry.getValue();
            try {
                if (!MODEL_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("the name must be letters, digits, '.', '_' or '-', "
                            + "up to 64 characters, not starting with a dot; it is the directory of the model files");
                }
                if (!model.isObject()) {
                    throw new IllegalArgumentException("the entry must be a JSON object");
                }
                String type = text(model.get("type"));
                int maxTokens = number(model.get("maxTokens"), "maxTokens", ModelDefinition.DEFAULT_MAX_TOKENS);
                if (maxTokens < 1) {
                    throw new IllegalArgumentException("maxTokens must be at least 1");
                }
                String pooling = text(model.get("pooling"));
                if (pooling != null && !pooling.equals("mean") && !pooling.equals("cls")) {
                    throw new IllegalArgumentException("pooling must be mean or cls, not '" + pooling + "'");
                }
                result.put(name, new ModelDefinition(name, type == null ? ModelDefinition.DEFAULT_TYPE : type,
                        prefix(model.get("queryPrefix"), "queryPrefix"),
                        prefix(model.get("passagePrefix"), "passagePrefix"), maxTokens,
                        pooling == null ? ModelDefinition.DEFAULT_POOLING : pooling));
            } catch (IllegalArgumentException e) {
                result.put(name, "models." + name + ": " + e.getMessage());
            }
        }
        return result;
    }

    private static String prefix(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (!node.isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return node.asString();
    }

    /** The collection with the model its {@code vectors} names, or broken when that is wrong. */
    private static CollectionDefinition withVectors(CollectionDefinition definition, JsonNode node,
                                                    Map<String, Object> models) {
        JsonNode vectors = node.get("vectors");
        if (vectors == null || vectors.isNull()) {
            return definition;
        }
        String model = vectors.isObject() ? text(vectors.get("model")) : null;
        if (model == null) {
            return definition.withError("\"vectors\" must be an object naming a model: {\"model\": \"<name>\"}");
        }
        Object entry = models.get(model);
        if (entry == null) {
            return definition.withError("vectors.model \"" + model + "\" is not defined in \"models\"; defined: "
                    + (models.isEmpty() ? "none" : String.join(", ", models.keySet())));
        }
        if (entry instanceof String error) {
            return definition.withError(error);
        }
        return new CollectionDefinition(definition.name(), definition.description(), definition.sources(),
                (ModelDefinition) entry, null);
    }

    private static CollectionDefinition collection(String name, JsonNode node, JsonNode readers, Path baseDir,
                                                   UnaryOperator<String> env) {
        String description = node.isObject() ? text(node.get("description")) : null;
        String nameProblem = CollectionNames.problem(name);
        if (nameProblem != null) {
            return CollectionDefinition.broken(name, description, nameProblem);
        }
        if (!node.isObject()) {
            return CollectionDefinition.broken(name, null, "the entry must be a JSON object");
        }
        JsonNode sources = node.get("sources");
        if (sources == null || !sources.isArray() || sources.isEmpty()) {
            return CollectionDefinition.broken(name, description, "\"sources\" must be a non-empty array");
        }
        List<SourceDefinition> definitions = new ArrayList<>();
        int index = 0;
        for (JsonNode source : sources) {
            String where = "collections." + name + ".sources[" + index++ + "]";
            try {
                definitions.add(source(withReader(source, readers), where, baseDir, env));
            } catch (IllegalStateException | IllegalArgumentException e) {
                return CollectionDefinition.broken(name, description, where + ": " + e.getMessage());
            }
        }
        return new CollectionDefinition(name, description, List.copyOf(definitions), null);
    }

    /**
     * The source with the keys of the reader it names; a key of the source replaces the reader's
     * key whole, without merging inside {@code split} or {@code map}.
     */
    private static JsonNode withReader(JsonNode node, JsonNode readers) {
        if (!node.isObject() || !node.has("reader") || node.get("reader").isNull()) {
            return node;
        }
        String name = text(node.get("reader"));
        if (name == null) {
            throw new IllegalArgumentException("\"reader\" must be the name of an entry of \"readers\"");
        }
        JsonNode reader = readers.get(name);
        if (reader == null) {
            throw new IllegalArgumentException("reader \"" + name + "\" is not defined in \"readers\"");
        }
        if (!reader.isObject()) {
            throw new IllegalArgumentException("readers." + name + " must be a JSON object");
        }
        ObjectNode merged = JSON.createObjectNode();
        for (String key : READER_KEYS) {
            if (reader.has(key)) {
                merged.set(key, reader.get(key));
            }
        }
        for (var entry : node.properties()) {
            merged.set(entry.getKey(), entry.getValue());
        }
        return merged;
    }

    private static SourceDefinition source(JsonNode node, String where, Path baseDir, UnaryOperator<String> env) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("the source must be a JSON object");
        }
        if (node.has("chunk")) {
            throw new IllegalArgumentException("\"chunk\" was renamed to \"split\" (and splitBy to level)");
        }
        String format = text(node.get("format"));
        boolean hasCommand = node.has("command") && !node.get("command").isNull();
        String rawPath = EnvironmentPlaceholders.resolve(text(node.get("path")), where + ".path", env);
        Path path = null;
        CommandSettings command = null;
        if (hasCommand) {
            if (rawPath != null) {
                throw new IllegalArgumentException("a source has either \"path\" or \"command\", not both");
            }
            command = CommandSettings.of(value(node.get("command")), value(node.get("timeoutSeconds")), baseDir);
        } else {
            if (rawPath == null || rawPath.isBlank()) {
                throw new IllegalArgumentException("\"path\" or \"command\" is required");
            }
            rawPath = rawPath.trim();
            if (rawPath.startsWith("~")) {
                rawPath = System.getProperty("user.home", ".") + rawPath.substring(1);
            }
            path = baseDir.resolve(rawPath).normalize();
        }
        SplitSettings split = split(node.get("split"));
        MapSettings map = map(node.get("map"));
        return new SourceDefinition(path, text(node.get("include")), text(node.get("exclude")), format, split, map,
                command, convert(node.get("convert"), baseDir), where(node.get("where")));
    }

    /** {@code "field": value} keeps records with the value, {@code "field": {"not": value}} those without. */
    private static WhereSettings where(JsonNode node) {
        if (node == null || node.isNull()) {
            return WhereSettings.ALL;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("\"where\" must be a JSON object of field to condition");
        }
        List<WhereSettings.Condition> conditions = new ArrayList<>();
        for (var entry : node.properties()) {
            String field = entry.getKey();
            JsonNode condition = entry.getValue();
            String value = scalar(condition);
            boolean not = false;
            if (value == null && condition.isObject() && condition.size() == 1 && condition.has("not")) {
                value = scalar(condition.get("not"));
                not = true;
            }
            if (value == null) {
                throw new IllegalArgumentException("where." + field
                        + " must be a value or {\"not\": value}; no other conditions are supported");
            }
            conditions.add(new WhereSettings.Condition(field, value, not));
        }
        return new WhereSettings(conditions);
    }

    private static List<ConvertStep> convert(JsonNode node, Path baseDir) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("\"convert\" must be an array of steps");
        }
        List<ConvertStep> steps = new ArrayList<>();
        for (JsonNode step : node) {
            if (!step.isObject()) {
                throw new IllegalArgumentException("every step of \"convert\" must be a JSON object");
            }
            Map<String, Object> settings = new LinkedHashMap<>();
            for (var entry : step.properties()) {
                settings.put(entry.getKey(), value(entry.getValue()));
            }
            steps.add(new ConvertStep(settings, baseDir));
        }
        return List.copyOf(steps);
    }

    /** A JSON value as plain Java: strings, numbers, booleans, lists and maps; {@code null} for none. */
    private static Object value(JsonNode node) {
        return node == null || node.isNull() ? null : JSON.treeToValue(node, Object.class);
    }

    private static SplitSettings split(JsonNode node) {
        if (node == null || node.isNull()) {
            return SplitSettings.DEFAULT;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("\"split\" must be a JSON object");
        }
        int level = SplitSettings.DEFAULT.level();
        String configured = text(node.get("level"));
        if (configured != null) {
            if ("document".equalsIgnoreCase(configured)) {
                level = SplitSettings.DOCUMENT;
            } else if (configured.matches("[hH][1-6]")) {
                level = configured.charAt(1) - '0';
            } else {
                throw new IllegalArgumentException("split.level must be h1…h6 or \"document\"");
            }
        }
        return new SplitSettings(level, text(node.get("headingPattern")),
                number(node.get("maxChars"), "split.maxChars", SplitSettings.DEFAULT.maxChars()),
                number(node.get("minChars"), "split.minChars", SplitSettings.DEFAULT.minChars()),
                text(node.get("list")), bool(node.get("tableRows"), "split.tableRows"));
    }

    private static MapSettings map(JsonNode node) {
        if (node == null || node.isNull()) {
            return MapSettings.DEFAULT;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("\"map\" must be a JSON object");
        }
        Map<String, String> fields = null;
        JsonNode fieldsNode = node.get("fields");
        if (fieldsNode != null && !fieldsNode.isNull()) {
            if (!fieldsNode.isObject()) {
                throw new IllegalArgumentException("map.fields must be a JSON object of name to template");
            }
            fields = new LinkedHashMap<>();
            for (var field : fieldsNode.properties()) {
                String template = scalar(field.getValue());
                if (template == null) {
                    throw new IllegalArgumentException("map.fields." + field.getKey() + " must be a string");
                }
                fields.put(field.getKey(), template);
            }
        }
        return new MapSettings(text(node.get("pathPattern")), text(node.get("document")), text(node.get("id")), text(node.get("title")),
                strings(node.get("breadcrumb"), "map.breadcrumb"), text(node.get("text")),
                fields == null ? null : Collections.unmodifiableMap(fields),
                strings(node.get("inheritFields"), "map.inheritFields"), strings(node.get("keys"), "map.keys"));
    }

    private static boolean bool(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (!node.isBoolean()) {
            throw new IllegalArgumentException(field + " must be true or false");
        }
        return node.booleanValue();
    }

    private static List<String> strings(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(field + " must be an array of strings");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = scalar(item);
            if (value == null) {
                throw new IllegalArgumentException(field + " must be an array of strings");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    /** A string, number or boolean as text; {@code null} for anything else. Blank strings are kept. */
    private static String scalar(JsonNode node) {
        return node == null || node.isNull() || node.isContainer() ? null : node.asString();
    }

    private static int number(JsonNode node, String field, int fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException(field + " must be a whole number");
        }
        return node.asInt();
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isContainer()) {
            return null;
        }
        String value = node.asString();
        return value == null || value.isBlank() ? null : value;
    }
}
