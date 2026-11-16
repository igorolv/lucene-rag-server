package ru.it_spectrum.ai.rag.mcp.definition;

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
 * Reads {@code indexes.json}: the only place the indexes this installation can build are
 * described. A missing or empty file yields no indexes; a file that is not valid JSON, or
 * whose top level has the wrong shape, is an error. A single bad entry is reported through
 * {@link IndexDefinition#configError()} and leaves the others usable.
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
 * <p>A top-level {@code models} object names the embedding models; an index with
 * {@code "vectors": {"model": "<name>"}} is embedded with one of them, one with
 * {@code "vectors": true} with the model marked {@code "default": true} (docs/decisions.md D80),
 * and an index without {@code vectors} is searched lexically only.
 *
 * <p>A top-level {@code vars} object names values for the {@code ${name}} placeholders of the
 * file and of the files it imports; they are looked up before the environment
 * (docs/decisions.md D79).
 *
 * <p>An index may name its project, {@code "project": "billing"}; the records get it as the field
 * {@code project} (docs/decisions.md D83). An imported file may name it once at its top level for
 * all its indexes, and an index of it may override it (D85); the main file has no such key, as it
 * may hold the indexes of several projects.
 *
 * <p>A top-level {@code import} array names other indexes files, usually kept with the
 * sources they describe. An imported file holds {@code indexes}, {@code readers} and
 * {@code project} only;
 * its relative paths and the working directory of its commands are its own directory, its
 * indexes name the models of the main file, and its commands run only when its entry of
 * {@code import} allows them (docs/decisions.md D78). What goes wrong with an import is reported
 * beside the indexes ({@link LoadedIndexes#importProblems()}).
 *
 * <p>Keys the current version does not know are ignored without a warning, so a file written for
 * a later version still loads; check spelling when a setting seems to have no effect.
 *
 * <p>Modeled on jdbc-mcp-server {@code connection/ConnectionsLoader}.
 */
public final class IndexesLoader {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The keys of a source a named reader may hold. */
    static final List<String> READER_KEYS = List.of("convert", "format", "split", "map", "join");

    private static final Pattern JOIN_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    private static final Pattern VAR_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}");
    private static final Pattern PROJECT = Pattern.compile("[A-Za-z0-9._-]+");

    private IndexesLoader() {
    }

    public static List<IndexDefinition> load(RagMcpProperties properties, UnaryOperator<String> env) {
        return load(properties.resolvedIndexesFile(), env);
    }

    /** The indexes of the file and of the files it imports, without the problems of its imports. */
    public static List<IndexDefinition> load(Path file, UnaryOperator<String> env) {
        return read(file, env).indexes();
    }

    public static LoadedIndexes read(RagMcpProperties properties, UnaryOperator<String> env) {
        return read(properties.resolvedIndexesFile(), env);
    }

    /**
     * The indexes of the file and of the files its {@code import} names, with what went wrong
     * with an import.
     *
     * @throws IllegalStateException when the file itself cannot be used
     */
    public static LoadedIndexes read(Path file, UnaryOperator<String> env) {
        if (!Files.isRegularFile(file)) {
            return LoadedIndexes.EMPTY;
        }
        final List<IndexDefinition> result;
        final Map<String, Object> models;
        final String defaultModel;
        final JsonNode root;
        try {
            root = root(file);
            if (root == null) {
                return LoadedIndexes.EMPTY;
            }
            env = withVars(root.get("vars"), file, env);
            JsonNode modelsNode = root.get("models");
            if (modelsNode != null && !modelsNode.isNull() && !modelsNode.isObject()) {
                throw new IllegalArgumentException("\"models\" in " + file + " must be a JSON object");
            }
            models = models(modelsNode);
            defaultModel = defaultModel(modelsNode, file);
            if (root.has("project") && !root.get("project").isNull()) {
                throw new IllegalArgumentException("\"project\" at the top of " + file + " is not read: "
                        + "this file may hold the indexes of several projects, so set \"project\" "
                        + "on each index; a file it imports may set it at its top");
            }
            result = new ArrayList<>(collections(root, file, models, defaultModel, env, null));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        Map<String, Path> owners = new LinkedHashMap<>();
        for (IndexDefinition definition : result) {
            owners.put(definition.name(), file);
        }
        List<String> problems = new ArrayList<>();
        UnaryOperator<String> lookup = env;
        JsonNode imports = root.get("import");
        if (imports != null && !imports.isNull()) {
            if (!imports.isArray()) {
                throw new IllegalStateException("\"import\" in " + file + " must be an array of files");
            }
            Path baseDir = file.toAbsolutePath().getParent();
            int index = 0;
            for (JsonNode entry : imports) {
                String where = "import[" + index++ + "]";
                try {
                    imported(entry, where, baseDir, models, defaultModel, lookup, result, owners, problems);
                } catch (IllegalStateException | IllegalArgumentException e) {
                    problems.add(where + ": " + e.getMessage());
                }
            }
        }
        return new LoadedIndexes(result, problems);
    }

    /**
     * The lookup of {@code ${name}} placeholders: the {@code vars} of the indexes file first,
     * then the environment. A value of {@code vars} may itself name environment variables, not
     * other entries of {@code vars}.
     *
     * @throws IllegalArgumentException when {@code vars} is not an object of names to strings
     */
    private static UnaryOperator<String> withVars(JsonNode node, Path file, UnaryOperator<String> env) {
        if (node == null || node.isNull()) {
            return env;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("\"vars\" in " + file + " must be a JSON object of name to value");
        }
        Map<String, String> vars = new LinkedHashMap<>();
        for (var entry : node.properties()) {
            String name = entry.getKey();
            if (!VAR_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("vars." + name + ": a name is letters, digits and '_', not "
                        + "starting with a digit");
            }
            if (!entry.getValue().isString()) {
                throw new IllegalArgumentException("vars." + name + " must be a string");
            }
            vars.put(name, EnvironmentPlaceholders.resolve(entry.getValue().asString(), "vars." + name, env));
        }
        return name -> vars.containsKey(name) ? vars.get(name) : env.apply(name);
    }

    /**
     * The JSON object an indexes file holds, or {@code null} for an empty file.
     *
     * @throws IllegalArgumentException when the file cannot be read or is not a JSON object
     */
    private static JsonNode root(Path file) {
        final JsonNode root;
        try {
            String text = Files.readString(file);
            if (text.isBlank()) {
                return null;
            }
            root = YamlTree.isYaml(file.getFileName().toString()) ? YamlTree.read(text, file.toString())
                    : JSON.readTree(text);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read the indexes file " + file + ": " + e.getMessage(), e);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("The indexes file " + file + " is not valid JSON: "
                    + e.getOriginalMessage(), e);
        }
        if (root == null) {
            return null;
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("The indexes file " + file + " must be "
                    + (YamlTree.isYaml(file.getFileName().toString()) ? "a YAML mapping" : "a JSON object"));
        }
        return root;
    }

    /**
     * The indexes one file describes, with its own {@code readers}; relative paths and the
     * working directory of commands are the directory of that file.
     *
     * @throws IllegalArgumentException when {@code collections} or {@code readers} is not an object
     */
    private static List<IndexDefinition> collections(JsonNode root, Path file, Map<String, Object> models,
                                                     String defaultModel, UnaryOperator<String> env,
                                                     JsonNode fileProject) {
        JsonNode collections = root.get("indexes");
        if (collections == null || collections.isNull()) {
            return List.of();
        }
        if (!collections.isObject()) {
            throw new IllegalArgumentException("\"indexes\" in " + file + " must be a JSON object");
        }
        JsonNode readers = root.get("readers");
        if (readers == null || readers.isNull()) {
            readers = JSON.createObjectNode();
        } else if (!readers.isObject()) {
            throw new IllegalArgumentException("\"readers\" in " + file + " must be a JSON object");
        }
        Path baseDir = file.toAbsolutePath().getParent();
        List<IndexDefinition> result = new ArrayList<>();
        for (var entry : collections.properties()) {
            IndexDefinition definition = collection(entry.getKey(), entry.getValue(), readers, baseDir, env);
            if (definition.usable()) {
                definition = withProject(definition, entry.getValue(), fileProject);
            }
            result.add(definition.usable() ? withVectors(definition, entry.getValue(), models, defaultModel) : definition);
        }
        return result;
    }

    /**
     * Adds the indexes of one entry of {@code import}: a file path, or an object with
     * {@code file} and {@code commands}. An imported file holds {@code indexes}, {@code readers}
     * and {@code project}; its indexes name the models of the main file.
     *
     * @throws IllegalArgumentException when the entry or its file cannot be used
     */
    private static void imported(JsonNode entry, String where, Path baseDir, Map<String, Object> models,
                                 String defaultModel, UnaryOperator<String> env, List<IndexDefinition> result,
                                 Map<String, Path> owners, List<String> problems) {
        JsonNode fileNode = entry.isObject() ? entry.get("file") : entry;
        String raw = fileNode == null || !fileNode.isString() ? null
                : EnvironmentPlaceholders.resolve(text(fileNode), where, env);
        if (raw == null) {
            throw new IllegalArgumentException("an import is a file path or {\"file\": \"<path>\", "
                    + "\"commands\": true}");
        }
        boolean commands = entry.isObject() && bool(entry.get("commands"), "commands");
        raw = raw.trim();
        if (raw.startsWith("~")) {
            raw = System.getProperty("user.home", ".") + raw.substring(1);
        }
        Path file = baseDir.resolve(raw).normalize();
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("the file " + file + " does not exist");
        }
        JsonNode root = root(file);
        if (root == null) {
            return;
        }
        for (String key : List.of("models", "import", "vars")) {
            if (root.has(key) && !root.get(key).isNull()) {
                throw new IllegalArgumentException(file + " has \"" + key + "\"; an imported file holds only "
                        + "\"indexes\", \"readers\" and \"project\"");
            }
        }
        for (IndexDefinition definition : collections(root, file, models, defaultModel, env, root.get("project"))) {
            Path owner = owners.putIfAbsent(definition.name(), file);
            if (owner != null) {
                problems.add(where + ": the index '" + definition.name() + "' of " + file
                        + " is left out, the name is taken by an index of " + owner);
                continue;
            }
            result.add(commands || !definition.usable() ? definition : withoutCommands(definition, file));
        }
    }

    /** The index of an imported file, broken when a source of it runs a command the import does not allow. */
    private static IndexDefinition withoutCommands(IndexDefinition definition, Path file) {
        for (int i = 0; i < definition.sources().size(); i++) {
            if (runsCommand(definition.sources().get(i))) {
                return definition.withError("indexes." + definition.name() + ".sources[" + i
                        + "] runs a command, which the import of " + file + " does not allow; set "
                        + "\"commands\": true in its entry of \"import\" to allow the commands of that file");
            }
        }
        return definition;
    }

    private static boolean runsCommand(SourceDefinition source) {
        return source.command() != null
                || source.convert().stream().anyMatch(step -> step.settings().containsKey("command"))
                || source.joins().stream().anyMatch(join -> runsCommand(join.source()));
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

    /**
     * The index with its project: its own {@code project}, else the one at the top of its file,
     * else none. A value of other characters than those of a name part is a configuration error.
     */
    private static IndexDefinition withProject(IndexDefinition definition, JsonNode node, JsonNode fileProject) {
        JsonNode own = node.get("project");
        boolean ownSet = own != null && !own.isNull();
        JsonNode value = ownSet ? own : fileProject;
        if (value == null || value.isNull()) {
            return definition;
        }
        String where = ownSet ? "\"project\"" : "\"project\" at the top of its file";
        if (!value.isString() || !PROJECT.matcher(value.asString()).matches()) {
            return definition.withError(where + " must be a string of letters, digits, '.', '_' and '-'; found "
                    + value);
        }
        return definition.withProject(value.asString());
    }

    /**
     * The index with the model its {@code vectors} names, or broken when that is wrong.
     * {@code "vectors": true} names the model marked {@code "default": true}; {@code false} or no
     * {@code vectors} leaves the index lexical.
     */
    private static IndexDefinition withVectors(IndexDefinition definition, JsonNode node,
                                                    Map<String, Object> models, String defaultModel) {
        JsonNode vectors = node.get("vectors");
        if (vectors == null || vectors.isNull() || vectors.isBoolean() && !vectors.booleanValue()) {
            return definition;
        }
        final String model;
        if (vectors.isBoolean()) {
            if (defaultModel == null) {
                return definition.withError("\"vectors\": true needs a model marked \"default\": true in "
                        + "\"models\"; defined: " + (models.isEmpty() ? "none" : String.join(", ", models.keySet())));
            }
            model = defaultModel;
        } else {
            model = vectors.isObject() ? text(vectors.get("model")) : null;
            if (model == null) {
                return definition.withError("\"vectors\" must be true, false or an object naming a model: "
                        + "{\"model\": \"<name>\"}");
            }
        }
        Object entry = models.get(model);
        if (entry == null) {
            return definition.withError("vectors.model \"" + model + "\" is not defined in \"models\"; defined: "
                    + (models.isEmpty() ? "none" : String.join(", ", models.keySet())));
        }
        if (entry instanceof String error) {
            return definition.withError(error);
        }
        return new IndexDefinition(definition.name(), definition.description(), definition.project(),
                definition.sources(), (ModelDefinition) entry, null);
    }

    /**
     * The name of the model marked {@code "default": true}, or {@code null}.
     *
     * @throws IllegalArgumentException when {@code default} is not a boolean or two models have it
     */
    private static String defaultModel(JsonNode models, Path file) {
        if (models == null || models.isNull()) {
            return null;
        }
        String found = null;
        for (var entry : models.properties()) {
            JsonNode flag = entry.getValue().isObject() ? entry.getValue().get("default") : null;
            if (flag == null || flag.isNull()) {
                continue;
            }
            if (!flag.isBoolean()) {
                throw new IllegalArgumentException("models." + entry.getKey() + ".default in " + file
                        + " must be true or false");
            }
            if (flag.booleanValue()) {
                if (found != null) {
                    throw new IllegalArgumentException("models " + found + " and " + entry.getKey() + " in " + file
                            + " are both marked \"default\": true; one model is the default");
                }
                found = entry.getKey();
            }
        }
        return found;
    }

    private static IndexDefinition collection(String name, JsonNode node, JsonNode readers, Path baseDir,
                                                   UnaryOperator<String> env) {
        String description = node.isObject() ? text(node.get("description")) : null;
        String nameProblem = IndexNames.problem(name);
        if (nameProblem != null) {
            return IndexDefinition.broken(name, description, nameProblem);
        }
        if (!node.isObject()) {
            return IndexDefinition.broken(name, null, "the entry must be a JSON object");
        }
        JsonNode sources = node.get("sources");
        if (sources == null || !sources.isArray() || sources.isEmpty()) {
            return IndexDefinition.broken(name, description, "\"sources\" must be a non-empty array");
        }
        List<SourceDefinition> definitions = new ArrayList<>();
        int index = 0;
        for (JsonNode source : sources) {
            String where = "indexes." + name + ".sources[" + index++ + "]";
            try {
                definitions.add(source(withReader(source, readers), where, baseDir, env));
            } catch (IllegalStateException | IllegalArgumentException e) {
                return IndexDefinition.broken(name, description, where + ": " + e.getMessage());
            }
        }
        return new IndexDefinition(name, description, List.copyOf(definitions), null);
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
                command, convert(node.get("convert"), baseDir), where(node.get("where")),
                joins(node.get("join"), where, baseDir, env));
    }

    /** The lookups of a source: each a source of its own, read for its units, with a key and what it is matched on. */
    private static List<JoinDefinition> joins(JsonNode node, String where, Path baseDir, UnaryOperator<String> env) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("\"join\" must be a JSON object of name to lookup");
        }
        List<JoinDefinition> joins = new ArrayList<>();
        for (var entry : node.properties()) {
            String name = entry.getKey();
            String label = "join." + name;
            JsonNode lookup = entry.getValue();
            if (!JOIN_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(label + ": the name of a lookup is letters and digits, starting"
                        + " with a letter");
            }
            if (!lookup.isObject()) {
                throw new IllegalArgumentException(label + " must be a JSON object");
            }
            for (String key : List.of("map", "where", "join", "reader")) {
                if (lookup.has(key)) {
                    throw new IllegalArgumentException(label + ": a lookup takes no \"" + key + "\"");
                }
            }
            String key = text(lookup.get("key"));
            String on = text(lookup.get("on"));
            if (key == null || key.isBlank() || on == null || on.isBlank()) {
                throw new IllegalArgumentException(label + ": \"key\" and \"on\" are required templates");
            }
            final SourceDefinition source;
            try {
                source = source(lookup, where + "." + label, baseDir, env);
            } catch (IllegalStateException | IllegalArgumentException e) {
                throw new IllegalArgumentException(label + ": " + e.getMessage(), e);
            }
            joins.add(new JoinDefinition(name, source, key, on));
        }
        return List.copyOf(joins);
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
                text(node.get("list")), bool(node.get("tableRows"), "split.tableRows"),
                bool(node.get("outline"), "split.outline"), text(node.get("selector")));
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
                strings(node.get("inheritFields"), "map.inheritFields"), strings(node.get("keys"), "map.keys"),
                text(node.get("bodyPattern")));
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
