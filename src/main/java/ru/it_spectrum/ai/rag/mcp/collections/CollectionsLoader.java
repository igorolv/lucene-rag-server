package ru.it_spectrum.ai.rag.mcp.collections;

import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        return new SourceDefinition(path, text(node.get("include")), text(node.get("exclude")), format,
                chunk(node.get("chunk")));
    }

    private static ChunkSettings chunk(JsonNode node) {
        if (node == null || node.isNull()) {
            return ChunkSettings.DEFAULT;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("\"chunk\" must be a JSON object");
        }
        int splitBy = ChunkSettings.DEFAULT.splitBy();
        String split = text(node.get("splitBy"));
        if (split != null) {
            if (!split.matches("[hH][1-6]")) {
                throw new IllegalArgumentException("chunk.splitBy must be h1…h6");
            }
            splitBy = split.charAt(1) - '0';
        }
        return new ChunkSettings(splitBy,
                number(node.get("maxChars"), "chunk.maxChars", ChunkSettings.DEFAULT.maxChars()),
                number(node.get("minChars"), "chunk.minChars", ChunkSettings.DEFAULT.minChars()));
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
