package ru.it_spectrum.ai.rag.mcp.collections;

import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Reads {@code collections.json}: the only place the collections this installation can build are
 * described. A missing or empty file yields no collections; a file that is not valid JSON, or
 * whose top level has the wrong shape, is an error. A single bad entry is reported through
 * {@link CollectionDefinition#configError()} and leaves the others usable.
 *
 * <p>Keys the current version does not know are ignored without a warning, so a file written for
 * a later version still loads; check spelling when a setting seems to have no effect.
 *
 * <p>Modeled on jdbc-mcp-server {@code connection/ConnectionsLoader}.
 */
public final class CollectionsLoader {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        Path baseDir = file.toAbsolutePath().getParent();
        List<CollectionDefinition> result = new ArrayList<>();
        for (var entry : collections.properties()) {
            result.add(collection(entry.getKey(), entry.getValue(), baseDir, env));
        }
        return List.copyOf(result);
    }

    private static CollectionDefinition collection(String name, JsonNode node, Path baseDir,
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
                definitions.add(source(source, where, baseDir, env));
            } catch (IllegalStateException | IllegalArgumentException e) {
                return CollectionDefinition.broken(name, description, where + ": " + e.getMessage());
            }
        }
        return new CollectionDefinition(name, description, List.copyOf(definitions), null);
    }

    private static SourceDefinition source(JsonNode node, String where, Path baseDir, UnaryOperator<String> env) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("the source must be a JSON object");
        }
        if (node.has("chunk")) {
            throw new IllegalArgumentException("\"chunk\" was renamed to \"split\" (and splitBy to level)");
        }
        String format = text(node.get("format"));
        if (format == null) {
            format = SourceDefinition.MARKDOWN;
        }
        if (!SourceDefinition.MARKDOWN.equals(format)) {
            throw new IllegalArgumentException("format \"" + format + "\" is not supported; supported: markdown");
        }
        String rawPath = EnvironmentPlaceholders.resolve(text(node.get("path")), where + ".path", env);
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("\"path\" is required");
        }
        rawPath = rawPath.trim();
        if (rawPath.startsWith("~")) {
            rawPath = System.getProperty("user.home", ".") + rawPath.substring(1);
        }
        Path path = baseDir.resolve(rawPath).normalize();
        SplitSettings split = split(node.get("split"));
        MapSettings map = map(node.get("map"));
        MarkdownMapRules.check(map, split);
        return new SourceDefinition(path, text(node.get("include")), text(node.get("exclude")), format, split, map);
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
                number(node.get("minChars"), "split.minChars", SplitSettings.DEFAULT.minChars()));
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
                strings(node.get("inheritFields"), "map.inheritFields"));
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
