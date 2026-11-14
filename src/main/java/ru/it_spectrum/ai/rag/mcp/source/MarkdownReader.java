package ru.it_spectrum.ai.rag.mcp.source;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a Markdown file into a section tree.
 *
 * <ul>
 *   <li>A YAML front matter block between {@code ---} lines at the very top becomes document
 *       fields: a scalar is one value, a list of scalars is several, anything nested is skipped.
 *       A block that is not valid YAML is treated as ordinary text.</li>
 *   <li>ATX headings ({@code #} … {@code ######}) outside fenced code blocks open sections.
 *       Setext headings (underlined with {@code ===} or {@code ---}) are not recognized.</li>
 * </ul>
 */
public final class MarkdownReader {

    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.*?)[ \\t]*#*[ \\t]*$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*$");
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z0-9_.-]+");

    private MarkdownReader() {
    }

    /**
     * @param path    path relative to the source root, {@code /}-separated
     * @param content file text
     */
    public static SourceDocument read(String path, String content) {
        String text = content.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        Map<String, List<String>> fields = new LinkedHashMap<>();
        text = frontMatter(text, fields);

        Node root = new Node(0, null);
        List<Node> open = new ArrayList<>(List.of(root));
        String fence = null;
        for (String line : text.split("\n", -1)) {
            Matcher fenceMatch = FENCE.matcher(line);
            if (fenceMatch.matches()) {
                String marker = fenceMatch.group(1);
                if (fence == null) {
                    fence = marker;
                } else if (marker.charAt(0) == fence.charAt(0) && marker.length() >= fence.length()) {
                    fence = null;
                }
            }
            Matcher heading = fence == null ? HEADING.matcher(line) : null;
            if (heading != null && heading.matches() && !heading.group(2).isBlank()) {
                int level = heading.group(1).length();
                while (open.getLast().level >= level) {
                    open.removeLast();
                }
                Node node = new Node(level, plain(heading.group(2)));
                open.getLast().children.add(node);
                open.add(node);
            } else {
                open.getLast().text.append(line).append('\n');
            }
        }

        List<Section> sections = root.children.stream().map(Node::toSection).toList();
        String title = first(fields, "title");
        if (title == null) {
            title = sections.stream().filter(s -> s.level() == 1).map(Section::title).findFirst()
                    .orElseGet(() -> fileName(path));
        }
        return new SourceDocument(path, title, Map.copyOf(fields), root.text.toString().strip(), sections);
    }

    private static String frontMatter(String text, Map<String, List<String>> fields) {
        if (!text.startsWith("---\n")) {
            return text;
        }
        int end = text.indexOf("\n---", 3);
        while (end >= 0 && end + 4 < text.length() && text.charAt(end + 4) != '\n') {
            end = text.indexOf("\n---", end + 4);
        }
        if (end < 0) {
            return text;
        }
        final Object parsed;
        try {
            parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(text.substring(4, end + 1));
        } catch (YAMLException e) {
            return text;
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            return text;
        }
        for (var entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!FIELD_NAME.matcher(name).matches()) {
                continue;
            }
            List<String> values = new ArrayList<>();
            if (entry.getValue() instanceof Collection<?> items) {
                for (Object item : items) {
                    addScalar(values, item);
                }
            } else {
                addScalar(values, entry.getValue());
            }
            if (!values.isEmpty()) {
                fields.put(name, List.copyOf(values));
            }
        }
        int bodyStart = end + 4;
        return bodyStart >= text.length() ? "" : text.substring(bodyStart + 1);
    }

    private static void addScalar(List<String> values, Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof Collection<?>) {
            return;
        }
        String text = String.valueOf(value).strip();
        if (!text.isEmpty()) {
            values.add(text);
        }
    }

    /** Heading text without the inline markers that would end up in titles and breadcrumbs. */
    private static String plain(String heading) {
        return heading.replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1").replaceAll("[`*]", "").strip();
    }

    private static String first(Map<String, List<String>> fields, String name) {
        List<String> values = fields.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static String fileName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static final class Node {
        final int level;
        final String title;
        final StringBuilder text = new StringBuilder();
        final List<Node> children = new ArrayList<>();

        Node(int level, String title) {
            this.level = level;
            this.title = title;
        }

        Section toSection() {
            return new Section(level, title, text.toString().strip(),
                    children.stream().map(Node::toSection).toList());
        }
    }
}
