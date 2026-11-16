package ru.it_spectrum.ai.rag.mcp.definition;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * How a unit of a source is laid out as a record: templates over the values the reader provides
 * ({@code docs/collections.md}, "Mapping"). A {@code null} component means the format's default,
 * so a source without a {@code map} block behaves as the format describes.
 *
 * @param pathPattern   regular expression searched in the file path relative to the source; its
 *                      named groups are available as {@code path.<group>}
 * @param document      template of the document key
 * @param id            template of the record id; the server still makes ids unique
 * @param title         template of the record title
 * @param breadcrumb    templates of the breadcrumb parts, joined after empty parts are dropped
 * @param text          template of the indexed and returned passage
 * @param fields        filter field name to the template of its values
 * @param inheritFields document fields copied to the record as they are; {@code *} for all
 * @param keys          fields whose values the {@code lookup} tool finds the record by; {@code null}
 *                      for none
 * @param bodyPattern   regular expression searched in the text of a unit; its named groups are
 *                      available as {@code body.<group>}
 */
public record MapSettings(String pathPattern, String document, String id, String title,
                          List<String> breadcrumb, String text, Map<String, String> fields,
                          List<String> inheritFields, List<String> keys, String bodyPattern) {

    public static final String ALL_FIELDS = "*";

    public MapSettings {
        if (bodyPattern != null) {
            compile("map.bodyPattern", bodyPattern);
        }
        if (pathPattern != null) {
            for (String group : compile("map.pathPattern", pathPattern).namedGroups().keySet()) {
                if (group.equals("name") || group.equals("dir")) {
                    throw new IllegalArgumentException("map.pathPattern: the group name \"" + group
                            + "\" is taken by {path." + group + "}");
                }
            }
        }
    }

    private static Pattern compile(String setting, String pattern) {
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(setting + " is not a valid regular expression: "
                    + e.getDescription());
        }
    }

    /** The compiled {@link #bodyPattern()}, or {@code null}. */
    public Pattern compiledBodyPattern() {
        return bodyPattern == null ? null : Pattern.compile(bodyPattern);
    }

    /** The compiled {@link #pathPattern()}, or {@code null}. */
    public Pattern compiledPathPattern() {
        return pathPattern == null ? null : Pattern.compile(pathPattern);
    }

    public static final MapSettings DEFAULT = new MapSettings(null, null, null, null, null, null, null, null, null,
            null);

    /** Settings without {@code bodyPattern}. */
    public MapSettings(String pathPattern, String document, String id, String title, List<String> breadcrumb,
                       String text, Map<String, String> fields, List<String> inheritFields, List<String> keys) {
        this(pathPattern, document, id, title, breadcrumb, text, fields, inheritFields, keys, null);
    }

    /** Settings without {@code keys} and {@code bodyPattern}. */
    public MapSettings(String pathPattern, String document, String id, String title, List<String> breadcrumb,
                       String text, Map<String, String> fields, List<String> inheritFields) {
        this(pathPattern, document, id, title, breadcrumb, text, fields, inheritFields, null, null);
    }
}
