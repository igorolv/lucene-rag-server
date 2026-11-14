package ru.it_spectrum.ai.rag.mcp.collections;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The template language of the {@code map} block: literal text with {@code {name}} placeholders.
 *
 * <ul>
 *   <li>{@code {a}} is replaced by the first value of {@code a}, or by nothing when it has none.</li>
 *   <li>{@code {a|b}} takes the first of the alternatives that has a value.</li>
 *   <li>A template that is exactly one placeholder yields every value of it
 *       ({@link #values}); this is how a multi-valued field is copied.</li>
 * </ul>
 *
 * There is no escape for a literal brace.
 */
public final class Template {

    private Template() {
    }

    /**
     * Names referenced by the template, alternatives included, in order of appearance.
     *
     * @throws IllegalArgumentException when a brace is not closed or a placeholder is empty
     */
    public static List<String> names(String template) {
        List<String> names = new ArrayList<>();
        for (Part part : parse(template)) {
            names.addAll(part.alternatives);
        }
        return names;
    }

    /** The template with every placeholder replaced by the first value of its first non-empty alternative. */
    public static String render(String template, Function<String, List<String>> lookup) {
        StringBuilder out = new StringBuilder();
        for (Part part : parse(template)) {
            if (part.alternatives.isEmpty()) {
                out.append(part.literal);
            } else {
                List<String> values = resolve(part, lookup);
                if (!values.isEmpty()) {
                    out.append(values.getFirst());
                }
            }
        }
        return out.toString();
    }

    /**
     * Values a template yields: all values of the placeholder when the template is one placeholder
     * and nothing else, otherwise the rendered text. Blank results are dropped.
     */
    public static List<String> values(String template, Function<String, List<String>> lookup) {
        List<Part> parts = parse(template.strip());
        if (parts.size() == 1 && !parts.getFirst().alternatives.isEmpty()) {
            return resolve(parts.getFirst(), lookup).stream().map(String::strip).filter(v -> !v.isEmpty()).toList();
        }
        String rendered = render(template, lookup).strip();
        return rendered.isEmpty() ? List.of() : List.of(rendered);
    }

    private static List<String> resolve(Part part, Function<String, List<String>> lookup) {
        for (String name : part.alternatives) {
            List<String> values = lookup.apply(name);
            if (values != null && values.stream().anyMatch(v -> v != null && !v.isBlank())) {
                return values;
            }
        }
        return List.of();
    }

    private static List<Part> parse(String template) {
        List<Part> parts = new ArrayList<>();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            if (open < 0) {
                parts.add(new Part(template.substring(i), List.of()));
                break;
            }
            if (open > i) {
                parts.add(new Part(template.substring(i, open), List.of()));
            }
            int close = template.indexOf('}', open + 1);
            if (close < 0) {
                throw new IllegalArgumentException("unclosed '{' in template \"" + template + "\"");
            }
            List<String> alternatives = new ArrayList<>();
            for (String name : template.substring(open + 1, close).split("\\|", -1)) {
                if (name.isBlank()) {
                    throw new IllegalArgumentException("empty placeholder in template \"" + template + "\"");
                }
                alternatives.add(name.strip());
            }
            parts.add(new Part(null, List.copyOf(alternatives)));
            i = close + 1;
        }
        return parts;
    }

    /** Literal text, or a placeholder with its alternatives. */
    private record Part(String literal, List<String> alternatives) { }
}
