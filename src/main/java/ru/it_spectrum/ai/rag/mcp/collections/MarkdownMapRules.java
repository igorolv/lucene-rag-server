package ru.it_spectrum.ai.rag.mcp.collections;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks the {@code map} block of a Markdown source when the collections file is loaded, so a
 * misspelled value name is a configuration error and not a silently empty field.
 *
 * <p>Values become available in the order records are laid out — document key, title,
 * breadcrumb, text, id, fields — and a template may use only what exists by then.
 */
final class MarkdownMapRules {

    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern FRONT_MATTER = Pattern.compile("frontmatter\\.[A-Za-z0-9_.-]+");
    private static final Pattern PATH_SEGMENT = Pattern.compile("path\\[\\d+]");
    private static final Pattern HEADING_GROUP = Pattern.compile("heading\\.([A-Za-z][A-Za-z0-9]*)");
    private static final Pattern PATH_GROUP = Pattern.compile("path\\.([A-Za-z][A-Za-z0-9]*)");

    private static final Set<String> DOCUMENT = Set.of("path", "path.name", "path.dir", "document.path",
            "document.title");
    private static final Set<String> TITLE = Set.of("document.key", "heading", "parents");
    private static final Set<String> BREADCRUMB = Set.of("title", "slug");
    private static final Set<String> TEXT = Set.of("breadcrumb", "body");

    private MarkdownMapRules() {
    }

    /** @throws IllegalArgumentException naming the template and the value that is not available in it */
    static void check(MapSettings map, SplitSettings split) {
        Set<String> groups = split.headingPattern() == null ? Set.of()
                : split.compiledHeadingPattern().namedGroups().keySet();
        Set<String> pathGroups = map.pathPattern() == null ? Set.of()
                : map.compiledPathPattern().namedGroups().keySet();
        check("map.document", map.document(), 0, groups, pathGroups);
        check("map.title", map.title(), 1, groups, pathGroups);
        if (map.breadcrumb() != null) {
            for (String part : map.breadcrumb()) {
                check("map.breadcrumb", part, 2, groups, pathGroups);
            }
        }
        check("map.text", map.text(), 3, groups, pathGroups);
        check("map.id", map.id(), 3, groups, pathGroups);
        if (map.fields() != null) {
            for (var field : map.fields().entrySet()) {
                if (!FIELD_NAME.matcher(field.getKey()).matches()) {
                    throw new IllegalArgumentException("map.fields: the field name \"" + field.getKey()
                            + "\" may contain only letters, digits, '.', '_' and '-'");
                }
                check("map.fields." + field.getKey(), field.getValue(), 3, groups, pathGroups);
            }
        }
        if (map.inheritFields() != null) {
            for (String name : map.inheritFields()) {
                if (!MapSettings.ALL_FIELDS.equals(name) && !FIELD_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("map.inheritFields: \"" + name + "\" is not a field name");
                }
            }
        }
    }

    private static void check(String where, String template, int stage, Set<String> groups,
                              Set<String> pathGroups) {
        if (template == null) {
            return;
        }
        final List<String> names;
        try {
            names = Template.names(template);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + ": " + e.getMessage());
        }
        for (String name : names) {
            var group = HEADING_GROUP.matcher(name);
            if (group.matches()) {
                if (stage < 1) {
                    throw new IllegalArgumentException(where + ": {" + name + "} is not available here");
                }
                if (!groups.contains(group.group(1))) {
                    throw new IllegalArgumentException(where + ": {" + name + "} needs a named group (?<"
                            + group.group(1) + ">…) in split.headingPattern");
                }
                continue;
            }
            var pathGroup = PATH_GROUP.matcher(name);
            if (pathGroup.matches() && !DOCUMENT.contains(name)) {
                if (!pathGroups.contains(pathGroup.group(1))) {
                    throw new IllegalArgumentException(where + ": {" + name + "} needs a named group (?<"
                            + pathGroup.group(1) + ">…) in map.pathPattern");
                }
                continue;
            }
            boolean known = FRONT_MATTER.matcher(name).matches() || PATH_SEGMENT.matcher(name).matches()
                    || DOCUMENT.contains(name)
                    || stage >= 1 && TITLE.contains(name)
                    || stage >= 2 && BREADCRUMB.contains(name)
                    || stage >= 3 && TEXT.contains(name);
            if (!known) {
                boolean exists = TITLE.contains(name) || BREADCRUMB.contains(name) || TEXT.contains(name);
                throw new IllegalArgumentException(where + ": {" + name + "} is "
                        + (exists ? "not available here" : "not a value of a Markdown source"));
            }
        }
    }
}
