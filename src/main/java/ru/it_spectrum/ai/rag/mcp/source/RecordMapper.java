package ru.it_spectrum.ai.rag.mcp.source;

import ru.it_spectrum.ai.rag.mcp.collections.MapSettings;
import ru.it_spectrum.ai.rag.mcp.collections.Template;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lays the units of a document out as records by {@link MapSettings}: the step between "how to
 * cut" and what is indexed.
 *
 * <p>Values are computed in a fixed order, each available to the templates after it: document
 * key, title, slug, breadcrumb, text, id, fields. Whatever the id template gives, ids are made
 * unique inside the document: a repeated id gets {@code -2}, {@code -3}, and the parts of a text
 * split by {@code maxChars} get {@code ~2}, {@code ~3}.
 */
public final class RecordMapper {

    static final String DEFAULT_DOCUMENT = "{frontmatter.id|document.path}";
    static final String DEFAULT_TITLE = "{heading}";
    static final String DEFAULT_ID = "{document.key}#{slug}";
    static final String DEFAULT_TEXT = "{body}";

    private RecordMapper() {
    }

    /**
     * @param maxChars a longer text is split into several records
     * @throws IllegalArgumentException when a document key or a record id comes out empty
     */
    public static List<ChunkRecord> map(SourceDocument document, List<Chunker.Unit> units, MapSettings map,
                                        int maxChars) {
        Map<String, List<String>> base = new HashMap<>();
        document.fields().forEach((name, values) -> base.put("frontmatter." + name, values));
        String[] segments = document.path().split("/");
        for (int i = 0; i < segments.length; i++) {
            put(base, "path[" + i + "]", segments[i]);
        }
        String fileName = segments[segments.length - 1];
        int dot = fileName.lastIndexOf('.');
        put(base, "path", document.path());
        put(base, "path.name", dot > 0 ? fileName.substring(0, dot) : fileName);
        put(base, "path.dir", document.path().contains("/")
                ? document.path().substring(0, document.path().lastIndexOf('/')) : "");
        Pattern pathPattern = map.compiledPathPattern();
        if (pathPattern != null) {
            Matcher matcher = pathPattern.matcher(document.path());
            if (matcher.find()) {
                for (String group : pathPattern.namedGroups().keySet()) {
                    put(base, "path." + group, matcher.group(group));
                }
            }
        }
        put(base, "document.path", document.path());
        put(base, "document.title", document.title());

        String key = Template.render(or(map.document(), DEFAULT_DOCUMENT), base::get).strip();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("map.document gives an empty key for " + document.path());
        }
        put(base, "document.key", key);

        List<ChunkRecord> records = new ArrayList<>();
        Map<String, Integer> usedIds = new HashMap<>();
        for (Chunker.Unit unit : units) {
            Map<String, List<String>> values = new HashMap<>(base);
            put(values, "heading", unit.heading());
            unit.groups().forEach((name, value) -> put(values, "heading." + name, value));
            put(values, "parents", String.join(ChunkRecord.BREADCRUMB_SEPARATOR, unit.ancestors()));
            put(values, "body", unit.text());

            String title = Template.render(or(map.title(), DEFAULT_TITLE), values::get).strip();
            put(values, "title", title);
            String slug = unit.intro() ? "intro" : slug(title);
            put(values, "slug", slug.isEmpty() ? "section" : slug);

            String breadcrumb = breadcrumb(document, unit, title, map, values);
            put(values, "breadcrumb", breadcrumb);

            String text = Template.render(or(map.text(), DEFAULT_TEXT), values::get).strip();
            if (text.isEmpty()) {
                continue;
            }
            String id = Template.render(or(map.id(), DEFAULT_ID), values::get).strip();
            if (id.isEmpty()) {
                throw new IllegalArgumentException("map.id gives an empty id for \"" + unit.heading()
                        + "\" in " + document.path());
            }
            int seen = usedIds.merge(id, 1, Integer::sum);
            String base_id = seen == 1 ? id : id + "-" + seen;

            Map<String, List<String>> fields = fields(document, map, values);
            List<String> parts = Chunker.split(text, maxChars);
            for (int i = 0; i < parts.size(); i++) {
                records.add(new ChunkRecord(i == 0 ? base_id : base_id + "~" + (i + 1), key, title, breadcrumb,
                        parts.get(i), document.path(), records.size(), fields));
            }
        }
        return records;
    }

    private static String breadcrumb(SourceDocument document, Chunker.Unit unit, String title, MapSettings map,
                                     Map<String, List<String>> values) {
        List<String> parts = new ArrayList<>();
        if (map.breadcrumb() != null) {
            for (String template : map.breadcrumb()) {
                String part = Template.render(template, values::get).strip();
                if (!part.isEmpty()) {
                    parts.add(part);
                }
            }
        } else {
            parts.add(document.title());
            for (String ancestor : unit.ancestors()) {
                if (!ancestor.equals(parts.getLast())) {
                    parts.add(ancestor);
                }
            }
            if (!title.isEmpty() && !title.equals(parts.getLast())) {
                parts.add(title);
            }
        }
        return String.join(ChunkRecord.BREADCRUMB_SEPARATOR, parts);
    }

    private static Map<String, List<String>> fields(SourceDocument document, MapSettings map,
                                                    Map<String, List<String>> values) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        List<String> inherit = map.inheritFields() == null ? List.of(MapSettings.ALL_FIELDS) : map.inheritFields();
        if (inherit.contains(MapSettings.ALL_FIELDS)) {
            fields.putAll(document.fields());
        } else {
            for (String name : inherit) {
                List<String> inherited = document.fields().get(name);
                if (inherited != null) {
                    fields.put(name, inherited);
                }
            }
        }
        if (map.fields() != null) {
            for (var field : map.fields().entrySet()) {
                List<String> mapped = Template.values(field.getValue(), values::get);
                if (mapped.isEmpty()) {
                    fields.remove(field.getKey());
                } else {
                    fields.put(field.getKey(), mapped);
                }
            }
        }
        return Map.copyOf(fields);
    }

    private static void put(Map<String, List<String>> values, String name, String value) {
        values.put(name, value == null || value.isEmpty() ? List.of() : List.of(value));
    }

    private static String or(String configured, String fallback) {
        return configured == null ? fallback : configured;
    }

    /** Lower-case letters and digits of the title joined by {@code -}; works for any script. */
    static String slug(String title) {
        StringBuilder out = new StringBuilder();
        boolean gap = false;
        for (int i = 0; i < title.length(); ) {
            int cp = title.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                if (gap && !out.isEmpty()) {
                    out.append('-');
                }
                out.appendCodePoint(Character.toLowerCase(cp));
                gap = false;
            } else {
                gap = true;
            }
        }
        String slug = out.toString().toLowerCase(Locale.ROOT);
        return slug.length() > 80 ? slug.substring(0, 80) : slug;
    }
}
