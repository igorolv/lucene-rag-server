package ru.it_spectrum.ai.rag.mcp.collections;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.StringReader;
import java.util.regex.Pattern;

/**
 * A collections file written in YAML, read into the same JSON tree a JSON file gives, so the
 * loader reads both alike (docs/decisions.md D81).
 *
 * <p>The file is read as nodes, not as Java objects, so that this class decides what a value is:
 * a plain {@code true} or {@code false} is a boolean, a plain whole number written as JSON writes
 * one (no leading zero, no {@code +}) a number, a plain
 * {@code null}, {@code ~} or nothing is null, and everything else is a string. YAML 1.1, which
 * SnakeYAML follows, would also read {@code on}, {@code off}, {@code yes} and {@code no} as
 * booleans, and {@code on} is a key of {@code join}.
 *
 * <p>Two mistakes of a collections file are reported as such instead of as the YAML they make: a
 * template that starts with <code>{</code> and is not quoted, which YAML reads as a mapping, and a
 * glob that starts with {@code *} and is not quoted, which YAML reads as an alias.
 */
final class YamlTree {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Pattern BOOLEAN = Pattern.compile("true|True|TRUE|false|False|FALSE");
    private static final Pattern NULL = Pattern.compile("|~|null|Null|NULL");
    /** A whole number as JSON writes one: a leading zero, as in a code {@code 0123}, keeps the value a string. */
    private static final Pattern INTEGER = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final Pattern UNQUOTED_TEMPLATE = Pattern.compile("(^|[:-])\\s*\\{");
    private static final Pattern UNQUOTED_GLOB = Pattern.compile("(^|[:-])\\s*\\*");

    private YamlTree() {
    }

    /** Whether a file is a YAML collections file, by its extension. */
    static boolean isYaml(String fileName) {
        String name = fileName.toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    /**
     * @return the tree, or {@code null} for a file without a document
     * @throws IllegalArgumentException naming the line of what cannot be read
     */
    static JsonNode read(String text, String file) {
        final Node root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).compose(new StringReader(text));
        } catch (MarkedYAMLException e) {
            throw new IllegalArgumentException("The collections file " + file + " is not valid YAML: "
                    + e.getProblem() + where(e.getProblemMark()) + hint(text, e.getProblemMark()), e);
        } catch (YAMLException e) {
            throw new IllegalArgumentException("The collections file " + file + " is not valid YAML: "
                    + e.getMessage(), e);
        }
        return root == null ? null : tree(root, file);
    }

    private static JsonNode tree(Node node, String file) {
        return switch (node) {
            case ScalarNode scalar -> scalar(scalar);
            case SequenceNode sequence -> {
                ArrayNode array = NODES.arrayNode();
                for (Node item : sequence.getValue()) {
                    array.add(tree(item, file));
                }
                yield array;
            }
            case MappingNode mapping -> {
                if (looksLikeTemplate(mapping)) {
                    throw new IllegalArgumentException("The collections file " + file + where(mapping.getStartMark())
                            + ": a template that starts with { is read by YAML as a mapping; put it in quotes, "
                            + "as in title: '{heading.name}'");
                }
                ObjectNode object = NODES.objectNode();
                for (NodeTuple tuple : mapping.getValue()) {
                    if (!(tuple.getKeyNode() instanceof ScalarNode key)) {
                        throw new IllegalArgumentException("The collections file " + file
                                + where(tuple.getKeyNode().getStartMark()) + ": a key must be a plain value");
                    }
                    if (object.has(key.getValue())) {
                        throw new IllegalArgumentException("The collections file " + file
                                + where(key.getStartMark()) + ": the key \"" + key.getValue() + "\" is repeated");
                    }
                    object.set(key.getValue(), tree(tuple.getValueNode(), file));
                }
                yield object;
            }
            default -> throw new IllegalArgumentException("The collections file " + file
                    + where(node.getStartMark()) + ": unsupported YAML node");
        };
    }

    /** <code>{heading.name}</code> or <code>{a|b}</code> unquoted: a flow mapping whose keys have no values. */
    private static boolean looksLikeTemplate(MappingNode mapping) {
        if (mapping.getFlowStyle() != DumperOptions.FlowStyle.FLOW || mapping.getValue().isEmpty()) {
            return false;
        }
        for (NodeTuple tuple : mapping.getValue()) {
            if (!(tuple.getValueNode() instanceof ScalarNode value)
                    || value.getScalarStyle() != DumperOptions.ScalarStyle.PLAIN || !value.getValue().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static JsonNode scalar(ScalarNode scalar) {
        String value = scalar.getValue();
        if (scalar.getScalarStyle() != DumperOptions.ScalarStyle.PLAIN) {
            return NODES.stringNode(value);
        }
        if (NULL.matcher(value).matches()) {
            return NODES.nullNode();
        }
        if (BOOLEAN.matcher(value).matches()) {
            return NODES.booleanNode(value.equalsIgnoreCase("true"));
        }
        if (INTEGER.matcher(value).matches()) {
            try {
                return NODES.numberNode(Long.parseLong(value));
            } catch (NumberFormatException e) {
                return NODES.stringNode(value);
            }
        }
        return NODES.stringNode(value);
    }

    private static String where(Mark mark) {
        return mark == null ? "" : " at line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1);
    }

    /** What an error of YAML syntax usually means in a collections file. */
    private static String hint(String text, Mark mark) {
        if (mark == null) {
            return "";
        }
        String[] lines = text.split("\\R", -1);
        int from = Math.max(0, mark.getLine() - 1);
        int to = Math.min(lines.length - 1, mark.getLine());
        for (int i = from; i <= to; i++) {
            if (UNQUOTED_GLOB.matcher(lines[i]).find()) {
                return "; a glob that starts with * is read by YAML as an alias: put it in quotes, as in "
                        + "include: '**/*.md'";
            }
            if (UNQUOTED_TEMPLATE.matcher(lines[i]).find()) {
                return "; a template that starts with { must be in quotes, as in title: '{heading.name}'";
            }
        }
        return "";
    }
}
